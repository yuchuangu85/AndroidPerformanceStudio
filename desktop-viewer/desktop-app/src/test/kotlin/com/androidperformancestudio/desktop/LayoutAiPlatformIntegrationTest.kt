package com.androidperformancestudio.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.ai.AiAnalysisGateway
import com.androidperformancestudio.ai.AiHttpTransport
import com.androidperformancestudio.ai.AnalysisFinding
import com.androidperformancestudio.ai.AnalysisFindingId
import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisResult
import com.androidperformancestudio.ai.AnalysisScopeKind
import com.androidperformancestudio.ai.AnalysisSessionStatus
import com.androidperformancestudio.ai.AnalysisSeverity
import com.androidperformancestudio.ai.InMemoryCredentialStore
import com.androidperformancestudio.ai.ProfilerKind
import com.androidperformancestudio.ai.SqliteAnalysisSessionRepository
import com.androidperformancestudio.ai.payloadText
import com.androidperformancestudio.aiplatform.app.AiPlatformRuntime
import com.androidperformancestudio.aiplatform.app.AiPlatformSettings
import com.androidperformancestudio.aiplatform.app.AiPlatformSettingsStore
import com.androidperformancestudio.aiplatform.app.BusinessContext
import com.androidperformancestudio.aiplatform.model.ArtifactFormat
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.RunStatus
import com.androidperformancestudio.aiplatform.presentation.AiConversationWorkspace
import com.androidperformancestudio.aiplatform.storage.ExecutionKind
import com.androidperformancestudio.aiplatform.storage.ExecutionPhase
import com.androidperformancestudio.analysis.AiFinding
import com.androidperformancestudio.analysis.Severity
import com.androidperformancestudio.source.ContentAddressedSourceCache
import com.androidperformancestudio.source.DefaultSourceWorkspaceService
import com.androidperformancestudio.source.IndexedSourceResolver
import com.androidperformancestudio.source.LocalSourceProvider
import com.androidperformancestudio.source.SourceProviderRegistry
import com.androidperformancestudio.source.SqliteSourceWorkspaceRepository
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class LayoutAiPlatformIntegrationTest {
    @Test
    fun `layout analysis freezes saved settings and returns a report only after global UI approval`() = runAnalysis(false)

    @Test
    fun `bounded partial layout summary reaches the selected provider only after global UI approval`() = runAnalysis(true)

    private fun runAnalysis(partial: Boolean) {
        val layoutJson = if (partial) partialLayoutJson() else LAYOUT_JSON
        val root = Files.createTempDirectory("aps-layout-ai-integration-").toRealPath()
        val sourcePreferences = Preferences.userRoot().node("aps-layout-ai-source-${UUID.randomUUID()}")
        val platformPreferences = Preferences.userRoot().node("aps-layout-ai-platform-${UUID.randomUUID()}")
        val catalogCalls = AtomicInteger()
        val openAiCalls = AtomicInteger()
        val globalCalls = AtomicInteger()
        val settingsReads = AtomicInteger()
        val models = ConcurrentLinkedQueue<String>()
        val requests = ConcurrentLinkedQueue<AnalysisRequest>()
        try {
            sourceRuntime(root, sourcePreferences, catalogCalls).use { source ->
                val settingsStore = AiPlatformSettingsStore(platformPreferences)
                val saved = AiPlatformSettings(
                    providerId = "codex",
                    model = MODEL,
                    agentId = "aps.report",
                    allowArtifacts = true,
                    reportFormat = ArtifactFormat.MARKDOWN,
                )
                settingsStore.save(saved)
                AiPlatformRuntime.open(
                    root = root.resolve("platform"),
                    openAiAvailable = { true },
                    openAiGateway = {
                        openAiCalls.incrementAndGet()
                        error("Layout integration must never use the OpenAI gateway")
                    },
                    codexAvailable = { true },
                    codexGateway = { model ->
                        models.add(model)
                        AiAnalysisGateway { request ->
                            requests.add(request)
                            AnalysisResult(request.sessionId, model, SUMMARY, listOf(finding()))
                        }
                    },
                ).use { platform ->
                    var openedContext by mutableStateOf<BusinessContext?>(null)
                    val coordinator = DesktopAiCoordinator(
                        loadSettings = { error("Layout handoff must use its prepare-time settings") },
                    ).apply {
                        runtime = platform
                        sourceModel = { error("A saved model must not use the source fallback") }
                        onOpen = { openedContext = it }
                    }
                    var preparedRequest: AnalysisRequest? = null
                    val client = SourceAwareLayoutAiAnalysisClient(
                        runtime = source,
                        analysisGateway = AiAnalysisGateway { error("Layout analysis must use global approval") },
                        globalAnalysis = { request, settings ->
                            globalCalls.incrementAndGet()
                            assertEquals(saved, settings)
                            coordinator.analyze(request, settings)
                        },
                        onPreparedContext = { preparedRequest = it },
                        loadSettings = {
                            settingsReads.incrementAndGet()
                            settingsStore.load()
                        },
                    )
                    val input = AiAnalysisInput(
                        json = layoutJson,
                        selectedNodeId = "selected",
                        sourceEvidence = listOf(LayoutSourceEvidence("selected", "android.view.View", "com.example.publicfixture:id/selected")),
                        includeSourceSnippets = false,
                        treeTruncated = partial,
                    )
                    val prepared = runBlocking { client.prepare(input) }
                    val frozenRequest = checkNotNull(preparedRequest)

                    assertFalse(client.requiresLocalConfirmation)
                    assertTrue(prepared.manifest.canAnalyze)
                    assertTrue(prepared.manifest.performanceDataOnly)
                    assertFalse(prepared.manifest.requiresSourceUploadAuthorization)
                    assertEquals(MODEL, prepared.manifest.model)
                    assertEquals(listOf(EVIDENCE_ID), prepared.manifest.evidence.map { it.id })
                    assertTrue(prepared.manifest.sources.isEmpty())
                    assertEquals(frozenRequest.payloadText().encodeToByteArray().size, prepared.manifest.payloadBytes)
                    assertTrue(prepared.manifest.payloadBytes in 1..AI_ANALYSIS_MAX_PAYLOAD_BYTES)
                    assertPerformanceOnly(frozenRequest, layoutJson)
                    assertEquals("android.view.View", input.sourceEvidence.single().className)
                    assertEquals("com.example.publicfixture:id/selected", input.sourceEvidence.single().resourceName)
                    assertEquals(1, settingsReads.get())
                    assertEquals(0, globalCalls.get())
                    assertNull(platform.service.state.value.status)
                    assertNull(openedContext)
                    assertNull(coordinator.contexts.current("layout-inspector"))
                    assertTrue(runBlocking { platform.service.history() }.isEmpty())
                    assertNull(source.analysisSessions.session(frozenRequest.sessionId))
                    assertTrue(models.isEmpty())
                    assertTrue(requests.isEmpty())
                    assertEquals(0, openAiCalls.get())
                    assertEquals(0, catalogCalls.get())

                    // Change storage before handoff, then again during approval: neither may thaw the request.
                    val changed = saved.copy(
                        providerId = "openai",
                        model = "other-provider-before-handoff",
                        agentId = "aps.main",
                        allowArtifacts = false,
                        reportFormat = ArtifactFormat.HTML,
                    )
                    settingsStore.save(changed)
                    val owner = SupervisorJob()
                    val analysis = CoroutineScope(owner + Dispatchers.IO).async(start = CoroutineStart.LAZY) {
                        client.analyze(prepared)
                    }
                    try {
                        runDesktopComposeUiTest(width = 1280, height = 900) {
                            setContent {
                                ViewerTheme(false) {
                                    openedContext?.let { context ->
                                        AiConversationWorkspace(
                                            runtime = platform,
                                            context = context,
                                            language = UiLanguage.ENGLISH,
                                            defaultModel = "unused-default-model",
                                            settingsStoreOverride = settingsStore,
                                            loadModels = { _, _ ->
                                                catalogCalls.incrementAndGet()
                                                error("Approval must not request the model catalog")
                                            },
                                        )
                                    }
                                }
                            }
                            runOnIdle { analysis.start() }
                            waitUntil(timeoutMillis = 10_000) {
                                openedContext != null && platform.service.state.value.status == RunStatus.AWAITING_APPROVAL
                            }
                            waitUntil(timeoutMillis = 10_000) {
                                onAllNodesWithTag("ai-approval-row").fetchSemanticsNodes().isNotEmpty()
                            }
                            val context = checkNotNull(openedContext)
                            val pending = checkNotNull(platform.service.state.value.prepared)
                            assertEquals("layout-inspector", context.key)
                            assertEquals("Layout node selected", context.title)
                            assertEquals(frozenRequest, context.request)
                            assertEquals(context, coordinator.contexts.current("layout-inspector"))
                            assertEquals(ProfilerKind.LAYOUT_INSPECTOR, context.request.originProfiler)
                            assertEquals(AnalysisScopeKind.CURRENT_SELECTION, context.request.scope.kind)
                            assertPerformanceOnly(context.request, layoutJson)
                            assertEquals("codex", pending.providerId)
                            assertEquals(MODEL, pending.model)
                            assertEquals(ArtifactFormat.MARKDOWN, pending.reportFormat)
                            assertEquals(1, pending.evidenceCount)
                            assertEquals(0, pending.candidateCount)
                            assertEquals(1, pending.plannedProviderCallCount)
                            assertEquals(0, pending.plannedScriptCount)
                            assertEquals(frozenRequest.sessionId.value, pending.approval.sessionId)
                            assertEquals(
                                setOf(Permission.READ_EVIDENCE, Permission.PROVIDER_NETWORK, Permission.WRITE_ARTIFACT),
                                pending.approval.permissions,
                            )
                            val preview = checkNotNull(pending.analysisPreview)
                            assertEquals(frozenRequest.sessionId, preview.sessionId)
                            assertEquals(listOf(EVIDENCE_ID), preview.evidence.map { it.id })
                            assertTrue(preview.sourceCandidates.isEmpty())
                            onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-report-format"))
                            onNodeWithText("codex / $MODEL", substring = true).assertExists()
                            onNodeWithTag("ai-report-format").assertTextContains("Markdown (.md)", substring = true)
                            onNodeWithTag("ai-preflight-evidence-$EVIDENCE_ID")
                                .assertTextContains("$EVIDENCE_ID", substring = true)
                                .assertTextContains("View", substring = true)
                            if (partial) {
                                onNodeWithTag("ai-preflight-evidence-$EVIDENCE_ID").assertTextContains("Partial/incomplete", substring = true)
                            }

                            runOnIdle { settingsStore.save(changed.copy(model = "other-provider-during-approval")) }
                            waitForIdle()
                            assertEquals(ArtifactFormat.HTML, settingsStore.load().reportFormat)
                            assertEquals("other-provider-during-approval", settingsStore.load().model)
                            assertSame(pending, platform.service.state.value.prepared)
                            assertEquals(RunStatus.AWAITING_APPROVAL, platform.service.state.value.status)
                            onNodeWithText("codex / $MODEL", substring = true).assertExists()
                            onNodeWithTag("ai-report-format").assertTextContains("Markdown (.md)", substring = true)
                            assertEquals(1, settingsReads.get())
                            assertEquals(1, globalCalls.get())
                            assertFalse(analysis.isCompleted)
                            assertTrue(models.isEmpty())
                            assertTrue(requests.isEmpty())
                            assertEquals(0, openAiCalls.get())
                            assertEquals(0, catalogCalls.get())
                            assertNull(platform.service.state.value.publishedArtifactHash)

                            onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-confirm"))
                            onNodeWithTag("ai-confirm").assertIsEnabled().performClick()
                            waitUntil(timeoutMillis = 10_000) { platform.service.state.value.status?.terminal == true }
                            val terminal = platform.service.state.value
                            assertEquals(RunStatus.SUCCEEDED, terminal.status, terminal.errorCode)
                            val report = runBlocking { withTimeout(10_000) { analysis.await() } }
                            assertEquals(listOf(MODEL), models.toList())
                            assertEquals(listOf(frozenRequest), requests.toList())
                            assertPerformanceOnly(requests.single(), layoutJson)
                            assertEquals("android.view.View", input.sourceEvidence.single().className)
                            assertEquals("com.example.publicfixture:id/selected", input.sourceEvidence.single().resourceName)
                            assertEquals(1, settingsReads.get())
                            assertEquals(1, globalCalls.get())
                            assertEquals(0, openAiCalls.get())
                            assertEquals(0, catalogCalls.get())
                            assertEquals(MODEL, report.model)
                            assertEquals(SUMMARY, report.summary)
                            val expectedFinding = finding()
                            assertEquals(
                                AiFinding(
                                    ruleId = expectedFinding.id.value,
                                    severity = Severity.WARNING,
                                    nodeId = "selected",
                                    title = expectedFinding.title,
                                    message = expectedFinding.explanation,
                                    recommendation = expectedFinding.recommendation,
                                    confidence = expectedFinding.analysisConfidence,
                                    performanceEvidenceIds = listOf(EVIDENCE_ID),
                                    sourceCandidateIds = emptyList(),
                                ),
                                report.findings.single(),
                            )
                            assertEquals(listOf(expectedFinding), checkNotNull(terminal.result).findings)
                            val provenance = checkNotNull(report.provenance)
                            assertEquals(frozenRequest.sessionId.value, provenance.sessionId)
                            assertEquals("Codex Responses", provenance.provider)
                            assertEquals(frozenRequest.scope.description, provenance.scope)
                            assertEquals(frozenRequest.promptVersion, provenance.promptVersion)
                            assertEquals(frozenRequest.payloadPolicyVersion, provenance.payloadPolicyVersion)
                            assertEquals(EVIDENCE_ID, provenance.evidence.single().id)
                            assertEquals("layout-snapshot", provenance.evidence.single().kind)
                            assertTrue(provenance.evidence.single().payloadHash.matches(Regex("[a-f0-9]{64}")))
                            assertTrue(provenance.sourceCandidates.isEmpty())
                            assertTrue(provenance.sourceSnapshotIds.isEmpty())
                            val session = checkNotNull(source.analysisSessions.session(frozenRequest.sessionId))
                            assertEquals(AnalysisSessionStatus.SUCCEEDED, session.status)
                            assertEquals("Codex Responses", session.provider)
                            assertEquals(MODEL, session.model)
                            assertEquals(listOf(expectedFinding), source.analysisSessions.findings(frozenRequest.sessionId))
                            assertTrue(
                                terminal.timeline.any {
                                    it.kind == ExecutionKind.RUN && it.phase == ExecutionPhase.STARTED && it.subjectId == "aps.report"
                                },
                                "The prepare-time Report Agent must run instead of the newly saved Main Agent",
                            )
                            val artifactHash = checkNotNull(terminal.publishedArtifactHash)
                            assertEquals(ArtifactFormat.MARKDOWN, platform.artifacts.metadata(artifactHash).format)
                            val artifact = platform.artifacts.read(artifactHash).decodeToString()
                            assertTrue(artifact.startsWith("# Performance analysis"))
                            assertTrue(artifact.contains(SUMMARY))
                            assertTrue(artifact.contains(expectedFinding.title))
                        }
                    } finally {
                        runBlocking {
                            analysis.cancelAndJoin()
                            owner.cancelAndJoin()
                        }
                    }
                }
            }
        } finally {
            try {
                sourcePreferences.removeNode()
            } finally {
                try {
                    platformPreferences.removeNode()
                } finally {
                    root.toFile().deleteRecursively()
                }
            }
        }
    }

    private fun sourceRuntime(root: Path, preferences: Preferences, catalogCalls: AtomicInteger): SourceWorkspaceRuntime {
        val repository = SqliteSourceWorkspaceRepository(root.resolve("source.db"))
        return SourceWorkspaceRuntime(
            repository = repository,
            service = DefaultSourceWorkspaceService(
                SourceProviderRegistry(listOf(LocalSourceProvider())),
                repository,
                ContentAddressedSourceCache(root.resolve("source-cache")),
            ),
            resolver = IndexedSourceResolver(repository),
            analysisSessions = SqliteAnalysisSessionRepository(root.resolve("analysis.db")),
            credentials = InMemoryCredentialStore(),
            preferences = preferences,
            modelCatalogTransport = AiHttpTransport {
                catalogCalls.incrementAndGet()
                error("The synthetic integration fixture must not make catalog HTTP requests")
            },
        )
    }

    private fun assertPerformanceOnly(request: AnalysisRequest, expectedJson: String) {
        assertTrue(request.sourceCandidates.isEmpty())
        assertNull(request.userQuestion)
        assertTrue(request.conversationContext.isEmpty())
        val evidence = request.evidence.single()
        assertEquals(EVIDENCE_ID, evidence.id)
        assertEquals("layout-snapshot", evidence.kind)
        if (expectedJson == LAYOUT_JSON) {
            assertEquals("View", evidence.summary)
        } else {
            assertEquals("Partial/incomplete layout summary (included nodes only): View", evidence.summary)
        }
        assertEquals(expectedJson, evidence.structuredPayload)
        val payloadText = request.payloadText()
        assertFalse(payloadText.contains("android.view"))
        assertFalse(payloadText.contains("com.example.publicfixture"))
        val payload = Json.parseToJsonElement(payloadText).jsonObject
        assertTrue(payload.getValue("sourceCandidates").jsonArray.isEmpty())
        val layout = payload.getValue("performanceEvidence").jsonArray.single().jsonObject.getValue("payload").jsonObject
        assertEquals(
            Json.parseToJsonElement(expectedJson),
            layout,
        )
        assertEquals(setOf("display", "metrics", "layoutMetrics", "ruleFindings", "tree", "coverage"), layout.keys)
        assertEquals("View", layout.getValue("tree").jsonObject.getValue("className").jsonPrimitive.content)
        assertEquals(JsonPrimitive(12), layout.getValue("tree").jsonObject.getValue("textLength"))
        val rule = layout.getValue("ruleFindings").jsonArray.single().jsonObject
        assertEquals(setOf("ruleId", "severity", "nodeId", "parameters"), rule.keys)
        val parameters = rule.getValue("parameters").jsonObject
        assertEquals(JsonPrimitive(48), parameters["threshold"])
        assertTrue(parameters.keys.all { it in setOf("count", "threshold", "depth", "ratioPercent") })
        assertTrue(parameters.values.all { it is JsonPrimitive && !it.isString && it.doubleOrNull != null })
        assertTrue(
            payload.keysRecursively().intersect(
                setOf(
                    "packageName", "capturedAtEpochMillis", "screenshotAvailable", "resourceName", "contentDescriptionLength",
                    "message", "arguments", "messageArguments", "text", "contentDescription", "source", "sourceSnippet", "sourceCode",
                    "screenshot", "screenshotBytes", "image_url", "imageBytes",
                ),
            ).isEmpty(),
            "Only bounded layout metrics and numeric rule parameters, never app identifiers, raw messages, text, source or images, may be sent",
        )
    }

    private fun partialLayoutJson(): String {
        val document = Json.parseToJsonElement(LAYOUT_JSON).jsonObject.toMutableMap()
        document["tree"] = JsonObject(document.getValue("tree").jsonObject + ("truncatedChildren" to JsonPrimitive(25)))
        document["layoutMetrics"] = JsonObject(mapOf("nodeCount" to JsonPrimitive(26), "maxDepth" to JsonPrimitive(2), "widestLevel" to JsonPrimitive(25)))
        document["coverage"] = JsonObject(mapOf(
            "schemaVersion" to JsonPrimitive(1),
            "complete" to JsonPrimitive(false),
            "includedNodeCount" to JsonPrimitive(1),
        ))
        return JsonObject(document).toString()
    }

    private fun JsonElement.keysRecursively(): Set<String> = when (this) {
        is JsonObject -> keys + values.flatMap { it.keysRecursively() }
        is JsonArray -> flatMap { it.keysRecursively() }.toSet()
        else -> emptySet()
    }

    private fun finding() = AnalysisFinding(
        id = AnalysisFindingId("layout-target-size"),
        severity = AnalysisSeverity.WARNING,
        title = "Selected view has a small target",
        explanation = "The selected public fixture is only 24 pixels high.",
        recommendation = "Increase the selected view's target height.",
        analysisConfidence = 0.9f,
        performanceEvidenceIds = listOf(EVIDENCE_ID),
        sourceCandidateIds = emptyList(),
    )

    private companion object {
        const val MODEL = "codex-layout-test"
        const val EVIDENCE_ID = "layout:selected"
        const val SUMMARY = "The selected public fixture has a small target."
        val LAYOUT_JSON = """
            {
              "display": {"widthPx": 240, "heightPx": 480, "density": 1.0},
              "metrics": {"nodeCount": 1, "maxDepth": 1, "widestLevel": 1},
              "layoutMetrics": {"nodeCount": 1, "maxDepth": 1, "widestLevel": 1},
              "ruleFindings": [
                {"ruleId": "layout-target-size", "severity": "WARNING", "nodeId": "selected", "parameters": {"threshold": 48}}
              ],
              "tree": {
                "id": "selected",
                "className": "View",
                "bounds": {"left": 0, "top": 0, "right": 120, "bottom": 24},
                "visible": true,
                "alpha": 1.0,
                "textLength": 12,
                "children": [],
                "truncatedChildren": 0
              },
              "coverage": {"schemaVersion": 1, "complete": true, "includedNodeCount": 1}
            }
        """.trimIndent()
    }
}
