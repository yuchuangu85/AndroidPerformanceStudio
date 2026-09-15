@file:Suppress("MagicNumber")

package com.androidperformancestudio.ai

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant

/**
 * Produces the checked-in analysis-session SQLite fixture consumed by Electron's
 * cross-runtime compatibility test. The fixture must be written through the
 * Kotlin repository, rather than manually seeded from JavaScript.
 */
public object ElectronAnalysisSessionFixtureWriter {
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 1) { "Expected exactly one fixture output path" }
        val databasePath = Path.of(args.single()).toAbsolutePath()
        databasePath.parent?.let(Files::createDirectories)
        Files.deleteIfExists(databasePath)
        Files.deleteIfExists(Path.of("$databasePath-wal"))
        Files.deleteIfExists(Path.of("$databasePath-shm"))

        SqliteAnalysisSessionRepository(databasePath).use { repository ->
            val sessionId = AnalysisSessionId("kotlin-ai-session")
            repository.saveSession(
                AnalysisSession(
                    id = sessionId,
                    originProfiler = ProfilerKind.LAYOUT_INSPECTOR,
                    scope = AnalysisScope(AnalysisScopeKind.REPORT_SUMMARY, "Kotlin-created Layout Inspector report"),
                    model = null,
                    promptVersion = "kotlin-fixture-prompt-v1",
                    payloadPolicyVersion = "minimal-v1",
                    sourceSnapshotIds = listOf("kotlin-source-snapshot-a", "kotlin-source-snapshot-b"),
                    buildEvidenceBundleIds = listOf("kotlin-build-evidence"),
                    status = AnalysisSessionStatus.RUNNING,
                    createdAt = Instant.parse("2026-09-14T00:00:00Z"),
                    parentSessionId = AnalysisSessionId("kotlin-parent-session"),
                    provider = "openai",
                ),
            )
            repository.saveRequest(
                AnalysisRequest(
                    sessionId = sessionId,
                    originProfiler = ProfilerKind.LAYOUT_INSPECTOR,
                    scope = AnalysisScope(AnalysisScopeKind.REPORT_SUMMARY, "Kotlin-created Layout Inspector report"),
                    evidence = listOf(
                        PerformanceEvidence(
                            id = "kotlin-ai-evidence",
                            kind = "layout",
                            summary = "Frame exceeded the expected budget",
                            structuredPayload = "{\"frameMillis\":42}",
                        ),
                    ),
                    sourceCandidates = listOf(
                        AiSourceCandidate(
                            id = "kotlin-ai-candidate",
                            relativePath = "src/main/kotlin/com/example/Renderer.kt",
                            symbol = "com.example.Renderer.render",
                            resolutionConfidence = "EXACT",
                            reasons = listOf("Qualified type matched", "Build identity verified"),
                            sourceSnippet = "fun render() = Unit",
                            startLine = 17,
                            endLine = 22,
                            contentHash = "d".repeat(64),
                            indexVersion = 7,
                            indexComplete = true,
                        ),
                        AiSourceCandidate(
                            id = "kotlin-ai-null-candidate",
                            relativePath = "src/main/kotlin/com/example/Fallback.kt",
                            symbol = null,
                            resolutionConfidence = "PROBABLE",
                            reasons = listOf("No source range was available"),
                            sourceSnippet = null,
                            startLine = null,
                            endLine = null,
                            contentHash = null,
                            indexVersion = null,
                            indexComplete = null,
                        ),
                    ),
                    promptVersion = "kotlin-fixture-prompt-v1",
                    payloadPolicyVersion = "minimal-v1",
                ),
            )
            repository.saveResult(
                AnalysisResult(
                    sessionId = sessionId,
                    model = "kotlin-fixture-model",
                    summary = "Kotlin-generated analysis summary",
                    findings = listOf(
                        AnalysisFinding(
                            id = AnalysisFindingId("kotlin-ai-finding"),
                            severity = AnalysisSeverity.WARNING,
                            title = "Frame budget regression",
                            explanation = "The captured frame took 42 ms.",
                            recommendation = "Avoid repeated render work.",
                            analysisConfidence = 0.75f,
                            performanceEvidenceIds = listOf("kotlin-ai-evidence"),
                            sourceCandidateIds = listOf("kotlin-ai-candidate", "kotlin-ai-null-candidate"),
                        ),
                    ),
                ),
            )
        }

        // Keep the fixture self-contained in Git: the repository itself writes
        // in WAL mode, so checkpoint and remove the sidecars after it closes.
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                statement.execute("PRAGMA journal_mode=DELETE")
            }
        }
    }
}
