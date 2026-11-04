package com.androidperformancestudio.aiplatform.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.ai.AiAnalysisGateway
import com.androidperformancestudio.ai.AiSourceCandidate
import com.androidperformancestudio.ai.AnalysisFinding
import com.androidperformancestudio.ai.AnalysisFindingId
import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisResult
import com.androidperformancestudio.ai.AnalysisSeverity
import com.androidperformancestudio.ai.PerformanceEvidence
import com.androidperformancestudio.ai.ReportLanguage
import com.androidperformancestudio.aiplatform.app.AiPlatformRuntime
import com.androidperformancestudio.aiplatform.app.AiPlatformSettings
import com.androidperformancestudio.aiplatform.app.AiPlatformSettingsStore
import com.androidperformancestudio.aiplatform.app.BusinessContext
import com.androidperformancestudio.aiplatform.app.createBusinessContext
import com.androidperformancestudio.aiplatform.model.ArtifactFormat
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.RunStatus
import com.androidperformancestudio.aiplatform.storage.ExecutionKind
import com.androidperformancestudio.aiplatform.storage.ExecutionPhase
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AiConversationWorkspaceTest {
    @Test
    fun `external page analysis keeps its terminal result and conversation after the initial context changes`() {
        for (fail in listOf(false, true)) {
            WorkspaceFixture(beforeResult = { if (fail) error("fixture failure") }).use { fixture ->
                runDesktopComposeUiTest(width = 1280, height = 720) {
                    val memory = ChatWindowMemory(null)
                    var active by mutableStateOf<BusinessContext?>(null)
                    setContent {
                        ViewerTheme(false) {
                            ConversationWorkspace(
                                fixture.runtime,
                                active,
                                UiLanguage.ENGLISH,
                                "fake-model",
                                {},
                                {},
                                fixture.settings,
                                memory,
                            )
                        }
                    }
                    val canonical = context("page-entry")
                    val prepared =
                        runBlocking {
                            fixture.runtime.service.prepare(
                                "external-entry",
                                canonical.request,
                                "aps.report",
                                "openai",
                                "entry-model",
                                permissions,
                            )
                        }
                    runOnIdle { active = canonical }
                    waitForIdle()
                    onNodeWithTag("ai-bound-context").assertTextContains("Report page-entry")
                    onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-confirm"))
                    onNodeWithTag("ai-confirm").performClick()
                    val expected = if (fail) RunStatus.PERMANENT_FAILURE else RunStatus.SUCCEEDED
                    waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == expected }
                    waitForIdle()
                    assertEquals(expected, fixture.runtime.service.state.value.status)
                    assertEquals("external-entry", fixture.runtime.service.state.value.conversationId)
                    assertEquals("external-entry", memory.conversationId)
                    assertEquals(canonical.revision, memory.navigationRevision.value)
                    assertEquals(1, fixture.calls.get())
                    if (!fail) onNodeWithTag("ai-summary-${prepared.approval.runId}").assertExists()
                    onNodeWithTag("ai-question").performTextReplacement("Continue the same analysis")
                    onNodeWithTag("ai-send").assertIsEnabled()
                }
            }
        }
    }

    @Test
    fun `confirmed deletion of the current conversation removes history and leaves a fresh usable conversation`() =
        WorkspaceFixture().use { fixture ->
            runDesktopComposeUiTest(width = 1280, height = 720) {
                setContent {
                    ViewerTheme(false) {
                        AiConversationWorkspace(
                            fixture.runtime,
                            null,
                            UiLanguage.ENGLISH,
                            "fake-model",
                            settingsStoreOverride = fixture.settings,
                        )
                    }
                }
                onNodeWithTag("ai-question").performTextReplacement("Delete only this conversation")
                onNodeWithTag("ai-send").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                val id = checkNotNull(fixture.runtime.service.state.value.conversationId)
                onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-confirm"))
                onNodeWithTag("ai-confirm").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.SUCCEEDED }
                onNodeWithTag("ai-session-menu-$id").performClick()
                onNodeWithTag("ai-session-delete").performClick()
                assertTrue(
                    runBlocking {
                        fixture.runtime.service
                            .history()
                            .any { it.id == id }
                    },
                )
                onNodeWithTag("ai-delete-cancel").performClick()
                onNodeWithTag("ai-session-$id").assertExists()
                onNodeWithTag("ai-session-menu-$id").performClick()
                onNodeWithTag("ai-session-delete").performClick()
                onNodeWithTag("ai-delete-confirm").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == null }
                onNodeWithTag("ai-session-$id").assertDoesNotExist()
                assertTrue(
                    runBlocking {
                        fixture.runtime.service
                            .history()
                            .none { it.id == id }
                    },
                )
                assertTrue(
                    runBlocking {
                        fixture.runtime.service
                            .transcript(id)
                            .isEmpty()
                    },
                )
                onNodeWithTag("ai-question").performTextReplacement("A new question")
                onNodeWithTag("ai-send").assertIsEnabled()
                assertEquals(1, fixture.calls.get())
            }
        }

    @Test
    fun `deleting another conversation preserves the selected conversation and its unsent draft`() =
        WorkspaceFixture().use { fixture ->
            runDesktopComposeUiTest(width = 1280, height = 720) {
                setContent {
                    ViewerTheme(false) {
                        AiConversationWorkspace(
                            fixture.runtime,
                            null,
                            UiLanguage.ENGLISH,
                            "fake-model",
                            settingsStoreOverride = fixture.settings,
                        )
                    }
                }
                val ids = mutableListOf<String>()
                for (question in listOf("Conversation A", "Conversation B")) {
                    onNodeWithTag("ai-question").performTextReplacement(question)
                    onNodeWithTag("ai-send").performClick()
                    waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                    ids += checkNotNull(fixture.runtime.service.state.value.conversationId)
                    onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-confirm"))
                    onNodeWithTag("ai-confirm").performClick()
                    waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.SUCCEEDED }
                    if (ids.size == 1) {
                        onNodeWithTag("ai-new-chat").performClick()
                        waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == null }
                    }
                }
                onNodeWithTag("ai-question").performTextReplacement("Preserve B draft")
                onNodeWithTag("ai-session-menu-${ids.first()}").performClick()
                onNodeWithTag("ai-session-delete").performClick()
                onNodeWithTag("ai-delete-confirm").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("ai-session-${ids.first()}").fetchSemanticsNodes().isEmpty()
                }
                assertEquals(ids.last(), fixture.runtime.service.state.value.conversationId)
                assertEquals(RunStatus.SUCCEEDED, fixture.runtime.service.state.value.status)
                onNodeWithTag("ai-question").assertTextContains("Preserve B draft")
                onNodeWithTag("ai-send").assertIsEnabled()
                assertEquals(2, fixture.calls.get())
            }
        }

    @Test
    fun `report analysis without a typed question can retry after a provider failure`() =
        WorkspaceFixture(beforeResult = { error("fixture provider failure") }).use { fixture ->
            val canonical = context("report-only-retry")
            val original =
                runBlocking {
                    fixture.runtime.service.prepare("report-only", canonical.request, "aps.report", "openai", "first-model", permissions)
                }
            runDesktopComposeUiTest(width = 1280, height = 720) {
                setContent {
                    ViewerTheme(false) {
                        AiConversationWorkspace(
                            fixture.runtime,
                            canonical,
                            UiLanguage.ENGLISH,
                            "fake-model",
                            settingsStoreOverride = fixture.settings,
                        )
                    }
                }
                onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-confirm"))
                onNodeWithTag("ai-confirm").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.PERMANENT_FAILURE }
                val tag = "ai-retry-${original.approval.runId}"
                onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag(tag))
                onNodeWithTag(tag).performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                val retry = checkNotNull(fixture.runtime.service.state.value.prepared)
                assertEquals(null, retry.analysisPreview?.userQuestion)
                assertEquals(canonical.request.sessionId, retry.analysisPreview?.sessionId)
                assertEquals(original.conversationId, retry.conversationId)
                assertTrue(original.approval.runId != retry.approval.runId)
                assertEquals(1, fixture.calls.get())
            }
        }

    @Test
    fun `retrying an older failed turn uses its own question and latest settings with fresh approval`() =
        WorkspaceFixture(beforeResult = { error("fixture provider failure") }).use { fixture ->
            runDesktopComposeUiTest(width = 1280, height = 720) {
                setContent {
                    ViewerTheme(false) {
                        AiConversationWorkspace(
                            fixture.runtime,
                            context("retry"),
                            UiLanguage.ENGLISH,
                            "fake-model",
                            settingsStoreOverride = fixture.settings,
                        )
                    }
                }
                val failedRuns = mutableListOf<String>()
                for (question in listOf("First failed question", "Second failed question")) {
                    onNodeWithTag("ai-question").performTextReplacement(question)
                    onNodeWithTag("ai-send").performClick()
                    waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                    failedRuns += checkNotNull(fixture.runtime.service.state.value.prepared).approval.runId
                    onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-confirm"))
                    onNodeWithTag("ai-confirm").performClick()
                    waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.PERMANENT_FAILURE }
                }
                onNodeWithTag("ai-question").performTextReplacement("Keep this unsent draft")
                runOnIdle {
                    fixture.settings.update { it.copy(model = "retry-model", reportLanguage = ReportLanguage.ENGLISH) }
                }
                val retryTag = "ai-retry-${failedRuns.first()}"
                onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag(retryTag))
                onNodeWithTag(retryTag).performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                val retried = checkNotNull(fixture.runtime.service.state.value.prepared)
                assertEquals("First failed question", retried.analysisPreview?.userQuestion)
                assertEquals("retry-model", retried.model)
                assertEquals(ReportLanguage.ENGLISH, retried.analysisPreview?.outputLanguage)
                assertTrue(retried.approval.runId !in failedRuns)
                assertEquals(2, fixture.calls.get())
                onNodeWithTag("ai-question").assertTextContains("Keep this unsent draft")
                onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag(retryTag))
                onNodeWithTag(retryTag).assertIsNotEnabled()
            }
        }

    @Test
    fun `preflight failure can retry the original question after correcting settings without a provider call`() =
        WorkspaceFixture().use { fixture ->
            fixture.settings.update { it.copy(agentId = "aps.missing") }
            runDesktopComposeUiTest(width = 1280, height = 720) {
                setContent {
                    ViewerTheme(false) {
                        AiConversationWorkspace(
                            fixture.runtime,
                            context("preflight-retry"),
                            UiLanguage.ENGLISH,
                            "fake-model",
                            settingsStoreOverride = fixture.settings,
                        )
                    }
                }
                onNodeWithTag("ai-question").performTextReplacement("Retry this original question")
                onNodeWithTag("ai-send").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("ai-chat-error").fetchSemanticsNodes().isNotEmpty()
                }
                runOnIdle { fixture.settings.update { it.copy(agentId = "aps.report", model = "corrected-model") } }
                onNodeWithTag("ai-question").performTextReplacement("A different unsent draft")
                onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-retry"))
                onNodeWithTag("ai-retry").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                val retried = checkNotNull(fixture.runtime.service.state.value.prepared)
                assertEquals("Retry this original question", retried.analysisPreview?.userQuestion)
                assertEquals("corrected-model", retried.model)
                assertEquals(0, fixture.calls.get())
                onNodeWithTag("ai-question").assertTextContains("A different unsent draft")
            }
        }

    @Test
    fun `chat model menu loads saved Provider models and selects without navigating or sending`() =
        WorkspaceFixture().use { fixture ->
            runDesktopComposeUiTest(width = 1280, height = 720) {
                var settingsOpened = 0
                val providers = mutableListOf<String>()
                val original = fixture.settings.load()
                setContent {
                    ViewerTheme(false) {
                        AiConversationWorkspace(
                            fixture.runtime,
                            context("idle"),
                            UiLanguage.ENGLISH,
                            "fake-model",
                            settingsStoreOverride = fixture.settings,
                            onOpenSettings = { settingsOpened++ },
                            loadModels = { provider, _ ->
                                providers += provider
                                listOf("frozen-model", "free/gpt-next-model")
                            },
                        )
                    }
                }
                onNodeWithTag("ai-chat-messages").assertExists()
                onNodeWithTag("ai-composer").assertExists()
                for (tab in listOf("Provider", "Agent", "Skill", "Policy")) onNodeWithText(tab).assertDoesNotExist()
                onNodeWithTag("ai-evidence").assertDoesNotExist()
                onNodeWithTag("ai-settings").assertDoesNotExist()
                onNodeWithTag("ai-model").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("ai-chat-model-option-free/gpt-next-model").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("ai-chat-model-menu").assertExists()
                assertEquals(listOf("openai"), providers)
                assertEquals(0, settingsOpened)
                assertEquals(0, fixture.calls.get())
                onNodeWithTag("ai-chat-model-option-free/gpt-next-model").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.settings.settings.value.model == "free/gpt-next-model" }
                onNodeWithTag("ai-model").assertTextContains("free/gpt-next-model", substring = true)
                assertEquals(original.copy(model = "free/gpt-next-model"), fixture.settings.load())
                assertEquals(0, fixture.calls.get())
                assertEquals(0, settingsOpened)
                onNodeWithTag("ai-question").performTextReplacement("Use the model selected in this chat")
                onNodeWithTag("ai-send").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                assertEquals(
                    "free/gpt-next-model",
                    fixture.runtime.service.state.value.prepared
                        ?.model,
                )
                assertEquals(0, fixture.calls.get())
            }
        }

    @Test
    fun `chat model refresh explicitly reloads the catalog without sending a message`() =
        WorkspaceFixture().use { fixture ->
            val refreshes = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
            runDesktopComposeUiTest(width = 1280, height = 720) {
                setContent {
                    ViewerTheme(false) {
                        AiConversationWorkspace(
                            fixture.runtime,
                            context("idle"),
                            UiLanguage.ENGLISH,
                            "fake-model",
                            settingsStoreOverride = fixture.settings,
                            loadModels = { _, refresh ->
                                refreshes += refresh
                                listOf(if (refresh) "gpt-refreshed-model" else "gpt-cached-model")
                            },
                        )
                    }
                }
                onNodeWithTag("ai-model").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("ai-chat-model-option-gpt-cached-model").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("ai-chat-model-retry").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("ai-chat-model-option-gpt-refreshed-model").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("ai-chat-model-option-gpt-cached-model").assertDoesNotExist()
                assertEquals(listOf(false, true), refreshes.toList())
                assertEquals("frozen-model", fixture.settings.load().model)
                assertEquals(0, fixture.calls.get())
            }
        }

    @Test
    fun `an open model menu loads the new Provider while discarding the previous Provider late catalog`() =
        WorkspaceFixture().use { fixture ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            try {
                runDesktopComposeUiTest(width = 1280, height = 720) {
                    setContent {
                        ViewerTheme(false) {
                            AiConversationWorkspace(
                                fixture.runtime,
                                context("idle"),
                                UiLanguage.ENGLISH,
                                "fake-model",
                                settingsStoreOverride = fixture.settings,
                                loadModels = { provider, _ ->
                                    if (provider == "openai") {
                                        entered.complete(Unit)
                                        release.await()
                                        listOf("gpt-stale-model")
                                    } else {
                                        check(provider == "claude")
                                        listOf("claude-new-model")
                                    }
                                },
                            )
                        }
                    }
                    onNodeWithTag("ai-model").performClick()
                    waitUntil(timeoutMillis = 10_000) { entered.isCompleted }
                    runOnIdle { fixture.settings.update { it.copy(providerId = "claude", model = "claude-new-model") } }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithTag("ai-chat-model-option-claude-new-model").fetchSemanticsNodes().isNotEmpty()
                    }
                    release.complete(Unit)
                    waitForIdle()
                    onNodeWithTag("ai-chat-model-option-gpt-stale-model").assertDoesNotExist()
                    onNodeWithTag("ai-chat-model-option-claude-new-model").assertExists()
                    onNodeWithTag("ai-model").assertTextContains("claude-new-model", substring = true)
                    assertEquals(0, fixture.calls.get())
                }
            } finally {
                release.complete(Unit)
            }
        }

    @Test
    fun `model catalog failure stays in the chat menu and leaves saved settings and draft intact`() =
        WorkspaceFixture().use { fixture ->
            val original = fixture.settings.load()
            runDesktopComposeUiTest(width = 1280, height = 720) {
                var settingsOpened = 0
                setContent {
                    ViewerTheme(false) {
                        AiConversationWorkspace(
                            fixture.runtime,
                            context("idle"),
                            UiLanguage.SIMPLIFIED_CHINESE,
                            "fake-model",
                            settingsStoreOverride = fixture.settings,
                            onOpenSettings = { settingsOpened++ },
                            loadModels = { _, _ -> error("private-fixture-error") },
                        )
                    }
                }
                onNodeWithTag("ai-question").performTextReplacement("An unsent draft")
                onNodeWithTag("ai-model").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("ai-chat-model-status").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("ai-chat-model-menu").assertExists()
                onNodeWithText("private-fixture-error").assertDoesNotExist()
                assertEquals(original, fixture.settings.load())
                assertEquals(0, fixture.calls.get())
                assertEquals(0, settingsOpened)
            }
        }

    @Test
    fun `external settings update idle chat without a request and next send uses them`() =
        WorkspaceFixture().use { fixture ->
            runDesktopComposeUiTest(width = 1280, height = 720) {
                setContent {
                    ViewerTheme(false) {
                        AiConversationWorkspace(
                            fixture.runtime,
                            context("idle"),
                            UiLanguage.ENGLISH,
                            "fake-model",
                            settingsStoreOverride = fixture.settings,
                        )
                    }
                }
                runOnIdle {
                    fixture.newSettingsStore().save(
                        fixture.settings.load().copy(providerId = "codex", model = "codex-idle", agentId = "aps.main"),
                    )
                }
                onNodeWithTag("ai-model").assertTextContains("codex-idle", substring = true)
                assertEquals(null, fixture.runtime.service.state.value.prepared)
                assertEquals(0, fixture.calls.get())
                onNodeWithTag("ai-question").performTextReplacement("Explain the current frame")
                onNodeWithTag("ai-send").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                val prepared = checkNotNull(fixture.runtime.service.state.value.prepared)
                assertEquals("codex", prepared.providerId)
                assertEquals("codex-idle", prepared.model)
                assertTrue(prepared.plannedProviderCallCount >= 2, "Saved Main Agent must plan its report child")
                assertEquals(context("idle").request.sessionId, prepared.analysisPreview?.sessionId)
                assertEquals(0, fixture.calls.get())
                onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-confirm"))
                onNodeWithTag("ai-confirm").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.SUCCEEDED }
                assertTrue(
                    fixture.runtime.service.state.value.timeline.any {
                        it.kind == ExecutionKind.RUN && it.phase == ExecutionPhase.STARTED && it.subjectId == "aps.main"
                    },
                )
                assertEquals(List(prepared.plannedProviderCallCount) { "codex-idle" }, fixture.models.toList())
            }
        }

    @Test
    fun `saved report format and language apply to new runs and cannot replace a pending snapshot`() =
        WorkspaceFixture().use { fixture ->
            fixture.settings.update {
                it.copy(
                    allowArtifacts = true,
                    reportFormat = ArtifactFormat.MARKDOWN,
                    reportLanguage = ReportLanguage.SIMPLIFIED_CHINESE,
                )
            }
            runDesktopComposeUiTest(width = 1280, height = 720) {
                setContent {
                    ViewerTheme(false) {
                        AiConversationWorkspace(
                            fixture.runtime,
                            context("report-format"),
                            UiLanguage.ENGLISH,
                            "fake-model",
                            settingsStoreOverride = fixture.settings,
                        )
                    }
                }
                onNodeWithTag("ai-question").performTextReplacement("Write the selected evidence report")
                onNodeWithTag("ai-send").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                val prepared = checkNotNull(fixture.runtime.service.state.value.prepared)
                assertEquals(ArtifactFormat.MARKDOWN, prepared.reportFormat)
                assertEquals(ReportLanguage.SIMPLIFIED_CHINESE, prepared.analysisPreview?.outputLanguage)
                assertTrue(Permission.WRITE_ARTIFACT in prepared.approval.permissions)
                onNodeWithTag("ai-report-format").assertTextContains("Markdown (.md)", substring = true)
                onNodeWithTag("ai-report-language").assertTextContains("Simplified Chinese", substring = true)
                runOnIdle {
                    fixture.newSettingsStore().update {
                        it.copy(reportFormat = ArtifactFormat.HTML, reportLanguage = ReportLanguage.ENGLISH)
                    }
                }
                waitForIdle()
                assertSame(prepared, fixture.runtime.service.state.value.prepared)
                onNodeWithTag("ai-report-format").assertTextContains("Markdown (.md)", substring = true)
                onNodeWithTag("ai-report-language").assertTextContains("Simplified Chinese", substring = true)
                onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-confirm"))
                onNodeWithTag("ai-confirm").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.SUCCEEDED }
                val hash = checkNotNull(fixture.runtime.service.state.value.publishedArtifactHash)
                assertEquals(
                    ArtifactFormat.MARKDOWN,
                    fixture.runtime.artifacts
                        .metadata(hash)
                        .format,
                )
                assertTrue(
                    fixture.runtime.artifacts
                        .read(hash)
                        .decodeToString()
                        .startsWith("# Performance analysis"),
                )

                onNodeWithTag("ai-question").performTextReplacement("Write the next report")
                onNodeWithTag("ai-send").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                assertEquals(
                    ArtifactFormat.HTML,
                    fixture.runtime.service.state.value.prepared
                        ?.reportFormat,
                )
                onNodeWithTag("ai-report-format").assertTextContains("HTML (.html)", substring = true)
                assertEquals(
                    ReportLanguage.ENGLISH,
                    fixture.runtime.service.state.value.prepared
                        ?.analysisPreview
                        ?.outputLanguage,
                )
                onNodeWithTag("ai-report-language").assertTextContains("English", substring = true)
            }
        }

    @Test
    fun `idle send reads saved provider model and agent before settings observation synchronizes`() =
        WorkspaceFixture().use { fixture ->
            runDesktopComposeUiTest(width = 1280, height = 720) {
                setContent {
                    ViewerTheme(false) {
                        AiConversationWorkspace(
                            fixture.runtime,
                            context("action-boundary"),
                            UiLanguage.ENGLISH,
                            "fake-model",
                            settingsStoreOverride = fixture.settings,
                        )
                    }
                }
                onNodeWithTag("ai-question").performTextReplacement("Use the settings saved immediately before sending")
                onNodeWithTag("ai-send").performSemanticsAction(SemanticsActions.OnClick) { send ->
                    fixture.newSettingsStore().save(
                        fixture.settings.load().copy(providerId = "claude", model = "claude-at-send", agentId = "aps.main"),
                    )
                    send()
                }
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                val prepared = checkNotNull(fixture.runtime.service.state.value.prepared)
                assertEquals("claude", prepared.providerId)
                assertEquals("claude-at-send", prepared.model)
                assertTrue(prepared.plannedProviderCallCount >= 2, "Saved Main Agent must plan its report child")
                assertEquals(permissions, prepared.approval.permissions)
                assertEquals(context("action-boundary").request.sessionId, prepared.analysisPreview?.sessionId)
                onNodeWithTag("ai-model").assertTextContains("claude-at-send", substring = true)
                assertEquals(0, fixture.calls.get())
            }
        }

    @Test
    fun `awaiting and running keep frozen settings and next question uses latest settings`() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        WorkspaceFixture(beforeResult = {
            entered.complete(Unit)
            release.await()
        }).use { fixture ->
            runDesktopComposeUiTest(width = 1280, height = 720) {
                setContent {
                    ViewerTheme(false) {
                        AiConversationWorkspace(
                            fixture.runtime,
                            context("active"),
                            UiLanguage.ENGLISH,
                            "fake-model",
                            settingsStoreOverride = fixture.settings,
                        )
                    }
                }
                onNodeWithTag("ai-question").performTextReplacement("First question")
                onNodeWithTag("ai-send").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                val prepared = checkNotNull(fixture.runtime.service.state.value.prepared)
                assertEquals("openai", prepared.providerId)
                assertEquals("frozen-model", prepared.model)
                assertEquals(1, prepared.plannedProviderCallCount)
                runOnIdle {
                    fixture.newSettingsStore().save(
                        fixture.settings.load().copy(providerId = "codex", model = "changed-awaiting", agentId = "aps.main"),
                    )
                }
                waitForIdle()
                assertSame(prepared, fixture.runtime.service.state.value.prepared)
                assertEquals(RunStatus.AWAITING_APPROVAL, fixture.runtime.service.state.value.status)
                assertEquals(0, fixture.calls.get())
                onNodeWithText("openai / frozen-model", substring = true).assertExists()
                onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-confirm"))
                onNodeWithTag("ai-confirm").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    entered.isCompleted ||
                        fixture.runtime.service.state.value.status
                            ?.terminal == true
                }
                assertTrue(entered.isCompleted, "Provider was not entered: ${fixture.runtime.service.state.value}")
                runOnIdle {
                    fixture.newSettingsStore().save(
                        fixture.settings.load().copy(providerId = "claude", model = "changed-running", agentId = "aps.main"),
                    )
                }
                waitForIdle()
                assertSame(prepared, fixture.runtime.service.state.value.prepared)
                assertEquals(RunStatus.RUNNING, fixture.runtime.service.state.value.status)
                assertEquals(1, fixture.calls.get())
                assertEquals(listOf("frozen-model"), fixture.models.toList())
                assertTrue(
                    fixture.runtime.service.state.value.timeline.any {
                        it.kind == ExecutionKind.RUN && it.phase == ExecutionPhase.STARTED && it.subjectId == "aps.report"
                    },
                )
                release.complete(Unit)
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.SUCCEEDED }
                onNodeWithTag("ai-question").performTextReplacement("Second question")
                onNodeWithTag("ai-send").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                val next = checkNotNull(fixture.runtime.service.state.value.prepared)
                assertEquals("claude", next.providerId)
                assertEquals("changed-running", next.model)
                assertTrue(next.plannedProviderCallCount >= 2, "Next question must use the saved Main Agent")
                assertTrue(next.approval.runId != prepared.approval.runId)
                assertEquals(1, next.analysisPreview!!.conversationContext.size)
                assertEquals(1, fixture.calls.get())
            }
        }
    }

    @Test
    fun `restoring the exact current report retains canonical evidence without a provider request`() =
        WorkspaceFixture(includeFinding = true).use { fixture ->
            runDesktopComposeUiTest(width = 1280, height = 720) {
                setContent {
                    ViewerTheme(false) {
                        AiConversationWorkspace(
                            fixture.runtime,
                            context("known"),
                            UiLanguage.ENGLISH,
                            "fake-model",
                            settingsStoreOverride = fixture.settings,
                        )
                    }
                }
                onNodeWithTag("ai-question").performTextReplacement("Explain this finding")
                onNodeWithTag("ai-send").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-confirm"))
                waitForIdle()
                onNodeWithTag("ai-confirm").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.SUCCEEDED }
                onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-finding-evidence-evidence-known"))
                onNodeWithTag("ai-finding-evidence-evidence-known").performClick()
                onNodeWithTag("ai-evidence-detail-summary").assertTextContains("Canonical timing for known")
                onNodeWithTag("ai-evidence-detail-payload").assertTextContains("{\"durationNs\":123}")
                onNodeWithTag("ai-evidence-detail-identity").assertTextContains(context("known").request.sessionId.value, substring = true)
                onNodeWithText("Close").performClick()
                val id = checkNotNull(fixture.runtime.service.state.value.conversationId)
                runBlocking { fixture.runtime.service.restore(id) }
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.restored }
                onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-finding-evidence-evidence-known"))
                onNodeWithTag("ai-finding-evidence-evidence-known").performClick()
                onNodeWithTag("ai-evidence-context-unavailable").assertDoesNotExist()
                onNodeWithTag("ai-evidence-detail-payload").assertTextContains("{\"durationNs\":123}")
                onNodeWithText("Close").performClick()
                onNodeWithTag("ai-question").performTextReplacement("Continue the same report")
                onNodeWithTag("ai-send").assertIsEnabled()
                onNodeWithTag("ai-context-unavailable").assertDoesNotExist()
                assertEquals(1, fixture.calls.get())
            }
        }

    @Test
    fun `new chat preserves real history and followup transmits bounded earlier dialogue`() =
        WorkspaceFixture().use { fixture ->
            runDesktopComposeUiTest(width = 1280, height = 720) {
                setContent {
                    ViewerTheme(false) {
                        AiConversationWorkspace(
                            fixture.runtime,
                            context("history"),
                            UiLanguage.ENGLISH,
                            "fake-model",
                            settingsStoreOverride = fixture.settings,
                        )
                    }
                }
                for (question in listOf("First question", "Second question")) {
                    onNodeWithTag("ai-question").performTextReplacement(question)
                    onNodeWithTag("ai-send").performClick()
                    waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                    onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-confirm"))
                    onNodeWithTag("ai-confirm").performClick()
                    waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.SUCCEEDED }
                }
                val id = checkNotNull(fixture.runtime.service.state.value.conversationId)
                assertEquals(
                    "First question",
                    fixture.requests
                        .last()
                        .conversationContext
                        .single()
                        .question,
                )
                assertEquals(2, runBlocking { fixture.runtime.service.transcript(id) }.size)
                onNodeWithTag("ai-new-chat").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == null }
                onNodeWithTag("ai-session-$id").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.restored }
                assertEquals(2, fixture.calls.get())
                onNodeWithTag("ai-question").performTextReplacement("Continue the same report")
                onNodeWithTag("ai-send").assertIsEnabled().performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                val followup = checkNotNull(fixture.runtime.service.state.value.prepared)
                assertEquals(id, followup.conversationId)
                assertEquals(context("history").request.evidence, fixture.requests.last().evidence)
                assertEquals(2, followup.analysisPreview?.conversationContext?.size)
                assertEquals(2, fixture.calls.get())
            }
        }

    @Test
    fun `missing historical evidence can start an ordinary chat without losing the draft`() =
        WorkspaceFixture().use { fixture ->
            runDesktopComposeUiTest(width = 1280, height = 720) {
                var active by mutableStateOf<BusinessContext?>(context("missing"))
                setContent {
                    ViewerTheme(false) {
                        AiConversationWorkspace(
                            fixture.runtime,
                            active,
                            UiLanguage.SIMPLIFIED_CHINESE,
                            "fake-model",
                            settingsStoreOverride = fixture.settings,
                        )
                    }
                }
                onNodeWithTag("ai-question").performTextReplacement("First report question")
                onNodeWithTag("ai-send").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                val id = checkNotNull(fixture.runtime.service.state.value.conversationId)
                onNodeWithTag("ai-cancel-preflight").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.CANCELLED }
                runOnIdle { active = context("other") }
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == null }
                onNodeWithTag("ai-session-$id").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.restored }
                onNodeWithTag("ai-question").performTextReplacement("An ordinary question")
                onNodeWithTag("ai-send").assertIsNotEnabled()
                onNodeWithTag("ai-start-ordinary-chat").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == null }
                onNodeWithTag("ai-question").assertTextContains("An ordinary question")
                onNodeWithTag("ai-bound-context").assertDoesNotExist()
                onNodeWithTag("ai-send").assertIsEnabled().performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                val prepared = checkNotNull(fixture.runtime.service.state.value.prepared)
                assertTrue(prepared.conversationId != id)
                assertEquals(emptyList(), prepared.analysisPreview?.evidence)
                assertEquals(emptyList(), prepared.analysisPreview?.sourceCandidates)
                assertEquals("An ordinary question", prepared.analysisPreview?.userQuestion)
                assertEquals(0, fixture.calls.get())
            }
        }

    @Test
    fun `changing report hides previous answers without resending and keeps old history`() =
        WorkspaceFixture().use { fixture ->
            runDesktopComposeUiTest(width = 1280, height = 720) {
                var active by mutableStateOf<BusinessContext?>(context("a"))
                setContent {
                    ViewerTheme(false) {
                        AiConversationWorkspace(
                            fixture.runtime,
                            active,
                            UiLanguage.ENGLISH,
                            "fake-model",
                            settingsStoreOverride = fixture.settings,
                        )
                    }
                }
                onNodeWithTag("ai-question").performTextReplacement("Question A")
                onNodeWithTag("ai-send").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-confirm"))
                onNodeWithTag("ai-confirm").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.SUCCEEDED }
                val old = fixture.runtime.service.state.value.conversationId!!
                runOnIdle { active = context("b") }
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == null }
                onNodeWithText("Fake verified result").assertDoesNotExist()
                onNodeWithTag("ai-bound-context").assertTextContains("Report b")
                assertEquals(1, runBlocking { fixture.runtime.service.transcript(old) }.size)
                assertEquals(1, fixture.calls.get())
            }
        }

    @Test
    fun `queued question uses settings saved after enqueue and still requires a new approval`() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        WorkspaceFixture(beforeResult = {
            entered.complete(Unit)
            release.await()
        }).use { fixture ->
            runDesktopComposeUiTest(width = 1280, height = 720) {
                setContent {
                    ViewerTheme(false) {
                        AiConversationWorkspace(
                            fixture.runtime,
                            context("queue"),
                            UiLanguage.ENGLISH,
                            "fake-model",
                            settingsStoreOverride = fixture.settings,
                        )
                    }
                }
                onNodeWithTag("ai-question").performTextReplacement("First question")
                onNodeWithTag("ai-send").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                val first = checkNotNull(fixture.runtime.service.state.value.prepared)
                onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-confirm"))
                onNodeWithTag("ai-confirm").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    entered.isCompleted ||
                        fixture.runtime.service.state.value.status
                            ?.terminal == true
                }
                assertTrue(entered.isCompleted, "Provider was not entered: ${fixture.runtime.service.state.value}")
                runOnIdle {
                    fixture.newSettingsStore().save(
                        fixture.settings.load().copy(providerId = "claude", model = "saved-at-enqueue", agentId = "aps.source-evidence"),
                    )
                }
                onNodeWithTag("ai-question").performTextReplacement("Queued question")
                onNodeWithTag("ai-send").performClick()
                onNodeWithTag("ai-queued-0").assertTextContains("Queued question")
                assertEquals(1, fixture.calls.get())
                runOnIdle {
                    fixture.newSettingsStore().save(
                        fixture.settings.load().copy(providerId = "codex", model = "saved-before-claim", agentId = "aps.main"),
                    )
                }
                assertSame(first, fixture.runtime.service.state.value.prepared)
                onNodeWithTag("ai-question").performTextReplacement("A third unsent draft")
                release.complete(Unit)
                waitUntil(timeoutMillis = 10_000) {
                    fixture.runtime.service.state.value.prepared
                        ?.analysisPreview
                        ?.userQuestion == "Queued question"
                }
                assertEquals(RunStatus.AWAITING_APPROVAL, fixture.runtime.service.state.value.status)
                val queued = checkNotNull(fixture.runtime.service.state.value.prepared)
                assertEquals("codex", queued.providerId)
                assertEquals("saved-before-claim", queued.model)
                assertTrue(queued.plannedProviderCallCount >= 2, "Queue must use Main Agent saved after enqueue")
                assertTrue(queued.approval.runId != first.approval.runId)
                assertTrue(queued.approval.id != first.approval.id)
                assertEquals(first.analysisPreview?.sessionId, queued.analysisPreview?.sessionId)
                assertEquals(first.analysisPreview?.evidence, queued.analysisPreview?.evidence)
                assertEquals(emptyList(), queued.analysisPreview?.sourceCandidates)
                assertEquals(permissions, queued.approval.permissions)
                assertEquals(1, fixture.calls.get())
                onNodeWithTag("ai-model").assertTextContains("saved-before-claim", substring = true)
                onNodeWithTag("ai-question").assertTextContains("A third unsent draft")
                onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-confirm"))
                onNodeWithTag("ai-confirm").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.SUCCEEDED }
                waitForIdle()
                assertEquals(1 + queued.plannedProviderCallCount, fixture.calls.get(), "Claimed queued draft must not execute twice")
                assertEquals(
                    listOf("frozen-model") + List(queued.plannedProviderCallCount) { "saved-before-claim" },
                    fixture.models.toList(),
                )
                assertTrue(
                    fixture.runtime.service.state.value.timeline.any {
                        it.kind == ExecutionKind.RUN && it.phase == ExecutionPhase.STARTED && it.subjectId == "aps.main"
                    },
                )
                onNodeWithTag("ai-question").assertTextContains("A third unsent draft")
            }
        }
    }

    @Test
    fun `closing and reopening on another report cannot rebind old references with reused ids`() =
        WorkspaceFixture(includeFinding = true).use { fixture ->
            val a =
                createBusinessContext(
                    "frame",
                    "Report A",
                    listOf(PerformanceEvidence("reused", "frame-selection", "A evidence", "{\"durationNs\":123}")),
                    true,
                )
            val b =
                createBusinessContext(
                    "frame",
                    "Report B",
                    listOf(PerformanceEvidence("reused", "frame-selection", "B evidence", "{\"durationNs\":456}")),
                    true,
                )
            val memory = ChatWindowMemory(a)
            var visible by mutableStateOf(true)
            var current by mutableStateOf(a)
            runDesktopComposeUiTest(width = 1280, height = 720) {
                setContent {
                    ViewerTheme(false) {
                        if (visible) {
                            ConversationWorkspace(
                                fixture.runtime,
                                current,
                                UiLanguage.ENGLISH,
                                "fake-model",
                                {},
                                {},
                                fixture.settings,
                                memory,
                            )
                        }
                    }
                }
                onNodeWithTag("ai-question").performTextReplacement("Question A")
                onNodeWithTag("ai-send").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-confirm"))
                onNodeWithTag("ai-confirm").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.SUCCEEDED }
                val oldId = fixture.runtime.service.state.value.conversationId!!
                runOnIdle { visible = false }
                waitForIdle()
                runOnIdle {
                    current = b
                    visible = true
                }
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == null }
                onNodeWithTag("ai-bound-context").assertTextContains("Report B")
                onNodeWithTag("ai-session-$oldId").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.restored }
                onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-finding-evidence-reused"))
                onNodeWithTag("ai-finding-evidence-reused").performClick()
                onNodeWithTag("ai-evidence-context-unavailable").assertExists()
                onNodeWithText("B evidence").assertDoesNotExist()
                assertEquals(1, fixture.calls.get())
            }
        }

    @Test
    fun `external approval stays bound to its own report and cancel never sends`() =
        WorkspaceFixture().use { fixture ->
            runDesktopComposeUiTest(width = 900, height = 720) {
                setContent {
                    ViewerTheme(true) {
                        AiConversationWorkspace(
                            fixture.runtime,
                            context("a"),
                            UiLanguage.ENGLISH,
                            "fake-model",
                            settingsStoreOverride = fixture.settings,
                        )
                    }
                }
                runBlocking {
                    fixture.runtime.service.prepare(
                        "external",
                        context("b").request,
                        "aps.report",
                        "openai",
                        "external-model",
                        permissions,
                    )
                }
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                onNodeWithTag("ai-bound-context").assertTextContains("Report b")
                onNodeWithText("openai / external-model", substring = true).assertExists()
                onNodeWithTag("ai-question").performTextReplacement("Do not send into the external approval")
                onNodeWithTag("ai-stop").assertExists()
                onNodeWithTag("ai-context-unavailable").assertExists()
                onNodeWithTag("ai-cancel-preflight").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.CANCELLED }
                assertEquals(0, fixture.calls.get())
            }
        }

    @Test
    fun `context becoming available preserves an unsent draft`() =
        WorkspaceFixture().use { fixture ->
            var active by mutableStateOf<BusinessContext?>(null)
            runDesktopComposeUiTest(width = 900, height = 720) {
                setContent {
                    ViewerTheme(false) {
                        AiConversationWorkspace(
                            fixture.runtime,
                            active,
                            UiLanguage.ENGLISH,
                            "fake-model",
                            settingsStoreOverride = fixture.settings,
                        )
                    }
                }
                onNodeWithTag("ai-question").performTextReplacement("Keep this draft")
                runOnIdle { active = context("late") }
                waitForIdle()
                onNodeWithTag("ai-bound-context").assertTextContains("Report late")
                onNodeWithTag("ai-question").assertTextContains("Keep this draft")
                assertEquals(0, fixture.calls.get())
            }
        }

    @Test
    fun `stopping preparation while waiting for the interaction lock permits another send`() =
        WorkspaceFixture().use { fixture ->
            val context = context("waiting")
            val memory = ChatWindowMemory(context)
            val lock = Mutex(locked = true)
            runDesktopComposeUiTest(width = 1280, height = 720) {
                setContent {
                    ViewerTheme(false) {
                        ConversationWorkspace(
                            fixture.runtime,
                            context,
                            UiLanguage.ENGLISH,
                            "fake-model",
                            {},
                            {},
                            fixture.settings,
                            memory,
                            lock,
                        )
                    }
                }
                onNodeWithTag("ai-question").performTextReplacement("Keep this cancelled draft")
                onNodeWithTag("ai-send").performClick()
                onNodeWithTag("ai-stop").performClick()
                runOnIdle { lock.unlock() }
                waitForIdle()
                onNodeWithTag("ai-question").assertTextContains("Keep this cancelled draft")
                onNodeWithTag("ai-send").assertIsEnabled()
                onNodeWithTag("ai-send").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                assertEquals(
                    "Keep this cancelled draft",
                    fixture.runtime.service.state.value.prepared
                        ?.analysisPreview
                        ?.userQuestion,
                )
                assertEquals(0, fixture.calls.get())
            }
        }

    @Test
    fun `restored source conversation requires explicit source reauthorization before followup`() =
        WorkspaceFixture(authorizeSource = true).use { fixture ->
            fixture.settings.save(fixture.settings.load().copy(agentId = "aps.main"))
            val evidence = context("source")
            val context =
                evidence.copy(
                    request =
                        evidence.request.copy(
                            sourceCandidates = listOf(AiSourceCandidate("candidate", "Main.kt", null, "EXACT", emptyList(), "source-body")),
                        ),
                )
            val memory = ChatWindowMemory(context)
            var visible by mutableStateOf(true)
            runDesktopComposeUiTest(width = 1280, height = 720) {
                setContent {
                    ViewerTheme(false) {
                        if (visible) {
                            ConversationWorkspace(
                                fixture.runtime,
                                context,
                                UiLanguage.ENGLISH,
                                "fake-model",
                                {},
                                {},
                                fixture.settings,
                                memory,
                            )
                        }
                    }
                }
                onNodeWithTag("ai-context-menu").performClick()
                onNodeWithTag("ai-include-source").performClick()
                onNodeWithTag("ai-question").performTextReplacement("Explain the source")
                onNodeWithTag("ai-send").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status != null }
                assertEquals(
                    RunStatus.AWAITING_APPROVAL,
                    fixture.runtime.service.state.value.status,
                    fixture.runtime.service.state.value.errorCode,
                )
                val callCount = checkNotNull(fixture.runtime.service.state.value.prepared).plannedProviderCallCount
                onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-confirm"))
                onNodeWithTag("ai-confirm").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.SUCCEEDED }
                val id = checkNotNull(fixture.runtime.service.state.value.conversationId)
                onNodeWithTag("ai-question").performTextReplacement("Unsent source followup")
                onNodeWithTag("ai-context-menu").performClick()
                onNodeWithTag("ai-include-source").performClick()
                onNodeWithTag("ai-send").assertIsNotEnabled()
                onNodeWithTag("ai-context-menu").performClick()
                onNodeWithTag("ai-include-source").performClick()
                onNodeWithTag("ai-send").assertIsEnabled()
                runOnIdle { visible = false }
                waitForIdle()
                runOnIdle { visible = true }
                waitForIdle()
                onNodeWithTag("ai-question").assertTextContains("Unsent source followup")
                onNodeWithTag("ai-send").assertIsNotEnabled()
                onNodeWithTag("ai-context-menu").performClick()
                onNodeWithTag("ai-include-source").performClick()
                onNodeWithTag("ai-send").assertIsEnabled()
                onNodeWithTag("ai-new-chat").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == null }
                onNodeWithTag("ai-session-$id").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.restored }
                onNodeWithTag("ai-question").performTextReplacement("Follow up on this source")
                onNodeWithTag("ai-send").assertIsNotEnabled()
                onNodeWithTag("ai-context-unavailable").assertExists()
                onNodeWithTag("ai-reauthorize-source").performClick()
                onNodeWithTag("ai-send").assertIsEnabled()
                onNodeWithTag("ai-send").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                val prepared = checkNotNull(fixture.runtime.service.state.value.prepared)
                assertEquals(id, prepared.conversationId)
                assertEquals("Follow up on this source", prepared.analysisPreview?.userQuestion)
                assertEquals(listOf("candidate"), prepared.analysisPreview?.sourceCandidates?.map { it.id })
                assertEquals(callCount, fixture.calls.get())
            }
        }

    @Test
    fun `external saved settings keep ordinary chat on chat agent with no additional permissions`() =
        WorkspaceFixture().use { fixture ->
            runDesktopComposeUiTest(width = 1280, height = 720) {
                setContent {
                    ViewerTheme(false) {
                        AiConversationWorkspace(
                            fixture.runtime,
                            null,
                            UiLanguage.ENGLISH,
                            "fake-model",
                            settingsStoreOverride = fixture.settings,
                        )
                    }
                }
                runOnIdle {
                    fixture.newSettingsStore().save(
                        AiPlatformSettings("codex", "saved-chat-model", agentId = "aps.main", allowArtifacts = true, allowScripts = true),
                    )
                }
                onNodeWithTag("ai-question").performTextReplacement("An ordinary chat question")
                onNodeWithTag("ai-send").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                val prepared = checkNotNull(fixture.runtime.service.state.value.prepared)
                assertEquals("codex", prepared.providerId)
                assertEquals("saved-chat-model", prepared.model)
                assertEquals(1, prepared.plannedProviderCallCount)
                assertEquals(0, prepared.plannedScriptCount)
                assertEquals(permissions, prepared.approval.permissions)
                assertEquals(emptyList(), prepared.analysisPreview?.evidence)
                assertEquals(emptyList(), prepared.analysisPreview?.sourceCandidates)
                assertEquals(0, fixture.calls.get())
                onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-confirm"))
                onNodeWithTag("ai-confirm").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.SUCCEEDED }
                assertTrue(
                    fixture.runtime.history.audit(prepared.conversationId).any {
                        it.kind == ExecutionKind.RUN && it.phase == ExecutionPhase.STARTED && it.subjectId == "aps.chat"
                    },
                )
                assertEquals(listOf("saved-chat-model"), fixture.models.toList())
                assertEquals(1, fixture.calls.get())
            }
        }

    @Test
    fun `chat without a report sends with approval and restores bounded followup history`() =
        WorkspaceFixture().use { fixture ->
            fixture.settings.save(fixture.settings.load().copy(agentId = "aps.main", allowScripts = true, allowArtifacts = true))
            runDesktopComposeUiTest(width = 1280, height = 720) {
                setContent {
                    ViewerTheme(false) {
                        AiConversationWorkspace(
                            fixture.runtime,
                            null,
                            UiLanguage.ENGLISH,
                            "fake-model",
                            settingsStoreOverride = fixture.settings,
                        )
                    }
                }
                onNodeWithTag("ai-question").performTextReplacement("A question")
                onNodeWithTag("ai-send").assertIsEnabled().performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                val first = checkNotNull(fixture.runtime.service.state.value.prepared)
                assertEquals(1, first.plannedProviderCallCount)
                assertEquals(0, first.plannedScriptCount)
                assertEquals(permissions, first.approval.permissions)
                assertEquals(emptyList(), first.analysisPreview?.evidence)
                assertEquals(emptyList(), first.analysisPreview?.sourceCandidates)
                assertEquals(0, fixture.calls.get())
                onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-confirm"))
                onNodeWithTag("ai-confirm").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.SUCCEEDED }
                assertEquals(1, fixture.calls.get())
                val id = checkNotNull(fixture.runtime.service.state.value.conversationId)
                onNodeWithTag("ai-new-chat").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == null }
                onNodeWithTag("ai-question").performTextReplacement("Another chat")
                onNodeWithTag("ai-send").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-confirm"))
                onNodeWithTag("ai-confirm").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.SUCCEEDED }
                val otherId = checkNotNull(fixture.runtime.service.state.value.conversationId)
                onNodeWithTag("ai-new-chat").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == null }
                onNodeWithTag("ai-session-$id").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.restored }
                onNodeWithTag("ai-question").performTextReplacement("A followup")
                onNodeWithTag("ai-send").assertIsEnabled()
                onNodeWithTag("ai-session-$id").performClick()
                onNodeWithTag("ai-send").assertIsEnabled()
                onNodeWithTag("ai-session-$otherId").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.conversationId == otherId }
                onNodeWithTag("ai-question").performTextReplacement("Another followup")
                onNodeWithTag("ai-send").assertIsEnabled()
                onNodeWithTag("ai-session-$id").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.conversationId == id }
                onNodeWithTag("ai-send").assertIsEnabled().performClick()
                waitUntil(timeoutMillis = 10_000) {
                    fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL &&
                        fixture.runtime.service.state.value.prepared
                            ?.approval
                            ?.runId != first.approval.runId
                }
                val followup = checkNotNull(fixture.runtime.service.state.value.prepared)
                assertEquals("A followup", followup.analysisPreview?.userQuestion)
                assertEquals(first.analysisPreview?.sessionId, followup.analysisPreview?.sessionId)
                assertEquals(listOf(first.approval.runId), followup.analysisPreview?.conversationContext?.map { it.runId })
                assertEquals(emptyList(), followup.analysisPreview?.evidence)
                assertEquals(emptyList(), followup.analysisPreview?.sourceCandidates)
                assertEquals(2, fixture.calls.get())
            }
        }

    @Test
    fun `preflight displays actual child model call count and respects declared permissions`() =
        WorkspaceFixture().use { fixture ->
            fixture.settings.save(fixture.settings.load().copy(agentId = "aps.main", allowScripts = true))
            runDesktopComposeUiTest(width = 1280, height = 720) {
                setContent {
                    ViewerTheme(false) {
                        AiConversationWorkspace(
                            fixture.runtime,
                            context("children"),
                            UiLanguage.ENGLISH,
                            "fake-model",
                            settingsStoreOverride = fixture.settings,
                        )
                    }
                }
                onNodeWithTag("ai-question").performTextReplacement("Analyze the evidence")
                onNodeWithTag("ai-send").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.AWAITING_APPROVAL }
                val prepared = fixture.runtime.service.state.value.prepared!!
                assertTrue(prepared.plannedProviderCallCount >= 2)
                assertTrue(Permission.EXECUTE_SCRIPT !in prepared.approval.permissions)
                onNodeWithTag(
                    "ai-planned-invocations",
                ).assertTextContains("Model requests: ${prepared.plannedProviderCallCount}", substring = true)
                assertEquals(0, fixture.calls.get())
                onNodeWithTag("ai-chat-history").performScrollToNode(hasTestTag("ai-confirm"))
                onNodeWithTag("ai-confirm").performClick()
                waitUntil(timeoutMillis = 10_000) { fixture.runtime.service.state.value.status == RunStatus.SUCCEEDED }
                assertEquals(prepared.plannedProviderCallCount, fixture.calls.get())
            }
        }

    @Test
    fun `chat screenshots cover EN ZH light dark narrow wide without implicit requests`() {
        for (width in listOf(900, 1280)) {
            for (language in listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE)) {
                for (dark in listOf(false, true)) {
                    WorkspaceFixture().use { fixture ->
                        runDesktopComposeUiTest(width = width, height = 720) {
                            setContent {
                                ViewerTheme(dark) {
                                    AiConversationWorkspace(
                                        fixture.runtime,
                                        context("screenshots"),
                                        language,
                                        "fake-model",
                                        settingsStoreOverride = fixture.settings,
                                    )
                                }
                            }
                            val composer = onNodeWithTag("ai-composer").fetchSemanticsNode().boundsInRoot
                            assertTrue(composer.top > 400 && composer.bottom <= 720)
                            val pixels = onNodeWithTag("ai-workspace").captureToImage().toPixelMap()
                            val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
                            for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
                            val output =
                                Path.of(
                                    "build/reports/ai-dsh-chat/host-$width-${language.name}-${if (dark) "dark" else "light"}.png",
                                )
                            Files.createDirectories(output.parent)
                            assertTrue(ImageIO.write(image, "png", output.toFile()))
                            assertEquals(0, fixture.calls.get())
                        }
                    }
                }
            }
        }
    }

    private fun context(id: String) =
        createBusinessContext(
            "frame",
            "Report $id",
            listOf(PerformanceEvidence("evidence-$id", "frame-selection", "Canonical timing for $id", "{\"durationNs\":123}")),
            true,
        )

    private val permissions = setOf(Permission.READ_EVIDENCE, Permission.PROVIDER_NETWORK)
}

private class WorkspaceFixture(
    private val includeFinding: Boolean = false,
    private val authorizeSource: Boolean = false,
    private val beforeResult: suspend () -> Unit = {
    },
) : AutoCloseable {
    private val root = Files.createTempDirectory("aps-ai-workspace-test-").toRealPath()
    private val preferences = Preferences.userRoot().node("com/androidperformancestudio/tests/${UUID.randomUUID()}")
    val settings =
        AiPlatformSettingsStore(
            preferences,
        ).also { it.save(AiPlatformSettings("openai", "frozen-model", agentId = "aps.report")) }
    val calls = AtomicInteger()
    val models = java.util.Collections.synchronizedList(mutableListOf<String>())
    val requests = java.util.Collections.synchronizedList(mutableListOf<AnalysisRequest>())
    val runtime =
        AiPlatformRuntime.open(
            root.resolve(
                "profile",
            ),
            openAiAvailable = { true },
            sourceAuthorization = { authorizeSource },
            openAiGateway = { model ->
                AiAnalysisGateway { request ->
                    calls.incrementAndGet()
                    models += model
                    requests += request
                    beforeResult()
                    AnalysisResult(
                        request.sessionId,
                        model,
                        "Fake verified result",
                        if (includeFinding) {
                            listOf(
                                AnalysisFinding(
                                    AnalysisFindingId("finding-known"),
                                    AnalysisSeverity.WARNING,
                                    "Known finding",
                                    "Selected evidence is relevant.",
                                    "Inspect the evidence.",
                                    0.9f,
                                    request.evidence.take(1).map { it.id },
                                    emptyList(),
                                ),
                            )
                        } else {
                            emptyList()
                        },
                    )
                }
            },
        )

    fun newSettingsStore(): AiPlatformSettingsStore = AiPlatformSettingsStore(preferences)

    override fun close() {
        runtime.close()
        val parent = preferences.parent()
        preferences.removeNode()
        parent.flush()
        root.toFile().deleteRecursively()
    }
}
