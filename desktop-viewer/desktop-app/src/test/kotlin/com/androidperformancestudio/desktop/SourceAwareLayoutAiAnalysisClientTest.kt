package com.androidperformancestudio.desktop

import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisResult
import com.androidperformancestudio.ai.AnalysisFinding
import com.androidperformancestudio.ai.AnalysisFindingId
import com.androidperformancestudio.ai.AnalysisSessionStatus
import com.androidperformancestudio.ai.AnalysisSeverity
import com.androidperformancestudio.ai.AiAnalysisGateway
import com.androidperformancestudio.ai.CredentialStore
import com.androidperformancestudio.ai.InMemoryCredentialStore
import com.androidperformancestudio.ai.SqliteAnalysisSessionRepository
import com.androidperformancestudio.ai.payloadText
import com.androidperformancestudio.aiplatform.app.AiPlatformSettings
import com.androidperformancestudio.aiplatform.model.ArtifactFormat
import com.androidperformancestudio.source.ContentAddressedSourceCache
import com.androidperformancestudio.source.DefaultSourceWorkspaceService
import com.androidperformancestudio.source.IndexedSourceResolver
import com.androidperformancestudio.source.LocalSourceProvider
import com.androidperformancestudio.source.SourceProviderConfig
import com.androidperformancestudio.source.SourceProviderRegistry
import com.androidperformancestudio.source.SqliteSourceWorkspaceRepository
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.AbstractPreferences
import java.util.prefs.Preferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
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
            val requestCount = requests.size
            for (omitted in listOf(0, 25)) {
                val partialWithSource = client.prepare(
                    boundedSummaryInput().copy(
                        sourceEvidence = input.sourceEvidence,
                        includeSourceSnippets = true,
                        omittedSourceEvidenceCount = omitted,
                    ),
                )
                assertFalse(partialWithSource.manifest.canAnalyze)
                assertTrue(partialWithSource.manifest.requiresNarrowerScope)
                assertTrue(partialWithSource.manifest.sources.isEmpty())
                assertThrows<IllegalStateException> { client.analyze(partialWithSource) }
            }
            assertEquals(requestCount, requests.size)
            runtime.close()
        } finally {
            runCatching { preferences.removeNode() }
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `outbound evidence summaries omit namespaces and resources while local evidence stays full`() = runBlocking {
        Fixture.create().use { fixture ->
            val requests = mutableListOf<AnalysisRequest>()
            val contexts = mutableListOf<AnalysisRequest>()
            val client = SourceAwareLayoutAiAnalysisClient(
                fixture.runtime,
                AiAnalysisGateway { request ->
                    requests += request
                    AnalysisResult(request.sessionId, "test-model", "ok", emptyList())
                },
                onPreparedContext = { contexts += it },
            )
            val input = AiAnalysisInput(
                json = """{"tree":{"id":"node","className":"Widget","textLength":12,"children":[]}}""",
                sourceEvidence = listOf(LayoutSourceEvidence("node", "private.layout.Widget", "private.layout:id/private_widget")),
                selectedNodeId = "node",
                includeSourceSnippets = false,
            )

            val prepared = client.prepare(input)

            assertTrue(prepared.manifest.canAnalyze)
            assertEquals("layout:node", prepared.manifest.evidence.single().id)
            assertEquals("Widget", prepared.manifest.evidence.single().summary)
            assertEquals("Widget", contexts.single().evidence.single().summary)
            assertTrue(requests.isEmpty())

            val report = client.analyze(prepared)
            val request = requests.single()

            assertSame(contexts.single(), request)
            assertEquals("layout:node", request.evidence.single().id)
            assertEquals("Widget", request.evidence.single().summary)
            assertEquals(input.json, request.evidence.single().structuredPayload)
            assertFalse(request.payloadText().contains("private.layout"))
            assertFalse(request.payloadText().contains("private_widget"))
            assertEquals("Widget", report.provenance?.evidence?.single()?.summary)
            assertSame(input, prepared.input)
            assertEquals("private.layout.Widget", input.sourceEvidence.single().className)
            assertEquals("private.layout:id/private_widget", input.sourceEvidence.single().resourceName)
        }
    }

    @Test
    fun `validated partial summaries allow performance only analysis with honest evidence`() = runBlocking {
        Fixture.create().use { fixture ->
            val requests = mutableListOf<AnalysisRequest>()
            val contexts = mutableListOf<AnalysisRequest>()
            val client = SourceAwareLayoutAiAnalysisClient(
                fixture.runtime,
                AiAnalysisGateway { request ->
                    requests += request
                    AnalysisResult(request.sessionId, "test-model", "ok", emptyList())
                },
                onPreparedContext = { contexts += it },
            )

            for (omitted in listOf(0, 25)) {
                val input = boundedSummaryInput().copy(omittedSourceEvidenceCount = omitted)
                val prepared = client.prepare(input)
                val evidence = prepared.manifest.evidence.single()

                assertTrue(prepared.manifest.canAnalyze)
                assertTrue(prepared.manifest.performanceDataOnly)
                assertFalse(prepared.manifest.requiresNarrowerScope)
                assertTrue(prepared.manifest.sources.isEmpty())
                assertEquals("layout:node", evidence.id)
                assertTrue(evidence.summary.startsWith("Partial/incomplete"))
                assertTrue(evidence.summary.contains("included nodes only"))
                assertEquals("Partial/incomplete layout summary (included nodes only): View", evidence.summary)
                assertFalse(evidence.summary.contains("android.view"))
                assertFalse(evidence.summary.contains("sample:id/widget"))
                assertEquals(evidence.summary, contexts.last().evidence.single().summary)

                client.analyze(prepared)

                assertEquals(evidence.summary, requests.last().evidence.single().summary)
                assertEquals("layout:node", requests.last().evidence.single().id)
                assertEquals(input.json, requests.last().evidence.single().structuredPayload)
                assertTrue(requests.last().sourceCandidates.isEmpty())
                assertEquals(requests.last().payloadText().encodeToByteArray().size, prepared.manifest.payloadBytes)
                assertFalse(requests.last().payloadText().contains("android.view"))
                assertFalse(requests.last().payloadText().contains("sample:id/widget"))
                assertEquals("android.view.View", input.sourceEvidence.single().className)
                assertEquals("sample:id/widget", input.sourceEvidence.single().resourceName)
            }
            assertEquals(2, requests.size)
        }
    }

    @Test
    fun `raw legacy malformed and mismatched partial summaries remain blocked`() = runBlocking {
        Fixture.create().use { fixture ->
            val client = SourceAwareLayoutAiAnalysisClient(
                fixture.runtime,
                AiAnalysisGateway { error("Invalid partial summaries must not reach the gateway") },
            )
            val valid = boundedSummaryInput()
            val oversizedUtf8 = boundedSummaryInput(coverage = buildJsonObject {
                put("schemaVersion", 1)
                put("complete", false)
                put("includedNodeCount", 1)
                put("note", "\u754c".repeat(16_384))
            }.toString())
            assertTrue(oversizedUtf8.json.length < 49_152)
            val invalidInputs = listOf(
                valid.copy(json = "{}"),
                boundedSummaryInput(coverage = null),
                boundedSummaryInput(coverage = "{}"),
                boundedSummaryInput(coverage = """{"schemaVersion":1,"complete":false,"includedNodeCount":2}"""),
                boundedSummaryInput(coverage = """{"schemaVersion":1,"complete":true,"includedNodeCount":1}"""),
                boundedSummaryInput(coverage = """{"schemaVersion":2,"complete":false,"includedNodeCount":1}"""),
                valid.copy(selectedNodeId = "missing"),
                valid.copy(treeTruncated = false),
                valid.copy(json = valid.json + " ".repeat(49_152)),
                oversizedUtf8,
            )

            for (input in invalidInputs) {
                val prepared = client.prepare(input)

                assertFalse(prepared.manifest.canAnalyze)
                assertTrue(prepared.manifest.requiresNarrowerScope)
                assertTrue(prepared.manifest.sources.isEmpty())
                assertThrows<IllegalStateException> { client.analyze(prepared) }
            }
        }
    }

    @Test
    fun `performance only requests retain actual UTF8 payload byte limits`() = runBlocking {
        Fixture.create().use { fixture ->
            val contexts = mutableListOf<AnalysisRequest>()
            val client = SourceAwareLayoutAiAnalysisClient(
                fixture.runtime,
                AiAnalysisGateway { error("Oversized payloads must not reach the gateway") },
                onPreparedContext = { contexts += it },
            )

            val ascii = buildJsonObject { put("padding", "a".repeat(AI_ANALYSIS_MAX_PAYLOAD_BYTES - 32)) }.toString()
            val multibyte = buildJsonObject { put("padding", "\u754c".repeat(AI_ANALYSIS_MAX_PAYLOAD_BYTES / 2)) }.toString()
            assertTrue(ascii.encodeToByteArray().size < AI_ANALYSIS_MAX_PAYLOAD_BYTES)
            assertTrue(multibyte.length < AI_ANALYSIS_MAX_PAYLOAD_BYTES)
            for (json in listOf(ascii, multibyte)) {
                val input = AiAnalysisInput(
                    json = json,
                    includeSourceSnippets = false,
                )
                val prepared = client.prepare(input)

                assertFalse(prepared.manifest.canAnalyze)
                assertTrue(prepared.manifest.requiresNarrowerScope)
                assertTrue(prepared.manifest.payloadBytes > AI_ANALYSIS_MAX_PAYLOAD_BYTES)
                assertEquals(contexts.last().payloadText().encodeToByteArray().size, prepared.manifest.payloadBytes)
                assertThrows<IllegalStateException> { client.analyze(prepared) }
            }
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

    @Test
    fun `hosted providers preserve saved models Agent and report format without preflight execution`() = runBlocking {
        hostedFixture().use { fixture ->
            val providers = listOf(
                "codex" to "Codex Responses",
                "claude" to "Claude",
                "openai" to "OpenAI Responses",
            )
            for ((providerId, providerLabel) in providers) {
                for (format in ArtifactFormat.entries) {
                    val settings = AiPlatformSettings(
                        providerId = providerId,
                        model = "$providerId-saved-model",
                        agentId = "aps.layout",
                        allowArtifacts = true,
                        allowScripts = true,
                        reportFormat = format,
                    )
                    var settingsLoads = 0
                    val contexts = mutableListOf<AnalysisRequest>()
                    val calls = mutableListOf<Pair<AnalysisRequest, AiPlatformSettings>>()
                    val client = SourceAwareLayoutAiAnalysisClient(
                        fixture.runtime,
                        analysisGateway = AiAnalysisGateway { error("Hosted analysis must not use a gateway") },
                        globalAnalysis = { request, captured ->
                            calls += request to captured
                            val session = requireNotNull(fixture.sessions.session(request.sessionId))
                            assertEquals(providerLabel, session.provider)
                            assertEquals(settings.model, session.model)
                            AnalysisResult(request.sessionId, captured.model, "ok", emptyList())
                        },
                        onPreparedContext = { contexts += it },
                        loadSettings = {
                            settingsLoads++
                            settings
                        },
                    )
                    assertFalse(client.requiresLocalConfirmation)
                    assertEquals(0, settingsLoads)

                    val prepared = client.prepare(
                        AiAnalysisInput("{}", selectedNodeId = "node", includeSourceSnippets = false),
                    )

                    assertTrue(prepared.manifest.canAnalyze)
                    assertEquals(settings.model, prepared.manifest.model)
                    assertEquals(1, settingsLoads)
                    assertTrue(calls.isEmpty())
                    assertNull(fixture.sessions.session(contexts.single().sessionId))

                    val report = client.analyze(prepared)

                    assertSame(contexts.single(), calls.single().first)
                    assertSame(settings, calls.single().second)
                    assertEquals(1, settingsLoads)
                    assertEquals(settings.model, report.model)
                    assertEquals(providerLabel, report.provenance?.provider)
                    assertEquals(providerLabel, fixture.sessions.session(calls.single().first.sessionId)?.provider)
                }
            }
        }
    }

    @Test
    fun `hosted prepared requests retain their own settings after later selections change`() = runBlocking {
        hostedFixture().use { fixture ->
            val codex = AiPlatformSettings(
                providerId = "codex",
                model = "saved-codex-model",
                agentId = "aps.layout",
                allowArtifacts = true,
                reportFormat = ArtifactFormat.MARKDOWN,
            )
            val claude = AiPlatformSettings(
                providerId = "claude",
                model = "saved-claude-model",
                agentId = "aps.review",
                allowScripts = true,
            )
            var saved = codex
            var settingsLoads = 0
            val calls = mutableListOf<AiPlatformSettings>()
            val client = SourceAwareLayoutAiAnalysisClient(
                fixture.runtime,
                globalAnalysis = { request, captured ->
                    calls += captured
                    AnalysisResult(request.sessionId, captured.model, "ok", emptyList())
                },
                loadSettings = {
                    settingsLoads++
                    saved
                },
            )
            val input = AiAnalysisInput("{}", includeSourceSnippets = false)
            val codexPrepared = client.prepare(input)
            saved = claude
            val claudePrepared = client.prepare(input)
            saved = AiPlatformSettings(providerId = "openai", model = "later-model")

            assertEquals(2, settingsLoads)
            assertTrue(calls.isEmpty())
            val claudeReport = client.analyze(claudePrepared)
            val codexReport = client.analyze(codexPrepared)

            assertSame(claude, calls[0])
            assertSame(codex, calls[1])
            assertEquals(2, settingsLoads)
            assertEquals(claude.model, claudePrepared.manifest.model)
            assertEquals(claude.model, claudeReport.model)
            assertEquals("Claude", claudeReport.provenance?.provider)
            assertEquals(codex.model, codexPrepared.manifest.model)
            assertEquals(codex.model, codexReport.model)
            assertEquals("Codex Responses", codexReport.provenance?.provider)
        }
    }

    @Test
    fun `hosted blank models freeze the selected provider fallback during prepare`() = runBlocking {
        val preferences = InMemoryPreferences()
        hostedFixture(preferences).use { fixture ->
            for ((providerId, modelKey, providerLabel) in listOf(
                Triple("codex", "model", "Codex Responses"),
                Triple("claude", "claude.model", "Claude"),
                Triple("openai", "custom.model", "OpenAI Responses"),
            )) {
                for (blank in listOf("", "  ")) {
                    val fallback = "$providerId-fallback-model"
                    preferences.put(modelKey, fallback)
                    val saved = AiPlatformSettings(
                        providerId = providerId,
                        model = blank,
                        agentId = "aps.layout",
                        allowArtifacts = true,
                        reportFormat = ArtifactFormat.MARKDOWN,
                    )
                    val calls = mutableListOf<AiPlatformSettings>()
                    var settingsLoads = 0
                    val client = SourceAwareLayoutAiAnalysisClient(
                        fixture.runtime,
                        globalAnalysis = { request, captured ->
                            calls += captured
                            assertEquals(fallback, fixture.sessions.session(request.sessionId)?.model)
                            AnalysisResult(request.sessionId, captured.model, "ok", emptyList())
                        },
                        loadSettings = {
                            settingsLoads++
                            saved
                        },
                    )

                    val prepared = client.prepare(AiAnalysisInput("{}", includeSourceSnippets = false))
                    preferences.put(modelKey, "$providerId-changed-model")

                    assertEquals(fallback, prepared.manifest.model)
                    assertTrue(calls.isEmpty())
                    val report = client.analyze(prepared)

                    assertEquals(saved.copy(model = fallback), calls.single())
                    assertEquals(1, settingsLoads)
                    assertEquals(fallback, report.model)
                    assertEquals(providerLabel, report.provenance?.provider)
                }
            }
        }
    }

    @Test
    fun `standalone confirmation and gateway do not load hosted settings`() = runBlocking {
        hostedFixture().use { fixture ->
            val requests = mutableListOf<AnalysisRequest>()
            val client = SourceAwareLayoutAiAnalysisClient(
                fixture.runtime,
                analysisGateway = AiAnalysisGateway { request ->
                    requests += request
                    AnalysisResult(request.sessionId, "standalone-model", "ok", emptyList())
                },
                loadSettings = { error("Standalone analysis must not load platform settings") },
            )

            assertTrue(client.requiresLocalConfirmation)
            val prepared = client.prepare(AiAnalysisInput("{}", includeSourceSnippets = false))
            assertEquals(fixture.runtime.aiModel(), prepared.manifest.model)
            assertTrue(requests.isEmpty())
            val report = client.analyze(prepared)

            assertEquals(1, requests.size)
            assertEquals("standalone-model", report.model)
            assertEquals("OpenAI Responses", report.provenance?.provider)
        }
    }

    @Test
    fun `hosted callback failure is redacted without gateway fallback`() = runBlocking {
        hostedFixture().use { fixture ->
            val failure = IllegalArgumentException("private-provider-details")
            val contexts = mutableListOf<AnalysisRequest>()
            val client = SourceAwareLayoutAiAnalysisClient(
                fixture.runtime,
                analysisGateway = AiAnalysisGateway { error("Hosted analysis must not fall back to a gateway") },
                globalAnalysis = { _, _ -> throw failure },
                onPreparedContext = { contexts += it },
                loadSettings = { AiPlatformSettings(providerId = "claude", model = "saved-model") },
            )
            val prepared = client.prepare(AiAnalysisInput("{}", includeSourceSnippets = false))

            assertSame(failure, assertThrows<IllegalArgumentException> { client.analyze(prepared) })

            val session = requireNotNull(fixture.sessions.session(contexts.single().sessionId))
            assertEquals(AnalysisSessionStatus.FAILED, session.status)
            assertEquals("IllegalArgumentException", session.errorMessage)
            assertEquals("Claude", session.provider)
            assertTrue(fixture.sessions.findings(session.id).isEmpty())
        }
    }

    @Test
    fun `cancelling hosted analysis retains provider provenance without a result`() = runBlocking {
        hostedFixture().use { fixture ->
            val started = CompletableDeferred<AnalysisRequest>()
            val client = SourceAwareLayoutAiAnalysisClient(
                fixture.runtime,
                globalAnalysis = { request, _ ->
                    started.complete(request)
                    awaitCancellation()
                },
                loadSettings = { AiPlatformSettings(providerId = "codex", model = "saved-model") },
            )
            val prepared = client.prepare(AiAnalysisInput("{}", includeSourceSnippets = false))
            val analysis = async { client.analyze(prepared) }
            val request = started.await()

            analysis.cancelAndJoin()

            val session = requireNotNull(fixture.sessions.session(request.sessionId))
            assertEquals(AnalysisSessionStatus.CANCELLED, session.status)
            assertEquals("Codex Responses", session.provider)
            assertTrue(fixture.sessions.findings(request.sessionId).isEmpty())
        }
    }

    private fun boundedSummaryInput(
        coverage: String? = """{"schemaVersion":1,"complete":false,"includedNodeCount":1,"omittedNodeCount":25}""",
    ): AiAnalysisInput = AiAnalysisInput(
        json = buildJsonObject {
            put("tree", Json.parseToJsonElement("""{"id":"node","className":"View","children":[],"truncatedChildren":25}"""))
            coverage?.let { put("coverage", Json.parseToJsonElement(it)) }
        }.toString(),
        sourceEvidence = listOf(LayoutSourceEvidence("node", "android.view.View", "sample:id/widget")),
        selectedNodeId = "node",
        includeSourceSnippets = false,
        omittedSourceEvidenceCount = 25,
        treeTruncated = true,
    )

    private fun hostedFixture(preferences: Preferences = InMemoryPreferences()): Fixture = Fixture.create(
        preferences = preferences,
        credentials = object : CredentialStore by InMemoryCredentialStore() {
            override fun read(key: String): String? = error("Hosted analysis must not access credentials")
        },
    )

    private class InMemoryPreferences(
        parent: AbstractPreferences? = null,
        name: String = "",
    ) : AbstractPreferences(parent, name) {
        private val values = mutableMapOf<String, String>()

        override fun putSpi(key: String, value: String) {
            values[key] = value
        }

        override fun getSpi(key: String): String? = values[key]

        override fun removeSpi(key: String) {
            values.remove(key)
        }

        override fun removeNodeSpi() {
            values.clear()
        }

        override fun keysSpi(): Array<String> = values.keys.toTypedArray()
        override fun childrenNamesSpi(): Array<String> = emptyArray()
        override fun childSpi(name: String): AbstractPreferences = InMemoryPreferences(this, name)
        override fun syncSpi() = Unit
        override fun flushSpi() = Unit
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
            fun create(
                preferences: Preferences = Preferences.userRoot().node("aps-test-${UUID.randomUUID()}"),
                credentials: CredentialStore = InMemoryCredentialStore(),
            ): Fixture {
                val root = Files.createTempDirectory("layout-ai-cancel")
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
                    credentials,
                    preferences,
                )
                return Fixture(runtime, sessions, preferences, root)
            }
        }
    }
}
