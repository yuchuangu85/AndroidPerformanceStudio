package com.androidperformancestudio.aiplatform.provider.codex

import com.androidperformancestudio.ai.AiAnalysisGateway
import com.androidperformancestudio.ai.AiHttpRequest
import com.androidperformancestudio.ai.AiHttpResponse
import com.androidperformancestudio.ai.AiHttpTransport
import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisResult
import com.androidperformancestudio.ai.AnalysisScope
import com.androidperformancestudio.ai.AnalysisScopeKind
import com.androidperformancestudio.ai.AnalysisSessionId
import com.androidperformancestudio.ai.OpenAiAnalysisGateway
import com.androidperformancestudio.ai.OpenAiResponsesClient
import com.androidperformancestudio.ai.PerformanceEvidence
import com.androidperformancestudio.ai.ProfilerKind
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.ProviderAvailability
import com.androidperformancestudio.aiplatform.model.ProviderEvent
import com.androidperformancestudio.aiplatform.model.ProviderRunRequest
import com.androidperformancestudio.aiplatform.model.ProviderUnavailableCode
import com.androidperformancestudio.aiplatform.providers.ProviderBroker
import com.androidperformancestudio.aiplatform.providers.ProviderRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

class CodexResponsesProviderTest {
    @Test
    fun `discovery connection change is rejected by revision aware factory before any HTTP`() =
        runBlocking {
            var revision = "A"
            var factories = 0
            var httpCalls = 0
            var legacyCalls = 0
            val provider =
                CodexResponsesProvider(
                    gateway = {
                        legacyCalls++
                        error("E_FIXTURE_LEGACY_GATEWAY")
                    },
                    credentialAvailable = {
                        revision = "B"
                        true
                    },
                    gatewayForRun = { request ->
                        factories++
                        assertEquals("A", request.connectionRevision)
                        check(request.connectionRevision == revision) { "E_PROVIDER_CONNECTION_CHANGED" }
                        OpenAiAnalysisGateway(
                            OpenAiResponsesClient(
                                "fixture",
                                request.model,
                                transport =
                                    AiHttpTransport {
                                        httpCalls++
                                        AiHttpResponse(200, """{"output_text":"{\"summary\":\"unexpected\",\"findings\":[]}"}""")
                                    },
                            ),
                        )
                    },
                )
            val events =
                ProviderBroker(
                    ProviderRegistry(listOf(provider)),
                ).run(request().copy(connectionRevision = "A"), setOf("codex")).toList()
            assertEquals(listOf(ProviderEvent.Failure("E_PROVIDER_CONNECTION_CHANGED", false)), events)
            assertEquals(1, factories)
            assertEquals(0, httpCalls)
            assertEquals(0, legacyCalls)
        }

    @Test
    fun `revision aware gateway receives exact request and snapshot survives later connection changes`() =
        runBlocking {
            var revision = "A"
            val destinations = mutableListOf<String>()
            val provider =
                CodexResponsesProvider(
                    gateway = { error("E_FIXTURE_LEGACY_GATEWAY") },
                    credentialAvailable = { true },
                    gatewayForRun = { request ->
                        assertEquals("A", request.connectionRevision)
                        check(request.connectionRevision == revision) { "E_PROVIDER_CONNECTION_CHANGED" }
                        val captured = revision
                        revision = "B"
                        AiAnalysisGateway { analysis ->
                            destinations += captured
                            AnalysisResult(analysis.sessionId, request.model, "Snapshot result", emptyList())
                        }
                    },
                )
            val events =
                ProviderBroker(
                    ProviderRegistry(listOf(provider)),
                ).run(request().copy(connectionRevision = "A"), setOf("codex")).toList()
            assertEquals(ProviderEvent.Completed, events.last())
            assertEquals(listOf("A"), destinations)
        }

    @Test
    fun `optional connection revision accepts bounded opaque values and rejects controls`() {
        assertEquals(null, request().connectionRevision)
        for (revision in listOf("A", "legacy:" + "a".repeat(64), "r".repeat(128))) {
            assertEquals(revision, request().copy(connectionRevision = revision).connectionRevision)
        }
        for (revision in listOf("", "r".repeat(129), "A\nB", "A\u007fB")) {
            assertFailsWith<IllegalArgumentException> { request().copy(connectionRevision = revision) }
        }
    }

    @Test
    fun `Codex model uses the actual Responses gateway with explicit no-tools wire contract`() =
        runBlocking {
            val fixture = Fixture()
            val events = fixture.run()
            assertEquals(1, fixture.requests.size)
            assertEquals(ProviderEvent.Completed, events.last())
            val result = assertIs<ProviderEvent.StructuredResult>(events[1]).result
            assertEquals("gpt-5.3-codex", result.model)
            assertEquals(AnalysisSessionId("codex-session"), result.sessionId)
            val body = Json.parseToJsonElement(fixture.requests.single().body).jsonObject
            assertEquals("gpt-5.3-codex", body.getValue("model").jsonPrimitive.content)
            assertEquals("none", body.getValue("tool_choice").jsonPrimitive.content)
            assertEquals(JsonArray(emptyList()), body.getValue("tools"))
            assertEquals("false", body.getValue("store").jsonPrimitive.content)
            assertEquals("256", body.getValue("max_output_tokens").jsonPrimitive.content)
        }

    @Test
    fun `missing credentials are explicit and no CLI or HTTP fallback is launched`() =
        runBlocking {
            val fixture = Fixture(available = false)
            assertEquals(ProviderAvailability.Unavailable(ProviderUnavailableCode.MISSING_CREDENTIAL), fixture.provider.discover())
            assertEquals(listOf(ProviderEvent.Failure("E_PROVIDER_MISSING_CREDENTIAL", false)), fixture.run())
            assertTrue(fixture.requests.isEmpty())
        }

    @Test
    fun `configured models including namespaced models are preserved exactly`() =
        runBlocking {
            for (model in listOf("gpt-6.1-sol", "free/gpt-6.1-sol", "other-model", " free/gpt-6.1-sol ", "m".repeat(128))) {
                val fixture = Fixture()
                val events = fixture.run(request().copy(model = model))
                assertEquals(ProviderEvent.Completed, events.last())
                val result = events.filterIsInstance<ProviderEvent.StructuredResult>().single().result
                assertEquals(model, result.model)
                val body = Json.parseToJsonElement(fixture.requests.single().body).jsonObject
                assertEquals(model, body.getValue("model").jsonPrimitive.content)
            }
        }

    @Test
    fun `control characters in configured model are rejected without sending a request`() =
        runBlocking {
            for (control in (0..31) + (127..159)) {
                val fixture = Fixture()
                assertEquals(
                    listOf(ProviderEvent.Failure("E_PROVIDER_MODEL", false)),
                    fixture.run(request().copy(model = "gpt-6.1${control.toChar()}-sol")),
                )
                assertTrue(fixture.requests.isEmpty())
            }
        }

    @Test
    fun `blank and oversized configured models remain invalid at the request boundary`() {
        for (model in listOf("", "   ", "m".repeat(129))) {
            assertFailsWith<IllegalArgumentException> { request().copy(model = model) }
        }
    }

    @Test
    fun `denied evidence and network permissions send nothing`() =
        runBlocking {
            val fixture = Fixture()
            for (permissions in listOf(emptySet(), setOf(Permission.READ_EVIDENCE), setOf(Permission.PROVIDER_NETWORK))) {
                assertTrue(fixture.run(request().copy(grantedPermissions = permissions)).last() is ProviderEvent.Failure)
            }
            assertTrue(fixture.requests.isEmpty())
        }

    @Test
    fun `invented evidence and tool output never publish a partial result`() =
        runBlocking {
            for (output in listOf(
                """{"output_text":"{\"summary\":\"bad\",\"findings\":[{\"id\":\"f\",\"severity\":\"WARNING\",\"title\":\"bad\",\"explanation\":\"bad\",\"recommendation\":\"bad\",\"analysisConfidence\":0.5,\"performanceEvidenceIds\":[\"invented\"],\"sourceCandidateIds\":[]}]}"}""",
                """{"output_text":"{\"summary\":\"bad\",\"findings\":[]}","output":[{"type":"function_call"}]}""",
            )) {
                val fixture = Fixture(response = output)
                val events = fixture.run()
                assertTrue(events.last() is ProviderEvent.Failure)
                assertFalse(events.any { it is ProviderEvent.StructuredResult || it == ProviderEvent.Completed })
                assertEquals(1, fixture.requests.size)
            }
        }

    @Test
    fun `credential stays only in the authorization header and is never projected into events`() =
        runBlocking {
            val fixture = Fixture()
            val events = fixture.run()
            val http = fixture.requests.single()
            assertEquals("Bearer fixture-codex-secret", http.headers["Authorization"])
            assertFalse(http.body.contains("fixture-codex-secret"))
            assertFalse(events.toString().contains("fixture-codex-secret"))
        }

    @Test
    fun `cancellation reaches the real HTTP transport and no retry occurs`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            var calls = 0
            val provider =
                CodexResponsesProvider({ model ->
                    OpenAiAnalysisGateway(
                        OpenAiResponsesClient(
                            "fixture",
                            model,
                            transport =
                                AiHttpTransport {
                                    calls++
                                    started.complete(Unit)
                                    try {
                                        awaitCancellation()
                                    } finally {
                                        stopped.complete(Unit)
                                    }
                                },
                        ),
                    )
                }, { true })
            val job = async { provider.run(request()).toList() }
            started.await()
            job.cancelAndJoin()
            stopped.await()
            assertTrue(job.isCancelled)
            assertEquals(1, calls)
        }

    private class Fixture(
        available: Boolean = true,
        response: String = buildJsonObject { put("output_text", """{"summary":"Bounded Codex result","findings":[]}""") }.toString(),
    ) {
        val requests = mutableListOf<AiHttpRequest>()
        val provider =
            CodexResponsesProvider({ model ->
                OpenAiAnalysisGateway(
                    OpenAiResponsesClient(
                        "fixture-codex-secret",
                        model,
                        transport =
                            AiHttpTransport {
                                requests += it
                                AiHttpResponse(200, response)
                            },
                        maxOutputTokens = 256,
                    ),
                )
            }, { available })
        private val broker = ProviderBroker(ProviderRegistry(listOf(provider)))

        suspend fun run(input: ProviderRunRequest = request()): List<ProviderEvent> = broker.run(input, setOf("codex")).toList()
    }

    companion object {
        private fun request() =
            ProviderRunRequest(
                "codex-run",
                1,
                "simpleperf",
                "codex",
                "gpt-5.3-codex",
                AnalysisRequest(
                    AnalysisSessionId("codex-session"),
                    ProfilerKind.SIMPLEPERF,
                    AnalysisScope(AnalysisScopeKind.CURRENT_SELECTION, "Synthetic selected node"),
                    listOf(PerformanceEvidence("evidence-1", "simpleperf-hotspots", "Bounded fact", "{}")),
                    emptyList(),
                    "v1",
                    "v1",
                ),
                setOf(Permission.READ_EVIDENCE, Permission.PROVIDER_NETWORK),
            )
    }
}
