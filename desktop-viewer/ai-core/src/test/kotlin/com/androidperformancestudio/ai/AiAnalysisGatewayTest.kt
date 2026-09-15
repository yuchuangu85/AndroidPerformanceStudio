package com.androidperformancestudio.ai

import java.nio.file.Files
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
    fun `opens Electron-created analysis-session fixture with ordering nullable fields and payload hashes`() =
        withTempDirectory { root ->
            val databasePath = root.resolve("electron-analysis-sessions.db")
            javaClass.getResourceAsStream("/electron-analysis-sessions.db").use { input ->
                requireNotNull(input) { "Electron analysis-session fixture is missing from test resources" }
                Files.copy(input, databasePath)
            }

            SqliteAnalysisSessionRepository(databasePath).use { repository ->
                val sessionId = AnalysisSessionId("electron-ai-session")
                assertEquals(
                    AnalysisSession(
                        id = sessionId,
                        originProfiler = ProfilerKind.SIMPLEPERF,
                        scope = AnalysisScope(AnalysisScopeKind.CURRENT_SELECTION, "Electron-created RenderThread sample"),
                        model = "electron-fixture-model",
                        promptVersion = "electron-fixture-prompt-v1",
                        payloadPolicyVersion = "minimal-v1",
                        sourceSnapshotIds = listOf("electron-source-snapshot-a", "electron-source-snapshot-b"),
                        buildEvidenceBundleIds = listOf("electron-build-evidence-a", "electron-build-evidence-b"),
                        status = AnalysisSessionStatus.SUCCEEDED,
                        createdAt = Instant.parse("2026-09-14T01:00:00Z"),
                        parentSessionId = AnalysisSessionId("electron-parent-session"),
                        summary = "Electron-generated analysis summary",
                        provider = "openai",
                    ),
                    repository.session(sessionId),
                )
                assertEquals(
                    listOf(
                        AnalysisFinding(
                            id = AnalysisFindingId("electron-ai-finding"),
                            severity = AnalysisSeverity.ERROR,
                            title = "RenderThread regression",
                            explanation = "The selected sample took 47 ms.",
                            recommendation = "Avoid repeated render work.",
                            analysisConfidence = 0.9f,
                            performanceEvidenceIds = listOf("electron-ai-evidence"),
                            sourceCandidateIds = listOf("electron-ai-candidate", "electron-ai-null-candidate"),
                        ),
                        AnalysisFinding(
                            id = AnalysisFindingId("electron-ai-info-finding"),
                            severity = AnalysisSeverity.INFO,
                            title = "Source context retained",
                            explanation = "The finding intentionally has no source candidate.",
                            recommendation = "Use the retained trace context.",
                            analysisConfidence = 0.25f,
                            performanceEvidenceIds = listOf("electron-ai-evidence"),
                            sourceCandidateIds = emptyList(),
                        ),
                    ),
                    repository.findings(sessionId),
                )
                assertEquals(
                    listOf(
                        AnalysisEvidenceSummary(
                            id = "electron-ai-evidence",
                            kind = "simpleperf",
                            summary = "The RenderThread sample exceeded the budget",
                            payloadHash = "256e2de97081f63f0aac088ab505d44b40585a9f37625e19c51cec183b49a430",
                        ),
                    ),
                    repository.evidence(sessionId),
                )
                assertEquals(
                    listOf(
                        AnalysisCandidateSummary(
                            id = "electron-ai-candidate",
                            relativePath = "src/main/kotlin/com/example/Renderer.kt",
                            startLine = 31,
                            endLine = 37,
                            resolutionConfidence = "EXACT",
                            contentHash = "a".repeat(64),
                        ),
                        AnalysisCandidateSummary(
                            id = "electron-ai-null-candidate",
                            relativePath = "src/main/kotlin/com/example/Fallback.kt",
                            startLine = null,
                            endLine = null,
                            resolutionConfidence = "PROBABLE",
                            contentHash = null,
                        ),
                    ),
                    repository.candidates(sessionId),
                )
            }

            val databaseBytes = databasePath.toFile().readBytes().decodeToString()
            assertFalse("snapshot-sensitive-body" in databaseBytes)
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
