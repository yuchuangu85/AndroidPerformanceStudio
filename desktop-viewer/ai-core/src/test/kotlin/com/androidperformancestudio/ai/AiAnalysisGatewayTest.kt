package com.androidperformancestudio.ai

import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AiAnalysisGatewayTest {
    @Test
    fun `accepts only evidence-bound structured findings`() = runBlocking {
        val gateway = gatewayReturning(
            """
            {
              "summary":"A verified hotspot was found",
              "findings":[{
                "id":"finding-1","severity":"WARNING","title":"Hot path",
                "explanation":"The selected call is expensive","recommendation":"Reduce repeated work",
                "analysisConfidence":0.8,
                "performanceEvidenceIds":["evidence-1"],
                "sourceCandidateIds":["candidate-1"]
              }]
            }
            """.trimIndent(),
        )

        val result = gateway.analyze(request())

        assertEquals("test-model", result.model)
        assertEquals(listOf("candidate-1"), result.findings.single().sourceCandidateIds)
        assertEquals(0.8f, result.findings.single().analysisConfidence)
    }

    @Test
    fun `rejects unknown source candidate ids without partial acceptance`() = runBlocking {
        val gateway = gatewayReturning(
            """
            {"summary":"bad","findings":[{
              "id":"finding-1","severity":"INFO","title":"x","explanation":"x","recommendation":"x",
              "analysisConfidence":1,"performanceEvidenceIds":["evidence-1"],"sourceCandidateIds":["invented"]
            }]}
            """.trimIndent(),
        )

        val failure = assertFailsWith<IllegalArgumentException> { gateway.analyze(request()) }

        assertTrue(failure.message.orEmpty().contains("unknown source candidate"))
    }

    @Test
    fun `rejects duplicate source candidate ids`() = runBlocking {
        val gateway = gatewayReturning(
            """
            {"summary":"bad","findings":[{
              "id":"finding-1","severity":"INFO","title":"x","explanation":"x","recommendation":"x",
              "analysisConfidence":1,"performanceEvidenceIds":["evidence-1"],
              "sourceCandidateIds":["candidate-1","candidate-1"]
            }]}
            """.trimIndent(),
        )

        val failure = assertFailsWith<IllegalArgumentException> { gateway.analyze(request()) }

        assertTrue(failure.message.orEmpty().contains("duplicate source candidate"))
    }

    @Test
    fun `rejects locally oversized finding text`() = runBlocking {
        val gateway = gatewayReturning(
            """
            {"summary":"bad","findings":[{
              "id":"finding-1","severity":"INFO","title":"${"x".repeat(600)}",
              "explanation":"x","recommendation":"x","analysisConfidence":1,
              "performanceEvidenceIds":["evidence-1"],"sourceCandidateIds":[]
            }]}
            """.trimIndent(),
        )

        val failure = assertFailsWith<IllegalArgumentException> { gateway.analyze(request()) }

        assertTrue(failure.message.orEmpty().contains("title"))
    }

    @Test
    fun `rejects out of range analysis confidence`() = runBlocking {
        val gateway = gatewayReturning(
            """
            {"summary":"bad","findings":[{
              "id":"finding-1","severity":"INFO","title":"x","explanation":"x","recommendation":"x",
              "analysisConfidence":2,"performanceEvidenceIds":["evidence-1"],"sourceCandidateIds":[]
            }]}
            """.trimIndent(),
        )

        assertFailsWith<IllegalArgumentException> { gateway.analyze(request()) }
    }

    @Test
    fun `session repository versions and restores findings without credential data`() = withTempDirectory { root ->
        SqliteAnalysisSessionRepository(root.resolve("analysis.db")).use { repository ->
            val session = AnalysisSession(
                id = AnalysisSessionId("session"),
                originProfiler = ProfilerKind.SIMPLEPERF,
                scope = AnalysisScope(AnalysisScopeKind.CURRENT_SELECTION, "renderFrame"),
                model = null,
                promptVersion = "v1",
                payloadPolicyVersion = "minimal-v1",
                sourceSnapshotIds = listOf("snapshot"),
                buildEvidenceBundleIds = emptyList(),
                status = AnalysisSessionStatus.RUNNING,
                createdAt = Instant.EPOCH,
            )
            repository.saveSession(session)
            repository.saveRequest(
                request().copy(
                    sessionId = session.id,
                    evidence = listOf(
                        PerformanceEvidence("evidence", "layout", "safe summary", "{\"private\":\"source-body\"}"),
                    ),
                ),
            )
            repository.saveResult(
                AnalysisResult(
                    session.id,
                    "test-model",
                    "summary",
                    listOf(
                        AnalysisFinding(
                            AnalysisFindingId("finding"),
                            AnalysisSeverity.WARNING,
                            "title",
                            "explanation",
                            "recommendation",
                            0.75f,
                            listOf("evidence"),
                            listOf("candidate"),
                        ),
                    ),
                ),
            )

            assertEquals(AnalysisSessionStatus.SUCCEEDED, repository.session(session.id)?.status)
            assertEquals(listOf("candidate"), repository.findings(session.id).single().sourceCandidateIds)
            assertEquals("safe summary", repository.evidence(session.id).single().summary)
            assertEquals(64, repository.evidence(session.id).single().payloadHash.length)
            val databaseBytes = root.resolve("analysis.db").toFile().readBytes().decodeToString()
            assertFalse("api-secret" in databaseBytes)
            assertFalse("source-body" in databaseBytes)
        }
    }

    @Test
    fun `in memory credential store supports replace and delete`() {
        val store = InMemoryCredentialStore()
        store.write("openai", "first")
        store.write("openai", "second")
        assertEquals("second", store.read("openai"))
        store.delete("openai")
        assertEquals(null, store.read("openai"))
    }

    private fun request(): AnalysisRequest = AnalysisRequest(
        sessionId = AnalysisSessionId("session"),
        originProfiler = ProfilerKind.LAYOUT_INSPECTOR,
        scope = AnalysisScope(AnalysisScopeKind.CURRENT_SELECTION, "node"),
        evidence = listOf(PerformanceEvidence("evidence-1", "layout", "summary", "{}")),
        sourceCandidates = listOf(AiSourceCandidate("candidate-1", "A.kt", "A", "EXACT", listOf("type"), null)),
        promptVersion = "v1",
        payloadPolicyVersion = "minimal-v1",
    )

    private fun gatewayReturning(output: String): OpenAiAnalysisGateway = OpenAiAnalysisGateway(
        OpenAiResponsesClient(
            apiKey = "api-secret",
            model = "test-model",
            transport = AiHttpTransport {
                AiHttpResponse(
                    200,
                    """{"output_text":${output.jsonString()}}""",
                )
            },
        ),
    )

    private fun withTempDirectory(block: (Path) -> Unit) {
        val directory = createTempDirectory("ai-core-test")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

private fun String.jsonString(): String = buildString {
    append('"')
    this@jsonString.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}
