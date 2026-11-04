package com.androidperformancestudio.aiplatform.providers

import com.androidperformancestudio.ai.AiHttpRequest
import com.androidperformancestudio.ai.AiHttpResponse
import com.androidperformancestudio.ai.AiHttpTransport
import com.androidperformancestudio.ai.AiSourceCandidate
import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisScope
import com.androidperformancestudio.ai.AnalysisScopeKind
import com.androidperformancestudio.ai.AnalysisSessionId
import com.androidperformancestudio.ai.OpenAiAnalysisGateway
import com.androidperformancestudio.ai.OpenAiResponsesClient
import com.androidperformancestudio.ai.PerformanceEvidence
import com.androidperformancestudio.ai.ProfilerKind
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.ProviderEvent
import com.androidperformancestudio.aiplatform.model.ProviderRunRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** In-process integration of the production gateway and client; only the HTTP transport is fake. */
class OpenAiProviderIntegrationTest {
    @Test
    fun `direct chat reaches production gateway without invented evidence`() =
        runBlocking {
            val fixture = Fixture("""{"summary":"A conversational answer","findings":[]}""")
            val events = fixture.broker.run(chatRequest(), setOf("openai")).toList()
            assertEquals(ProviderEvent.Completed, events.last())
            val result = assertIs<ProviderEvent.StructuredResult>(events[1]).result
            assertEquals("A conversational answer", result.summary)
            assertTrue(result.findings.isEmpty())
            val body = Json.parseToJsonElement(fixture.requests.single().body).jsonObject
            assertTrue(
                body
                    .getValue("instructions")
                    .jsonPrimitive.content
                    .contains("findings as an empty array"),
            )
            val input = Json.parseToJsonElement(body.getValue("input").jsonPrimitive.content).jsonObject
            assertEquals("CHAT", input.getValue("originProfiler").jsonPrimitive.content)
            assertEquals("[]", input.getValue("performanceEvidence").toString())
            assertEquals("A general question", input.getValue("untrustedUserQuestion").jsonPrimitive.content)
        }

    @Test
    fun `direct chat cannot publish findings or acquire source and artifact permissions`() =
        runBlocking {
            val findings = Fixture(output())
            assertRejectedResult(findings, findings.broker.run(chatRequest(), setOf("openai")).toList())
            for (permission in listOf(
                Permission.READ_SOURCE_CANDIDATES,
                Permission.SEND_SOURCE_SNIPPETS,
                Permission.WRITE_ARTIFACT,
                Permission.EXECUTE_SCRIPT,
            )) {
                val fixture = Fixture("""{"summary":"Unused","findings":[]}""")
                val request = chatRequest().let { it.copy(grantedPermissions = it.grantedPermissions + permission) }
                val events = fixture.broker.run(request, setOf("openai")).toList()
                assertTrue(fixture.requests.isEmpty())
                assertEquals(ProviderEvent.Failure("E_PERMISSION_DENIED", retryable = false), events.last())
            }
        }

    @Test
    fun `empty evidence remains invalid for profiler analysis and chat cannot carry source`() =
        runBlocking {
            val fixture = Fixture("""{"summary":"Unused","findings":[]}""")
            val request = request().let { it.copy(analysis = it.analysis.copy(evidence = emptyList())) }
            val events = fixture.broker.run(request, setOf("openai")).toList()
            assertTrue(fixture.requests.isEmpty())
            assertEquals(ProviderEvent.Failure("E_EVIDENCE_LIMIT", retryable = false), events.last())
            assertFailsWith<IllegalArgumentException> {
                chatRequest().analysis.copy(
                    sourceCandidates = request().analysis.sourceCandidates,
                )
            }
            assertFailsWith<IllegalArgumentException> { chatRequest().analysis.copy(evidence = request().analysis.evidence) }
            assertFailsWith<IllegalArgumentException> { chatRequest().analysis.copy(scope = request().analysis.scope) }
        }

    private fun chatRequest(): ProviderRunRequest =
        request().copy(
            agentId = "aps.chat",
            analysis =
                AnalysisRequest(
                    AnalysisSessionId("chat-session"),
                    ProfilerKind.CHAT,
                    AnalysisScope(AnalysisScopeKind.CONVERSATION, "General conversation"),
                    emptyList(),
                    emptyList(),
                    "aps-chat-v1",
                    "aps-minimized-v1",
                    userQuestion = "A general question",
                ),
            grantedPermissions = setOf(Permission.READ_EVIDENCE, Permission.PROVIDER_NETWORK),
        )

    @Test
    fun `actual OpenAI gateway structured result reaches broker once`() =
        runBlocking {
            val fixture = Fixture(output())
            val events = fixture.broker.run(request(), setOf("openai")).toList()

            assertEquals(1, fixture.requests.size)
            assertEquals(ProviderEvent.Started, events.first())
            assertEquals(ProviderEvent.Completed, events.last())
            val result = assertIs<ProviderEvent.StructuredResult>(events[1]).result
            assertEquals(AnalysisSessionId("session-1"), result.sessionId)
            assertEquals("test-model", result.model)
            assertEquals("Measured selected-node work", result.summary)
            assertEquals(listOf("evidence-1"), result.findings.single().performanceEvidenceIds)
            assertEquals(listOf("candidate-1"), result.findings.single().sourceCandidateIds)
            assertEquals(3, events.size)

            val body = Json.parseToJsonElement(fixture.requests.single().body).jsonObject
            assertEquals("test-model", body.getValue("model").jsonPrimitive.content)
            val format =
                body
                    .getValue("text")
                    .jsonObject
                    .getValue("format")
                    .jsonObject
            assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
            assertEquals("android_performance_analysis", format.getValue("name").jsonPrimitive.content)
            val input = Json.parseToJsonElement(body.getValue("input").jsonPrimitive.content).jsonObject
            assertEquals("SIMPLEPERF", input.getValue("originProfiler").jsonPrimitive.content)
            assertEquals(
                "CURRENT_SELECTION",
                input
                    .getValue("scope")
                    .jsonObject
                    .getValue("kind")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `actual OpenAI gateway rejects unknown evidence without publishing partial result`() =
        runBlocking {
            val fixture = Fixture(output(evidenceId = "invented-evidence"))
            val events = fixture.broker.run(request(), setOf("openai")).toList()

            assertRejectedResult(fixture, events)
        }

    @Test
    fun `actual OpenAI gateway rejects unknown source candidate without publishing partial result`() =
        runBlocking {
            val fixture = Fixture(output(candidateId = "invented-candidate"))
            val events = fixture.broker.run(request(), setOf("openai")).toList()

            assertRejectedResult(fixture, events)
        }

    @Test
    fun `fixture credential is confined to fake transport Authorization header`() =
        runBlocking {
            val fixture = Fixture(output())
            val request = request()
            val events = fixture.broker.run(request, setOf("openai")).toList()
            val httpRequest = fixture.requests.single()

            assertEquals("Bearer $FIXTURE_SECRET", httpRequest.headers["Authorization"])
            assertEquals(1, httpRequest.headers.values.count { FIXTURE_SECRET in it })
            assertFalse(httpRequest.body.contains(FIXTURE_SECRET))
            assertFalse(httpRequest.url.contains(FIXTURE_SECRET))
            assertFalse(events.toString().contains(FIXTURE_SECRET))
            assertFalse(request.toString().contains(FIXTURE_SECRET))
            assertEquals(ProviderEvent.Completed, events.last())
        }

    @Test
    fun `denied permissions prevent all actual client transport calls`() =
        runBlocking {
            val fixture = Fixture(output())
            val networkDenied = request().copy(grantedPermissions = setOf(Permission.READ_EVIDENCE, Permission.READ_SOURCE_CANDIDATES))
            val events = fixture.broker.run(networkDenied, setOf("openai")).toList()
            assertEquals(listOf(ProviderEvent.Failure("E_NETWORK_PERMISSION", retryable = false)), events)
            assertTrue(fixture.requests.isEmpty())

            val evidenceDenied = request().copy(grantedPermissions = setOf(Permission.PROVIDER_NETWORK))
            val evidenceEvents = fixture.broker.run(evidenceDenied, setOf("openai")).toList()
            assertEquals(listOf(ProviderEvent.Failure("E_EVIDENCE_PERMISSION", retryable = false)), evidenceEvents)
            assertTrue(fixture.requests.isEmpty())
        }

    private class Fixture(
        outputText: String,
    ) {
        val requests = mutableListOf<AiHttpRequest>()
        private val gateway =
            OpenAiAnalysisGateway(
                OpenAiResponsesClient(
                    apiKey = FIXTURE_SECRET,
                    model = "test-model",
                    endpoint = "https://provider.invalid/v1/responses",
                    transport =
                        AiHttpTransport { request ->
                            requests.add(request)
                            AiHttpResponse(200, buildJsonObject { put("output_text", outputText) }.toString())
                        },
                ),
            )
        val broker = ProviderBroker(ProviderRegistry(listOf(GatewayAnalysisProvider("openai", "test-model", gateway))))
    }

    private fun assertRejectedResult(
        fixture: Fixture,
        events: List<ProviderEvent>,
    ) {
        assertEquals(1, fixture.requests.size)
        assertEquals(ProviderEvent.Started, events.first())
        assertEquals(ProviderEvent.Failure("E_PROVIDER_FAILURE", retryable = false), events.last())
        assertFalse(events.any { it is ProviderEvent.StructuredResult || it == ProviderEvent.Completed })
        assertFalse(events.toString().contains(FIXTURE_SECRET))
    }

    private fun request(): ProviderRunRequest =
        ProviderRunRequest(
            runId = "run-1",
            generation = 1,
            agentId = "simpleperf",
            providerId = "openai",
            model = "test-model",
            analysis =
                AnalysisRequest(
                    sessionId = AnalysisSessionId("session-1"),
                    originProfiler = ProfilerKind.SIMPLEPERF,
                    scope = AnalysisScope(AnalysisScopeKind.CURRENT_SELECTION, "selected call node"),
                    evidence = listOf(PerformanceEvidence("evidence-1", "simpleperf-node", "hot selected node", "{}")),
                    sourceCandidates =
                        listOf(
                            AiSourceCandidate(
                                id = "candidate-1",
                                relativePath = "src/Main.kt",
                                symbol = "render",
                                resolutionConfidence = "PROBABLE",
                                reasons = listOf("symbol match"),
                                sourceSnippet = null,
                            ),
                        ),
                    promptVersion = "v1",
                    payloadPolicyVersion = "v1",
                ),
            grantedPermissions = setOf(Permission.READ_EVIDENCE, Permission.READ_SOURCE_CANDIDATES, Permission.PROVIDER_NETWORK),
        )

    private fun output(
        evidenceId: String = "evidence-1",
        candidateId: String = "candidate-1",
    ): String =
        """
        {
          "summary":"Measured selected-node work",
          "findings":[{
            "id":"finding-1","severity":"WARNING","title":"Repeated selected work",
            "explanation":"The selected sample supports investigation",
            "recommendation":"Inspect the canonical call node","analysisConfidence":0.8,
            "performanceEvidenceIds":["$evidenceId"],"sourceCandidateIds":["$candidateId"]
          }]
        }
        """.trimIndent()

    private companion object {
        // Synthetic fixture only. No local provider configuration or real credential is read.
        const val FIXTURE_SECRET = "fixture-only-not-a-real-api-key"
    }
}
