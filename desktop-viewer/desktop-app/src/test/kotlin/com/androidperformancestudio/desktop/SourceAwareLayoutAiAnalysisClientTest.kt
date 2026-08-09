package com.androidperformancestudio.desktop

import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisResult
import com.androidperformancestudio.ai.AnalysisFinding
import com.androidperformancestudio.ai.AnalysisFindingId
import com.androidperformancestudio.ai.AnalysisSeverity
import com.androidperformancestudio.ai.AiAnalysisGateway
import com.androidperformancestudio.ai.InMemoryCredentialStore
import com.androidperformancestudio.ai.SqliteAnalysisSessionRepository
import com.androidperformancestudio.ai.payloadText
import com.androidperformancestudio.source.ContentAddressedSourceCache
import com.androidperformancestudio.source.DefaultSourceWorkspaceService
import com.androidperformancestudio.source.IndexedSourceResolver
import com.androidperformancestudio.source.LocalSourceProvider
import com.androidperformancestudio.source.SourceProviderConfig
import com.androidperformancestudio.source.SourceProviderRegistry
import com.androidperformancestudio.source.SqliteSourceWorkspaceRepository
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SourceAwareLayoutAiAnalysisClientTest {
    @Test
    fun `preflight is immutable and blocked source upload never reaches gateway`() = runBlocking {
        val root = Files.createTempDirectory("layout-ai-preflight")
        val preferences = Preferences.userRoot().node("aps-test-${UUID.randomUUID()}")
        try {
            val sourceRoot = root.resolve("project")
            Files.createDirectories(sourceRoot.resolve("src/main/kotlin/sample"))
            Files.writeString(sourceRoot.resolve("src/main/kotlin/sample/Widget.kt"), "package sample\nclass Widget")
            val repository = SqliteSourceWorkspaceRepository(root.resolve("source.db"))
            val sessions = SqliteAnalysisSessionRepository(root.resolve("analysis.db"))
            val service = DefaultSourceWorkspaceService(
                SourceProviderRegistry(listOf(LocalSourceProvider())),
                repository,
                ContentAddressedSourceCache(root.resolve("cache")),
            )
            val workspace = service.add("sample", SourceProviderConfig.Local(sourceRoot))
            val runtime = SourceWorkspaceRuntime(
                repository,
                service,
                IndexedSourceResolver(repository),
                sessions,
                InMemoryCredentialStore(),
                preferences,
            )
            val requests = mutableListOf<AnalysisRequest>()
            val client = SourceAwareLayoutAiAnalysisClient(
                runtime,
                AiAnalysisGateway { request ->
                    requests += request
                    AnalysisResult(
                        request.sessionId,
                        "test-model",
                        "ok",
                        listOf(
                            AnalysisFinding(
                                AnalysisFindingId("finding"),
                                AnalysisSeverity.WARNING,
                                "title",
                                "explanation",
                                "recommendation",
                                0.8f,
                                listOf(request.evidence.single().id),
                                request.sourceCandidates.map { it.id },
                            ),
                        ),
                    )
                },
            )
            val input = AiAnalysisInput(
                json = "{}",
                sourceEvidence = listOf(LayoutSourceEvidence("node", "sample.Widget", null)),
                selectedNodeId = "node",
            )

            val blocked = client.prepare(input)

            assertFalse(blocked.manifest.canAnalyze)
            assertTrue(blocked.manifest.requiresSourceUploadAuthorization)
            assertEquals("layout:node", blocked.manifest.evidence.single().id)
            assertEquals("src/main/kotlin/sample/Widget.kt", blocked.manifest.sources.single().relativePath)
            assertThrows<IllegalStateException> { client.analyze(blocked) }
            assertTrue(requests.isEmpty())

            val oversizedTree = client.prepare(input.copy(includeSourceSnippets = false, treeTruncated = true))
            assertFalse(oversizedTree.manifest.canAnalyze)
            assertTrue(oversizedTree.manifest.requiresNarrowerScope)

            val performanceOnly = client.prepare(input.copy(includeSourceSnippets = false))
            assertTrue(performanceOnly.manifest.canAnalyze)
            assertTrue(performanceOnly.manifest.sources.isEmpty())
            client.analyze(performanceOnly)
            assertEquals(requests.single().payloadText().encodeToByteArray().size, performanceOnly.manifest.payloadBytes)
            assertTrue(requests.single().sourceCandidates.isEmpty())

            service.setAiSourceUploadAllowed(workspace.id, true)
            val withSource = client.prepare(input)
            assertTrue(withSource.manifest.canAnalyze)
            assertEquals("src/main/kotlin/sample/Widget.kt", withSource.manifest.sources.single().relativePath)
            assertEquals(1, requests.size) // Dismissing a prepared request performs no transport call.
            service.setAiSourceUploadAllowed(workspace.id, false)
            assertThrows<IllegalStateException> { client.analyze(withSource) }
            assertEquals(1, requests.size)
            service.setAiSourceUploadAllowed(workspace.id, true)
            val reauthorized = client.prepare(input)
            val sourceReport = client.analyze(reauthorized)
            assertEquals(requests.last().payloadText().encodeToByteArray().size, reauthorized.manifest.payloadBytes)
            assertEquals("layout-snapshot", sessions.evidence(requests.last().sessionId).single().kind)
            assertEquals(requests.last().sourceCandidates.single().id, sourceReport.findings.single().sourceCandidateIds.single())
            val archivedCandidate = requireNotNull(sourceReport.provenance?.sourceCandidates?.single())
            assertEquals("PROBABLE", archivedCandidate.resolutionConfidence)
            assertEquals("LOCAL", archivedCandidate.providerKind)
            assertEquals("sample", archivedCandidate.repositoryIdentity)
            assertEquals(
                "src/main/kotlin/sample/Widget.kt",
                runtime.rebindArchivedSource(archivedCandidate, workspace.id)?.relativePath,
            )
            assertEquals("OpenAI Responses", sessions.session(requests.last().sessionId)?.provider)
            assertEquals(
                "src/main/kotlin/sample/Widget.kt",
                sessions.candidates(requests.last().sessionId).single().relativePath,
            )
            assertEquals(64, sessions.candidates(requests.last().sessionId).single().contentHash?.length)
            assertFalse(Files.readAllBytes(root.resolve("analysis.db")).decodeToString().contains("class Widget"))
            runtime.close()
        } finally {
            runCatching { preferences.removeNode() }
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cancelling analysis persists cancelled session without a result`() = runBlocking {
        val fixture = Fixture.create()
        try {
            val started = CompletableDeferred<AnalysisRequest>()
            val client = SourceAwareLayoutAiAnalysisClient(
                fixture.runtime,
                AiAnalysisGateway { request ->
                    started.complete(request)
                    awaitCancellation()
                },
            )
            val prepared = client.prepare(
                AiAnalysisInput("{}", selectedNodeId = "node", includeSourceSnippets = false),
            )
            val analysis = async { client.analyze(prepared) }
            val request = started.await()

            analysis.cancelAndJoin()

            assertEquals(
                com.androidperformancestudio.ai.AnalysisSessionStatus.CANCELLED,
                fixture.sessions.session(request.sessionId)?.status,
            )
            assertTrue(fixture.sessions.findings(request.sessionId).isEmpty())
        } finally {
            fixture.close()
        }
    }

    private class Fixture(
        val runtime: SourceWorkspaceRuntime,
        val sessions: SqliteAnalysisSessionRepository,
        private val preferences: Preferences,
        private val root: java.nio.file.Path,
    ) : AutoCloseable {
        override fun close() {
            runtime.close()
            runCatching { preferences.removeNode() }
            root.toFile().deleteRecursively()
        }

        companion object {
            fun create(): Fixture {
                val root = Files.createTempDirectory("layout-ai-cancel")
                val preferences = Preferences.userRoot().node("aps-test-${UUID.randomUUID()}")
                val repository = SqliteSourceWorkspaceRepository(root.resolve("source.db"))
                val sessions = SqliteAnalysisSessionRepository(root.resolve("analysis.db"))
                val service = DefaultSourceWorkspaceService(
                    SourceProviderRegistry(listOf(LocalSourceProvider())),
                    repository,
                    ContentAddressedSourceCache(root.resolve("cache")),
                )
                val runtime = SourceWorkspaceRuntime(
                    repository,
                    service,
                    IndexedSourceResolver(repository),
                    sessions,
                    InMemoryCredentialStore(),
                    preferences,
                )
                return Fixture(runtime, sessions, preferences, root)
            }
        }
    }
}
