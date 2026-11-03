package com.androidperformancestudio.aiplatform.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ai.AiConversationContextTurn
import com.androidperformancestudio.ai.AiSourceCandidate
import com.androidperformancestudio.ai.AnalysisFinding
import com.androidperformancestudio.ai.AnalysisFindingId
import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisResult
import com.androidperformancestudio.ai.AnalysisScope
import com.androidperformancestudio.ai.AnalysisScopeKind
import com.androidperformancestudio.ai.AnalysisSessionId
import com.androidperformancestudio.ai.AnalysisSeverity
import com.androidperformancestudio.ai.PerformanceEvidence
import com.androidperformancestudio.ai.ProfilerKind
import com.androidperformancestudio.aiplatform.model.ApprovalRequest
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.RunStatus
import com.androidperformancestudio.aiplatform.runtime.ChatSessionSummary
import com.androidperformancestudio.aiplatform.runtime.ChatTurn
import com.androidperformancestudio.aiplatform.runtime.PreparedAgentRun
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DshChatSurfaceTest {
    @Test
    fun `delete always requires a second confirmation and cancellation preserves the session`() {
        for (status in listOf(RunStatus.SUCCEEDED, RunStatus.RUNNING)) {
            runDesktopComposeUiTest(width = 1280, height = 720) {
                val fixture = SurfaceFixture()
                fixture.state =
                    fixture.state.copy(sessions = fixture.state.sessions.map { if (it.id == "frame") it.copy(status = status) else it })
                setContent { ViewerTheme(false) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.SIMPLIFIED_CHINESE) } }
                onNodeWithTag("ai-session-menu-frame").performClick()
                onNodeWithTag("ai-session-delete").performClick()
                onNodeWithTag("ai-delete-dialog").assertExists()
                captureDshNode("delete-confirm-${status.name.lowercase()}", "ai-delete-dialog")
                assertTrue(fixture.events.isEmpty())
                onNodeWithTag("ai-delete-cancel").performClick()
                onNodeWithTag("ai-delete-dialog").assertDoesNotExist()
                onNodeWithTag("ai-session-frame").assertExists()
                assertTrue(fixture.events.isEmpty())
                onNodeWithTag("ai-session-menu-frame").performClick()
                onNodeWithTag("ai-session-delete").performClick()
                onNodeWithTag("ai-delete-confirm").performClick()
                assertEquals(listOf("delete:frame"), fixture.events)
                onNodeWithTag("ai-session-frame").assertDoesNotExist()
                onNodeWithTag("ai-session-battery").assertExists()
            }
        }
    }

    @Test
    fun `every failed turn has its own retry action and cannot retry while busy or unbound`() =
        runDesktopComposeUiTest(width = 900, height = 720) {
            val fixture = SurfaceFixture()
            setContent { ViewerTheme(false) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.SIMPLIFIED_CHINESE) } }
            for (status in listOf(RunStatus.RETRYABLE_FAILURE, RunStatus.PERMANENT_FAILURE, RunStatus.INTERRUPTED)) {
                runOnIdle {
                    fixture.state = fixture.state.copy(turns = listOf(turn("failed").copy(status = status, result = null)))
                }
                onNodeWithTag("ai-retry-failed").performClick()
                assertEquals("retry:failed", fixture.events.last())
                runOnIdle { fixture.state = fixture.state.copy(busy = true) }
                onNodeWithTag("ai-retry-failed").assertIsNotEnabled()
                runOnIdle { fixture.state = fixture.state.copy(busy = false, canSend = false) }
                onNodeWithTag("ai-retry-failed").assertIsNotEnabled()
                runOnIdle { fixture.state = fixture.state.copy(canSend = true) }
            }
            captureDshConversation("failure-retry-900", 900)
            runOnIdle {
                fixture.state =
                    fixture.state.copy(turns = listOf(turn("cancelled").copy(status = RunStatus.CANCELLED, result = null)))
            }
            onNodeWithTag("ai-retry-cancelled").assertDoesNotExist()
        }

    @Test
    fun `empty workspace is chat only and does not dispatch actions`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            val fixture = SurfaceFixture()
            setContent { ViewerTheme(darkTheme = false) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.ENGLISH) } }
            onNodeWithTag("ai-chat-sidebar").assertExists()
            onNodeWithTag("ai-question").assertExists()
            onNodeWithText("What would you like to explore?").assertExists()
            onNodeWithTag("ai-send").assertIsNotEnabled()
            onNodeWithTag("ai-confirm").assertDoesNotExist()
            listOf("Provider", "Agent", "Skill", "Policy").forEach { onNodeWithText(it).assertDoesNotExist() }
            assertTrue(fixture.events.isEmpty())
        }

    @Test
    fun `sidebar search selects only matching sessions and new chat is explicit`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            val fixture = SurfaceFixture()
            setContent { ViewerTheme(darkTheme = false) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.ENGLISH) } }
            onNodeWithTag("ai-chat-search").performTextReplacement("battery")
            onNodeWithTag("ai-session-frame").assertDoesNotExist()
            onNodeWithTag("ai-session-battery").performClick()
            assertEquals(listOf("select:battery"), fixture.events)
            onNodeWithTag("ai-new-chat").performClick()
            assertEquals("new", fixture.events.last())
        }

    @Test
    fun `conversation menu preserves DSH ordering and routes pin rename fork archive`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            val fixture = SurfaceFixture()
            setContent { ViewerTheme(darkTheme = false) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.ENGLISH) } }
            onNodeWithTag("ai-session-menu-frame").performClick()
            val menuTags =
                listOf(
                    "ai-session-pin",
                    "ai-session-rename",
                    "ai-session-fork",
                    "ai-session-archive",
                    "ai-session-copy-id",
                    "ai-session-delete",
                )
            menuTags.forEach { onNodeWithTag(it).assertHeightIsEqualTo(32.dp) }
            val tops = menuTags.map { onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top }
            assertEquals(tops.sorted(), tops)
            onNodeWithTag("ai-session-delete").assertExists()
            onNodeWithTag("ai-session-pin").performClick()
            assertEquals("pin:frame:true", fixture.events.last())
            onNodeWithTag("ai-session-menu-frame").performClick()
            onNodeWithTag("ai-session-rename").performClick()
            onNodeWithTag("ai-rename-title").performTextReplacement(" Renamed report ")
            onNodeWithTag("ai-rename-confirm").performClick()
            assertEquals("rename:frame:Renamed report", fixture.events.last())
            onNodeWithTag("ai-session-menu-frame").performClick()
            onNodeWithTag("ai-session-fork").performClick()
            assertEquals("fork:frame:null", fixture.events.last())
            onNodeWithTag("ai-session-menu-frame").performClick()
            onNodeWithTag("ai-session-archive").performClick()
            assertEquals("archive:frame:true", fixture.events.last())
            onNodeWithTag("ai-session-frame").assertDoesNotExist()
            onNodeWithTag("ai-show-archived").performClick()
            onNodeWithTag("ai-session-frame").assertExists()
            onNodeWithTag("ai-session-menu-frame").performClick()
            onNodeWithTag("ai-session-archive").performClick()
            assertEquals("archive:frame:false", fixture.events.last())
        }

    @Test
    fun `rename is bounded to the persisted title contract`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            val fixture = SurfaceFixture()
            setContent { ViewerTheme(darkTheme = false) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.ENGLISH) } }
            onNodeWithTag("ai-session-menu-frame").performClick()
            onNodeWithTag("ai-session-rename").performClick()
            onNodeWithTag("ai-rename-title").performTextReplacement("x".repeat(150))
            onNodeWithTag("ai-rename-confirm").performClick()
            assertEquals("rename:frame:${"x".repeat(120)}", fixture.events.single())
        }

    @Test
    fun `running archive requires confirmation and cancel leaves the session active`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            val fixture =
                SurfaceFixture().also { fixture ->
                    fixture.state =
                        fixture.state.copy(
                            sessions =
                                fixture.state.sessions.map {
                                    if (it.id ==
                                        "frame"
                                    ) {
                                        it.copy(status = RunStatus.RUNNING)
                                    } else {
                                        it
                                    }
                                },
                        )
                }
            setContent { ViewerTheme(darkTheme = false) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.ENGLISH) } }
            onNodeWithTag("ai-session-menu-frame").performClick()
            onNodeWithTag("ai-session-archive").performClick()
            assertTrue(fixture.events.isEmpty())
            onNodeWithTag("ai-archive-cancel").performClick()
            onNodeWithTag("ai-session-frame").assertExists()
            onNodeWithTag("ai-session-menu-frame").performClick()
            onNodeWithTag("ai-session-archive").performClick()
            onNodeWithTag("ai-archive-confirm").performClick()
            assertEquals(listOf("archive:frame:true"), fixture.events)
        }

    @Test
    fun `copy session ID reports success only after the clipboard write`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            val fixture = SurfaceFixture()
            setContent { ViewerTheme(darkTheme = false) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.ENGLISH) } }
            onNodeWithTag("ai-session-menu-frame").performClick()
            onNodeWithTag("ai-session-copy-id").performClick()
            waitUntil(timeoutMillis = 5000) { "copy-session:frame" in fixture.events }
            onNodeWithText("Session ID copied").assertExists()
        }

    @Test
    fun `unavailable canonical context and oversized question cannot submit from button or Enter`() =
        runDesktopComposeUiTest(width = 900, height = 720) {
            val fixture =
                SurfaceFixture().also {
                    it.state =
                        it.state.copy(contextTitle = null, canSend = false, draft = TextFieldValue("No evidence"))
                }
            setContent { ViewerTheme(darkTheme = false) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.ENGLISH) } }
            onNodeWithTag("ai-send").assertIsNotEnabled()
            onNodeWithTag("ai-question").performClick()
            onNodeWithTag("ai-question").performKeyInput { pressKey(Key.Enter) }
            assertTrue(fixture.events.isEmpty())
            runOnIdle { fixture.state = fixture.state.copy(contextTitle = "Preview only", canSend = false) }
            onNodeWithTag("ai-send").assertIsNotEnabled()
            onNodeWithTag("ai-context-unavailable").assertExists()
            captureDshConversation("send-blocked-preview-900", 900)
            onNodeWithTag("ai-question").performKeyInput { pressKey(Key.Enter) }
            assertTrue(fixture.events.isEmpty())
            runOnIdle {
                fixture.state =
                    fixture.state.copy(contextTitle = "Report", canSend = true, draft = TextFieldValue("x".repeat(4097)))
            }
            onNodeWithTag("ai-send").assertIsNotEnabled()
            onNodeWithTag("ai-question").performKeyInput { pressKey(Key.Enter) }
            assertTrue(fixture.events.isEmpty())
        }

    @Test
    fun `direct chat without a report title submits from button and Enter`() =
        runDesktopComposeUiTest(width = 900, height = 720) {
            val fixture = SurfaceFixture().also { it.state = it.state.copy(contextTitle = null, draft = TextFieldValue("你好")) }
            setContent { ViewerTheme(darkTheme = false) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.SIMPLIFIED_CHINESE) } }
            onNodeWithTag("ai-bound-context").assertDoesNotExist()
            onNodeWithTag("ai-context-unavailable").assertDoesNotExist()
            onNodeWithTag("ai-send").performClick()
            assertEquals(listOf("send:你好"), fixture.events)
            onNodeWithTag("ai-question").performTextReplacement("A followup")
            onNodeWithTag("ai-question").performKeyInput { pressKey(Key.Enter) }
            assertEquals(listOf("send:你好", "send:A followup"), fixture.events)
        }

    @Test
    fun `missing evidence exposes rebind and ordinary chat actions without sending`() =
        runDesktopComposeUiTest(width = 900, height = 720) {
            val fixture = SurfaceFixture().also { it.state = it.state.copy(canSend = false, canIncludeSource = false) }
            setContent { ViewerTheme(false) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.SIMPLIFIED_CHINESE) } }
            onNodeWithTag("ai-context-unavailable").assertTextContains("此对话的报告证据尚未绑定。")
            captureDshConversation("evidence-recovery-900", 900)
            onNodeWithTag("ai-rebind-context").performClick()
            assertEquals(listOf("context"), fixture.events)
            onNodeWithTag("ai-start-ordinary-chat").performClick()
            assertEquals(listOf("context", "new"), fixture.events)
        }

    @Test
    fun `missing source authorization exposes a separate approval action without sending`() =
        runDesktopComposeUiTest(width = 900, height = 720) {
            val fixture = SurfaceFixture().also { it.state = it.state.copy(canSend = false, canIncludeSource = true) }
            setContent { ViewerTheme(false) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.SIMPLIFIED_CHINESE) } }
            onNodeWithTag("ai-context-unavailable").assertTextContains("继续此对话前，请重新授权源码访问。")
            onNodeWithTag("ai-rebind-context").assertDoesNotExist()
            onNodeWithTag("ai-reauthorize-source").performClick()
            assertEquals(listOf("source-permission:true"), fixture.events)
        }

    @Test
    fun `Enter sends text but Shift Enter and active IME do not send`() =
        runDesktopComposeUiTest(width = 900, height = 720) {
            val fixture = SurfaceFixture()
            setContent { ViewerTheme(darkTheme = false) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.ENGLISH) } }
            val question = onNodeWithTag("ai-question")
            question.performTextReplacement("First question")
            question.performKeyInput { pressKey(Key.Enter) }
            assertEquals(listOf("send:First question"), fixture.events)
            question.performTextReplacement("Multiline")
            question.performKeyInput {
                keyDown(Key.ShiftLeft)
                pressKey(Key.Enter)
                keyUp(Key.ShiftLeft)
            }
            assertEquals(1, fixture.events.size)
            assertTrue(
                fixture.state.draft.text
                    .contains('\n'),
            )
            runOnIdle { fixture.state = fixture.state.copy(draft = TextFieldValue("拼音", composition = TextRange(0, 2))) }
            question.performKeyInput { pressKey(Key.Enter) }
            assertEquals(1, fixture.events.size)
        }

    @Test
    fun `ArrowUp edits latest text only from an empty noncomposing input`() =
        runDesktopComposeUiTest(width = 900, height = 720) {
            val fixture = SurfaceFixture().also { it.state = it.state.copy(turns = listOf(turn("one"), turn("two"))) }
            setContent { ViewerTheme(darkTheme = false) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.ENGLISH) } }
            val question = onNodeWithTag("ai-question")
            question.performClick()
            question.performKeyInput { pressKey(Key.DirectionUp) }
            assertEquals("edit:Question two", fixture.events.single())
            question.performKeyInput { pressKey(Key.DirectionUp) }
            assertEquals(1, fixture.events.size)
        }

    @Test
    fun `busy composer stops with empty draft and queues explicitly with text`() =
        runDesktopComposeUiTest(width = 900, height = 720) {
            val fixture =
                SurfaceFixture().also {
                    it.state =
                        it.state.copy(busy = true, status = RunStatus.RUNNING, queued = listOf("Follow up"))
                }
            setContent { ViewerTheme(darkTheme = true) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.ENGLISH) } }
            onNodeWithTag("ai-stop").performClick()
            onNodeWithTag("ai-edit-queued-0").performClick()
            onNodeWithTag("ai-remove-queued-0").performClick()
            onNodeWithTag("ai-question").performTextReplacement("Next question")
            onNodeWithTag("ai-send").performClick()
            assertEquals(listOf("stop", "edit-queued:0", "remove-queued:0", "send:Next question"), fixture.events)
        }

    @Test
    fun `message citations and completed turn branches invoke only host callbacks`() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val fixture = SurfaceFixture().also { it.state = it.state.copy(turns = listOf(turn("one", includeFinding = true))) }
            setContent { ViewerTheme(darkTheme = false) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.ENGLISH) } }
            onNodeWithTag("ai-finding-evidence-evidence").performClick()
            onNodeWithTag("ai-finding-source-source").performClick()
            onNodeWithTag("ai-branch-answer-one").performClick()
            onNodeWithTag("ai-copy-question-one").assertExists()
            onNodeWithTag("ai-copy-answer-one").assertExists()
            onNodeWithTag("ai-edit-question-one").performClick()
            assertEquals(listOf("evidence:evidence", "source:source", "fork:frame:one", "edit:Question one"), fixture.events)
        }

    @Test
    fun `answer copy provides checked feedback and model picker loads and selects without settings or send`() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val fixture =
                SurfaceFixture().also {
                    it.state = it.state.copy(turns = listOf(turn("one")), modelsLoading = true, models = listOf("test-model"))
                }
            setContent { ViewerTheme(darkTheme = false) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.ENGLISH) } }
            onNodeWithTag("ai-copy-answer-one").performClick()
            waitUntil(timeoutMillis = 5000) { runCatching { onNodeWithContentDescription("Copied").fetchSemanticsNode() }.isSuccess }
            onNodeWithTag("ai-model").performClick()
            onNodeWithTag("ai-chat-model-menu").assertIsDisplayed()
            onNodeWithTag("ai-chat-model-status").assertTextEquals("Loading models...")
            onNodeWithTag("ai-chat-model-retry").assertIsNotEnabled()
            onNodeWithTag("ai-chat-model-option-test-model").assertIsNotEnabled().performClick()
            onNodeWithTag("ai-chat-model-menu").assertIsDisplayed()
            assertEquals(listOf("models"), fixture.events)
            runOnIdle {
                fixture.state = fixture.state.copy(modelsLoading = false, models = listOf("test-model", "provider/actual-model-id"))
            }
            onNodeWithTag("ai-chat-model-option-test-model").assertIsSelected()
            onNodeWithTag("ai-chat-model-option-provider/actual-model-id")
                .assertTextEquals("provider/actual-model-id")
                .assertIsNotSelected()
                .performClick()
            onNodeWithTag("ai-chat-model-menu").assertDoesNotExist()
            onNodeWithTag("ai-model").assertTextContains("provider/actual-model-id")
            assertEquals(listOf("models", "model:provider/actual-model-id"), fixture.events)
        }

    @Test
    fun `model picker localizes empty loading and stable errors without exposing failure text`() {
        for (language in listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE)) {
            runDesktopComposeUiTest(width = 600, height = 720) {
                val fixture = SurfaceFixture()
                setContent { ViewerTheme(darkTheme = false) { DshChatSurface(fixture.state, fixture.actions, language) } }
                onNodeWithTag("ai-model").performClick()
                onNodeWithTag("ai-chat-model-current").assertTextEquals("test-model")
                onNodeWithTag("ai-chat-model-option-test-model").assertDoesNotExist()
                onNodeWithTag("ai-chat-model-status").assertTextEquals(dshLabel(language, "No models available", "暂无可用模型"))
                onNodeWithContentDescription(dshLabel(language, "Refresh models", "刷新模型")).assertExists()
                onNodeWithTag("ai-chat-model-retry").performClick()
                onNodeWithTag("ai-chat-model-menu").assertIsDisplayed()
                assertEquals(listOf("models", "models"), fixture.events)
                val failures =
                    listOf(
                        "E_AI_MODEL_CATALOG_INVALID_ENDPOINT" to dshLabel(language, "Model endpoint is unavailable", "模型接口不可用"),
                        "E_AI_MODEL_CATALOG_INVALID_RESPONSE" to dshLabel(language, "Model list is unavailable", "模型列表不可用"),
                        "E_AI_MODEL_CATALOG_HTML_RESPONSE" to dshLabel(language, "Model list is unavailable", "模型列表不可用"),
                        "E_UNKNOWN_MODEL_FAILURE" to dshLabel(language, "Unable to load models", "无法加载模型"),
                        "private raw response with credential=not-for-display" to dshLabel(language, "Unable to load models", "无法加载模型"),
                    )
                for ((code, label) in failures) {
                    runOnIdle { fixture.state = fixture.state.copy(modelsError = code) }
                    onNodeWithTag("ai-chat-model-status").assertTextEquals(label)
                    onNodeWithText(code, substring = true).assertDoesNotExist()
                    onNodeWithTag("ai-chat-model-option-test-model").assertDoesNotExist()
                }
                runOnIdle { fixture.state = fixture.state.copy(modelsLoading = true) }
                onNodeWithTag("ai-chat-model-status").assertTextEquals(dshLabel(language, "Loading models...", "正在加载模型…"))
                onNodeWithTag("ai-chat-model-retry").assertIsNotEnabled()
                runOnIdle {
                    fixture.state = fixture.state.copy(modelsLoading = false, modelsError = null, models = listOf("other/actual-id"))
                }
                onNodeWithTag("ai-chat-model-current").assertTextEquals("test-model")
                onNodeWithTag("ai-chat-model-option-test-model").assertDoesNotExist()
                onNodeWithTag("ai-chat-model-option-other/actual-id").assertTextEquals("other/actual-id").assertIsNotSelected()
                runOnIdle { fixture.state = fixture.state.copy(model = "", models = emptyList()) }
                onNodeWithTag("ai-chat-model-current").assertTextEquals(dshLabel(language, "Choose model", "选择模型"))
                onNodeWithTag("ai-chat-model-option-").assertDoesNotExist()
                onNodeWithTag("ai-chat-model-retry").performClick()
                assertEquals(listOf("models", "models", "models"), fixture.events)
            }
        }
    }

    @Test
    fun `model picker disables while busy and never changes pending run model`() =
        runDesktopComposeUiTest(width = 900, height = 720) {
            val approval =
                PreparedAgentRun(
                    ApprovalRequest("approval", "run", "session", 1, "a".repeat(64), setOf(Permission.READ_EVIDENCE), Long.MAX_VALUE),
                    evidenceCount = 1,
                    candidateCount = 0,
                    payloadBytes = 128,
                    providerId = "openai",
                    model = "frozen-model",
                )
            val fixture =
                SurfaceFixture().also {
                    it.state =
                        it.state.copy(
                            busy = true,
                            pendingApproval = approval,
                            status = RunStatus.AWAITING_APPROVAL,
                            models = listOf("test-model", "next-model"),
                        )
                }
            setContent { ViewerTheme(darkTheme = true) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.ENGLISH) } }
            onNodeWithTag("ai-model").assertIsNotEnabled().performClick()
            onNodeWithTag("ai-chat-model-menu").assertDoesNotExist()
            assertTrue(fixture.events.isEmpty())
            runOnIdle { fixture.state = fixture.state.copy(busy = false) }
            onNodeWithTag("ai-model").performClick()
            onNodeWithTag("ai-chat-model-option-next-model").performClick()
            assertEquals(listOf("models", "model:next-model"), fixture.events)
            assertEquals(approval, fixture.state.pendingApproval)
            assertEquals("frozen-model", fixture.state.pendingApproval?.model)
            onNodeWithTag("ai-model").performClick()
            runOnIdle { fixture.state = fixture.state.copy(busy = true) }
            onNodeWithTag("ai-model").assertIsNotEnabled()
            onNodeWithTag("ai-chat-model-menu").assertDoesNotExist()
            runOnIdle { fixture.state = fixture.state.copy(busy = false) }
            onNodeWithTag("ai-chat-model-menu").assertDoesNotExist()
            assertEquals(listOf("models", "model:next-model", "models"), fixture.events)
        }

    @Test
    fun `model picker fits compact and long IDs in English Chinese light and dark layouts`() {
        val longId = "provider/" + "long-model-id-".repeat(16)
        for (width in listOf(320, 900)) {
            for (language in listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE)) {
                for (dark in listOf(false, true)) {
                    runDesktopComposeUiTest(width = width, height = 720) {
                        val models = listOf(longId, "other/actual-id") + (1..20).map { "provider/model-$it" }
                        val fixture = SurfaceFixture().also { it.state = it.state.copy(model = longId, models = models + longId) }
                        setContent { ViewerTheme(darkTheme = dark) { DshChatSurface(fixture.state, fixture.actions, language) } }
                        onNodeWithTag("ai-model").performClick()
                        onNodeWithTag("ai-chat-model-current").assertTextEquals(longId)
                        onNodeWithTag("ai-chat-model-option-$longId").assertIsSelected().assertTextEquals(longId)
                        assertEquals(1, onAllNodesWithTag("ai-chat-model-option-$longId").fetchSemanticsNodes().size)
                        val menu = onNodeWithTag("ai-chat-model-menu").getUnclippedBoundsInRoot()
                        val menuWidth = menu.right - menu.left
                        assertTrue(menuWidth <= 480.dp && menuWidth <= (width - 32).dp)
                        assertTrue(menu.bottom - menu.top <= 320.dp)
                        val suffix = "$width-${language.name}-${if (dark) "dark" else "light"}"
                        captureDshNode("model-picker-$suffix", "ai-chat-model-menu")
                        onNodeWithTag("ai-chat-model-option-provider/model-20").performScrollTo().assertIsDisplayed().performClick()
                        onNodeWithTag("ai-model").assertTextContains("provider/model-20")
                        onNodeWithTag("ai-chat-model-menu").assertDoesNotExist()
                        assertEquals(listOf("models", "model:provider/model-20"), fixture.events)
                    }
                }
            }
        }
    }

    @Test
    fun `approval shows immutable request counts and never auto approves`() =
        runDesktopComposeUiTest(width = 900, height = 720) {
            val approval =
                PreparedAgentRun(
                    ApprovalRequest("approval", "run", "session", 1, "a".repeat(64), setOf(Permission.READ_EVIDENCE), Long.MAX_VALUE),
                    evidenceCount = 2,
                    candidateCount = 1,
                    payloadBytes = 512,
                    providerId = "openai",
                    model = "frozen-model",
                    plannedProviderCallCount = 3,
                    plannedScriptCount = 1,
                )
            val fixture =
                SurfaceFixture().also {
                    it.state =
                        it.state.copy(
                            pendingApproval = approval,
                            status = RunStatus.AWAITING_APPROVAL,
                        )
                }
            setContent { ViewerTheme(darkTheme = false) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.ENGLISH) } }
            onNodeWithTag("ai-planned-invocations").assertTextContains("Model requests: 3 · Scripts: 1")
            assertTrue(fixture.events.isEmpty())
            onNodeWithTag("ai-confirm").performClick()
            onNodeWithTag("ai-cancel-preflight").performClick()
            assertEquals(listOf("approve", "stop"), fixture.events)
        }

    @Test
    fun `fitting transcript has neither navigation button`() =
        runDesktopComposeUiTest(width = 900, height = 720) {
            val fixture = SurfaceFixture().also { it.state = it.state.copy(turns = listOf(turn("short"))) }
            setContent { ViewerTheme(darkTheme = false) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.ENGLISH) } }
            assertScrollNavigation(canScrollBackward = false, canScrollForward = false)
            onNodeWithTag("ai-user-short").assertIsDisplayed()
            onNodeWithTag("ai-copy-answer-short").assertIsDisplayed()
            captureDshConversation("short-transcript-900", 900)
            assertTrue(fixture.events.isEmpty())
        }

    @Test
    fun `reader navigates top middle and bottom without new answers stealing scroll`() {
        for (width in listOf(900, 1280)) {
            for (language in listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE)) {
                for (dark in listOf(false, true)) {
                    runDesktopComposeUiTest(width = width, height = 720) {
                        val fixture = SurfaceFixture().also { it.state = it.state.copy(turns = (1..20).map { turn("turn-$it") }) }
                        setContent { ViewerTheme(darkTheme = dark) { DshChatSurface(fixture.state, fixture.actions, language) } }
                        val suffix = "$width-${language.name}-${if (dark) "dark" else "light"}"
                        assertScrollNavigation(canScrollBackward = true, canScrollForward = false)
                        onNodeWithTag("ai-scroll-top").performClick()
                        assertScrollNavigation(canScrollBackward = false, canScrollForward = true)
                        onNodeWithTag("ai-user-turn-1").assertIsDisplayed()
                        captureDshConversation("navigation-top-$suffix", width, listOf("ai-scroll-bottom"))
                        val start = onNodeWithTag("ai-user-turn-1").getUnclippedBoundsInRoot()
                        runOnIdle { fixture.state = fixture.state.copy(turns = fixture.state.turns + turn("new-top")) }
                        assertScrollNavigation(canScrollBackward = false, canScrollForward = true)
                        assertEquals(start, onNodeWithTag("ai-user-turn-1").getUnclippedBoundsInRoot())

                        onNodeWithTag("ai-chat-history").performScrollToIndex(10)
                        assertScrollNavigation(canScrollBackward = true, canScrollForward = true)
                        captureDshConversation("navigation-middle-$suffix", width, listOf("ai-scroll-top", "ai-scroll-bottom"))
                        val reading = onNodeWithTag("ai-user-turn-11").getUnclippedBoundsInRoot()
                        runOnIdle { fixture.state = fixture.state.copy(turns = fixture.state.turns + turn("new-middle")) }
                        assertScrollNavigation(canScrollBackward = true, canScrollForward = true)
                        assertEquals(reading, onNodeWithTag("ai-user-turn-11").getUnclippedBoundsInRoot())

                        onNodeWithTag("ai-scroll-bottom").performClick()
                        assertScrollNavigation(canScrollBackward = true, canScrollForward = false)
                        onNodeWithTag("ai-copy-answer-new-middle").assertIsDisplayed()
                        runOnIdle { fixture.state = fixture.state.copy(turns = fixture.state.turns + turn("new-latest")) }
                        assertScrollNavigation(canScrollBackward = true, canScrollForward = false)
                        onNodeWithTag("ai-copy-answer-new-latest").assertIsDisplayed()
                        captureDshConversation("navigation-bottom-$suffix", width, listOf("ai-scroll-top"))
                        assertTrue(fixture.events.isEmpty(), "Scroll navigation must not dispatch host actions")
                    }
                }
            }
        }
    }

    @Test
    fun `bottom navigation reaches the true end of a single oversized answer`() =
        runDesktopComposeUiTest(width = 900, height = 720) {
            val fixture =
                SurfaceFixture().also {
                    it.state = it.state.copy(turns = listOf(turn("long", summary = (1..100).joinToString("  \n") { "Answer line $it" })))
                }
            setContent { ViewerTheme(darkTheme = false) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.ENGLISH) } }
            assertScrollNavigation(canScrollBackward = true, canScrollForward = false)
            onNodeWithTag("ai-copy-answer-long").assertIsDisplayed()
            onNodeWithTag("ai-scroll-top").performClick()
            assertScrollNavigation(canScrollBackward = false, canScrollForward = true)
            onNodeWithTag("ai-user-long").assertIsDisplayed()
            val viewport = onNodeWithTag("ai-chat-messages").fetchSemanticsNode().boundsInRoot
            val viewportBounds = onNodeWithTag("ai-chat-messages").getUnclippedBoundsInRoot()
            val answerBounds = onNodeWithTag("ai-summary-long").getUnclippedBoundsInRoot()
            assertTrue((answerBounds.bottom - answerBounds.top) > (viewportBounds.bottom - viewportBounds.top) * 3f)
            onNodeWithTag("ai-chat-history").performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                assertTrue(scroll(0f, viewport.height / 2f))
            }
            assertScrollNavigation(canScrollBackward = true, canScrollForward = true)
            onNodeWithTag("ai-scroll-bottom").performClick()
            assertScrollNavigation(canScrollBackward = true, canScrollForward = false)
            onNodeWithTag("ai-copy-answer-long").assertIsDisplayed()
            captureDshConversation("navigation-long-answer-900", 900, listOf("ai-scroll-top"))
            assertTrue(fixture.events.isEmpty())
        }

    @Test
    fun `narrow sidebar toggles without hiding the composer or sending`() =
        runDesktopComposeUiTest(width = 600, height = 720) {
            val fixture = SurfaceFixture()
            setContent { ViewerTheme(darkTheme = false) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.ENGLISH) } }
            onNodeWithTag("ai-chat-sidebar").assertDoesNotExist()
            onNodeWithTag("ai-sidebar-open").performClick()
            onNodeWithTag("ai-chat-sidebar").assertExists()
            onNodeWithTag("ai-sidebar-close").performClick()
            onNodeWithTag("ai-chat-sidebar").assertDoesNotExist()
            onNodeWithTag("ai-composer").assertExists()
            assertTrue(fixture.events.isEmpty())
        }

    @Test
    fun `English Chinese light dark narrow wide chat layouts paint opaque screenshots without actions`() {
        for (width in listOf(600, 900, 1280)) {
            for (language in listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE)) {
                for (dark in listOf(false, true)) {
                    runDesktopComposeUiTest(width = width, height = 720) {
                        val fixture = SurfaceFixture()
                        setContent { ViewerTheme(darkTheme = dark) { DshChatSurface(fixture.state, fixture.actions, language) } }
                        val question = onNodeWithTag("ai-question").fetchSemanticsNode().boundsInRoot
                        val composer = onNodeWithTag("ai-composer").fetchSemanticsNode().boundsInRoot
                        val send = onNodeWithTag("ai-send").fetchSemanticsNode().boundsInRoot
                        val model = onNodeWithTag("ai-model").fetchSemanticsNode().boundsInRoot
                        onNodeWithTag("ai-send").assertWidthIsEqualTo(28.dp).assertHeightIsEqualTo(28.dp)
                        assertTrue(question.width > 0f && question.height >= 44f)
                        assertTrue(composer.left >= 0f && composer.right <= width + 1f)
                        assertTrue(composer.bottom <= 720f)
                        assertEquals(
                            12f,
                            composer.right - send.right,
                            absoluteTolerance = 1.5f,
                            message = "Send button stays at the composer trailing inset",
                        )
                        assertEquals(4f, send.left - model.right, absoluteTolerance = 1.5f)
                        assertEquals(send.center.y, model.center.y, absoluteTolerance = 1f)
                        val image = onNodeWithTag("ai-workspace").captureToImage().toPixelMap()
                        assertEquals(1f, image[image.width - 1, 0].alpha)
                        val output = Path.of("build/reports/dsh-chat/$width-${language.name}-${if (dark) "dark" else "light"}.png")
                        Files.createDirectories(output.parent)
                        val screenshot = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                        for (y in 0 until image.height) for (x in 0 until image.width) screenshot.setRGB(x, y, image[x, y].toArgb())
                        assertTrue(ImageIO.write(screenshot, "png", output.toFile()))
                        for (sidebarExpanded in listOf(width >= 1000, width < 1000)) {
                            onNodeWithTag("ai-settings").assertDoesNotExist()
                            onNodeWithTag("ai-new-chat").assertExists()
                            onNodeWithTag("ai-model").assertExists()
                            onNodeWithTag("ai-composer").assertExists()
                            if (sidebarExpanded) {
                                onNodeWithTag("ai-chat-sidebar").assertExists()
                                onNodeWithTag("ai-show-archived").assertExists()
                                onNodeWithTag("ai-sidebar-open").assertDoesNotExist()
                                onNodeWithTag("ai-sidebar-close").performClick()
                            } else {
                                onNodeWithTag("ai-chat-sidebar").assertDoesNotExist()
                                onNodeWithTag("ai-show-archived").assertDoesNotExist()
                                onNodeWithTag("ai-sidebar-open").performClick()
                            }
                            assertTrue(fixture.events.isEmpty(), "Rendering and sidebar toggles must not dispatch chat actions")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `send and stop stay rightmost with long and empty model labels on their left`() {
        for (width in listOf(320, 600, 900, 1280)) {
            runDesktopComposeUiTest(width = width, height = 720) {
                val fixture = SurfaceFixture().also { it.state = it.state.copy(model = "long-provider-model-name-".repeat(20)) }
                setContent { ViewerTheme(darkTheme = false) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.ENGLISH) } }
                val composer = onNodeWithTag("ai-composer").fetchSemanticsNode().boundsInRoot
                val model = onNodeWithTag("ai-model").fetchSemanticsNode().boundsInRoot
                val send = onNodeWithTag("ai-send").fetchSemanticsNode().boundsInRoot
                onNodeWithTag("ai-send").assertWidthIsEqualTo(28.dp).assertHeightIsEqualTo(28.dp)
                assertTrue(model.width <= 240f + 1f)
                assertEquals(12f, composer.right - send.right, absoluteTolerance = 1.5f)
                assertEquals(4f, send.left - model.right, absoluteTolerance = 1.5f)
                assertTrue(model.left >= composer.left)
                runOnIdle { fixture.state = fixture.state.copy(busy = true, status = RunStatus.RUNNING) }
                onNodeWithTag("ai-stop").assertWidthIsEqualTo(28.dp).assertHeightIsEqualTo(28.dp)
                val stop = onNodeWithTag("ai-stop").fetchSemanticsNode().boundsInRoot
                val runningModel = onNodeWithTag("ai-model").fetchSemanticsNode().boundsInRoot
                assertEquals(4f, stop.left - runningModel.right, absoluteTolerance = 1.5f)
                assertEquals(send, stop)
                runOnIdle { fixture.state = fixture.state.copy(model = "") }
                onNodeWithText("Choose model").assertExists()
                val emptyModel = onNodeWithTag("ai-model").fetchSemanticsNode().boundsInRoot
                val currentStop = onNodeWithTag("ai-stop").fetchSemanticsNode().boundsInRoot
                assertEquals(4f, currentStop.left - emptyModel.right, absoluteTolerance = 1.5f)
                assertEquals(12f, composer.right - currentStop.right, absoluteTolerance = 1.5f)
                assertEquals(stop, currentStop)
                assertTrue(fixture.events.isEmpty())
            }
        }
    }

    @Test
    fun `populated conversation and pending preflight stay scrollable above the composer in both reference layouts`() {
        val layouts = listOf(Triple(1280, UiLanguage.ENGLISH, true), Triple(900, UiLanguage.SIMPLIFIED_CHINESE, false))
        for ((width, language, dark) in layouts) {
            runDesktopComposeUiTest(width = width, height = 720) {
                val chinese = language == UiLanguage.SIMPLIFIED_CHINESE
                val question = if (chinese) "这帧为什么超时？" else "Why did this frame miss its deadline?"
                val summary = if (chinese) "本帧耗时 **24ms**。" else "This frame took **24ms**."
                val finding =
                    AnalysisFinding(
                        AnalysisFindingId("finding"),
                        AnalysisSeverity.WARNING,
                        if (chinese) "检查渲染工作" else "Inspect render work",
                        if (chinese) "查看已选中的帧与源码候选。" else "Review the selected frame and source candidate.",
                        "",
                        0.9f,
                        listOf("evidence"),
                        listOf("source"),
                    )
                val result =
                    AnalysisResult(
                        AnalysisSessionId("session"),
                        "test-model",
                        "$summary\n```kotlin\nrenderFrame()\n```",
                        listOf(finding),
                    )
                val fixture =
                    SurfaceFixture().also {
                        it.state =
                            it.state.copy(
                                contextTitle = if (chinese) "帧选择 · 24ms" else "Frame selection · 24ms",
                                turns = listOf(ChatTurn("populated", 1, RunStatus.SUCCEEDED, question, result, 1, emptyList())),
                            )
                    }
                setContent { ViewerTheme(darkTheme = dark) { DshChatSurface(fixture.state, fixture.actions, language) } }
                onNodeWithTag("ai-chat-history").performScrollToIndex(0)
                onNodeWithTag("ai-user-populated").assertExists()
                onNodeWithTag("ai-assistant-populated").assertExists()
                onNodeWithTag("ai-summary-populated-block-1-code-copy").assertExists()
                onNodeWithTag("ai-finding-evidence-evidence").assertExists()
                onNodeWithTag("ai-finding-source-source").assertExists()
                val suffix = "$width-${language.name}-${if (dark) "dark" else "light"}"
                captureDshConversation("populated-$suffix", width)

                val preview =
                    AnalysisRequest(
                        AnalysisSessionId("session"),
                        ProfilerKind.FRAME,
                        AnalysisScope(AnalysisScopeKind.CURRENT_SELECTION, "Frame selection"),
                        listOf(PerformanceEvidence("evidence", "frame", "Frame duration 24ms", "{}")),
                        listOf(AiSourceCandidate("source", "Render.kt", "renderFrame", "EXACT", emptyList(), null)),
                        "test-prompt",
                        "test-policy",
                        userQuestion = if (chinese) "接下来应该检查哪里？" else "What should I inspect next?",
                        conversationContext = listOf(AiConversationContextTurn("populated", question, summary)),
                    )
                val pending =
                    PreparedAgentRun(
                        ApprovalRequest("approval", "next", "session", 2, "a".repeat(64), setOf(Permission.READ_EVIDENCE), Long.MAX_VALUE),
                        evidenceCount = 1,
                        candidateCount = 1,
                        payloadBytes = 1024,
                        providerId = "openai",
                        model = "frozen-model",
                        analysisPreview = preview,
                        plannedProviderCallCount = 1,
                    )
                runOnIdle {
                    fixture.state =
                        fixture.state.copy(
                            pendingApproval = pending,
                            status = RunStatus.AWAITING_APPROVAL,
                            busy = true,
                        )
                }
                onNodeWithTag("ai-chat-history").performScrollToIndex(1)
                onNodeWithTag("ai-preflight-evidence-evidence").assertExists()
                onNodeWithTag("ai-preflight-source-source").assertExists()
                onNodeWithTag("ai-preflight-history-toggle").assertExists()
                onNodeWithTag("ai-confirm").assertExists()
                onNodeWithTag("ai-cancel-preflight").assertExists()
                captureDshConversation("preflight-$suffix", width)
                assertTrue(fixture.events.isEmpty(), "Rendering content and preflight must not send or approve")
            }
        }
    }

    @Test
    fun `double Escape gesture rejects held repeats and expires after half second`() {
        val gesture = DshStopGesture()
        assertFalse(gesture.press(100))
        assertFalse(gesture.press(101))
        gesture.release()
        assertTrue(gesture.press(500))
        gesture.release()
        assertFalse(gesture.press(600))
        gesture.release()
        assertFalse(gesture.press(1200))
        gesture.release()
        gesture.reset()
        assertFalse(gesture.press(1201))
    }

    @Test
    fun `chat column uses DSH width limits while fitting small containers`() {
        assertEquals(680.dp, DshChatStyle.chatWidth(900.dp))
        assertEquals(819.2f, DshChatStyle.chatWidth(1280.dp).value, absoluteTolerance = 0.001f)
        assertEquals(920.dp, DshChatStyle.chatWidth(1800.dp))
        assertEquals(252.dp, DshChatStyle.chatWidth(300.dp))
        assertEquals(284.dp, DshChatStyle.composerWidth(300.dp))
        assertEquals(0.dp, DshChatStyle.chatWidth(20.dp))
    }

    @Test
    fun `Markdown answer renders semantic heading and literal unsafe links without actions`() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val sourceTurn = turn("markdown")
            val markdown = "## Findings\n- **Hot** path\nInspect `dispatch()`\n[secret](file:///private/secret)"
            val fixture =
                SurfaceFixture().also {
                    it.state =
                        it.state.copy(
                            turns =
                                listOf(
                                    sourceTurn.copy(
                                        result =
                                            sourceTurn.result!!.copy(
                                                summary = markdown,
                                            ),
                                    ),
                                ),
                        )
                }
            setContent { ViewerTheme(darkTheme = false) { DshChatSurface(fixture.state, fixture.actions, UiLanguage.ENGLISH) } }
            onNodeWithText("Findings").assertExists()
            onNodeWithText("Hot path", substring = true).assertExists()
            onNodeWithText("Inspect dispatch()", substring = true).assertExists()
            onNodeWithText("[secret](file:///private/secret)", substring = true).assertExists()
            assertTrue(fixture.events.isEmpty())
        }
}

@OptIn(ExperimentalTestApi::class)
private fun DesktopComposeUiTest.assertScrollNavigation(
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
) {
    waitUntil(timeoutMillis = 5000) {
        val range = onNodeWithTag("ai-chat-history").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        (range.value() > 0f) == canScrollBackward && (range.value() < range.maxValue()) == canScrollForward
    }
    val visibility =
        listOf(
            "ai-scroll-controls" to (canScrollBackward || canScrollForward),
            "ai-scroll-top" to canScrollBackward,
            "ai-scroll-bottom" to canScrollForward,
        )
    for ((tag, visible) in visibility) {
        if (visible) onNodeWithTag(tag).assertIsDisplayed() else onNodeWithTag(tag).assertDoesNotExist()
    }
}

@OptIn(ExperimentalTestApi::class)
private fun DesktopComposeUiTest.captureDshConversation(
    name: String,
    width: Int,
    navigation: List<String> = emptyList(),
) {
    val composer = onNodeWithTag("ai-composer").fetchSemanticsNode().boundsInRoot
    val messages = onNodeWithTag("ai-chat-messages").fetchSemanticsNode().boundsInRoot
    val history = onNodeWithTag("ai-chat-history").fetchSemanticsNode().boundsInRoot
    val workspace = onNodeWithTag("ai-workspace").fetchSemanticsNode().boundsInRoot
    val sidebar = onAllNodesWithTag("ai-chat-sidebar").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
    val question = onNodeWithTag("ai-question").fetchSemanticsNode().boundsInRoot
    val viewportLeft = if (sidebar == null) workspace.left else workspace.left + DshChatStyle.sidebarWidth.value
    assertEquals(viewportLeft, messages.left, absoluteTolerance = 1f)
    assertEquals(workspace.right, messages.right, absoluteTolerance = 1f)
    assertTrue(history.width < messages.width)
    assertEquals(messages.center.x, history.center.x, absoluteTolerance = 1f)
    assertTrue(composer.left >= 0f && composer.right <= width + 1f)
    assertTrue(composer.bottom <= 720f && composer.top >= messages.bottom - 1f)
    assertTrue(question.height >= 44f && question.bottom <= composer.bottom)
    if (navigation.isNotEmpty()) {
        val viewport = onNodeWithTag("ai-chat-messages").getUnclippedBoundsInRoot()
        val controls = onNodeWithTag("ai-scroll-controls").getUnclippedBoundsInRoot()
        assertTrue(controls.top >= viewport.top && controls.bottom <= viewport.bottom)
        assertTrue(controls.bottom <= onNodeWithTag("ai-composer").getUnclippedBoundsInRoot().top)
        assertTrue(controls.bottom <= onNodeWithTag("ai-question").getUnclippedBoundsInRoot().top)
        val historyRight = onNodeWithTag("ai-chat-history").getUnclippedBoundsInRoot().right
        val buttons =
            navigation.map { tag ->
                onNodeWithTag(tag).assertWidthIsEqualTo(32.dp).assertHeightIsEqualTo(32.dp).getUnclippedBoundsInRoot()
            }
        assertTrue(buttons.all { it.left >= historyRight }, "Navigation stays outside the message text column")
        buttons.forEach { assertEquals(12f, (viewport.right - it.right).value, absoluteTolerance = 1f) }
        assertEquals(12f, (viewport.bottom - buttons.last().bottom).value, absoluteTolerance = 1f)
        buttons.zipWithNext { top, bottom -> assertEquals(6f, (bottom.top - top.bottom).value, absoluteTolerance = 1f) }
    }
    captureDshNode(name, "ai-workspace")
}

@OptIn(ExperimentalTestApi::class)
private fun DesktopComposeUiTest.captureDshNode(
    name: String,
    tag: String,
) {
    val image = onNodeWithTag(tag).captureToImage().toPixelMap()
    val screenshot = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until image.height) for (x in 0 until image.width) screenshot.setRGB(x, y, image[x, y].toArgb())
    val output = Path.of("build/reports/dsh-chat/$name.png")
    Files.createDirectories(output.parent)
    assertTrue(ImageIO.write(screenshot, "png", output.toFile()))
}

private class SurfaceFixture {
    val events = mutableListOf<String>()
    var state by mutableStateOf(
        ChatSurfaceState(
            sessions =
                listOf(
                    ChatSessionSummary("frame", "Frame timing", 2, false, 1, RunStatus.SUCCEEDED),
                    ChatSessionSummary("battery", "Battery usage", 1, true, 1, RunStatus.SUCCEEDED),
                ),
            selectedConversationId = "frame",
            turns = emptyList(),
            draft = TextFieldValue(),
            contextTitle = "Frame selection",
            canSend = true,
            model = "test-model",
            busy = false,
            status = null,
            errorCode = null,
            pendingApproval = null,
            timeline = emptyList(),
            includeSource = false,
            canIncludeSource = true,
        ),
    )
    val actions =
        ChatSurfaceActions(
            onNew = { events += "new" },
            onSelect = { events += "select:$it" },
            onSend = {
                events += "send:${state.draft.text}"
                state = state.copy(draft = TextFieldValue())
            },
            onStop = { events += "stop" },
            onApprove = { events += "approve" },
            onDraft = { state = state.copy(draft = it) },
            onRename = { id, title -> events += "rename:$id:$title" },
            onPin = { id, pinned -> events += "pin:$id:$pinned" },
            onArchive = { id, archived ->
                events += "archive:$id:$archived"
                state = state.copy(sessions = state.sessions.map { if (it.id == id) it.copy(archived = archived) else it })
            },
            onDelete = { id ->
                events += "delete:$id"
                state = state.copy(sessions = state.sessions.filterNot { it.id == id })
            },
            onFork = { id, run -> events += "fork:$id:$run" },
            onCopySession = { events += "copy-session:$it" },
            onEditQuestion = {
                events += "edit:$it"
                state = state.copy(draft = TextFieldValue(it))
            },
            onRetry = { events += "retry" },
            onRetryTurn = { events += "retry:$it" },
            onOpenSettings = { events += "settings" },
            onBindContext = { events += "context" },
            onIncludeSource = { events += "source-permission:$it" },
            onEvidence = { events += "evidence:$it" },
            onSource = { events += "source:$it" },
            onArtifact = { events += "artifact:$it" },
            onRemoveQueued = { events += "remove-queued:$it" },
            onEditQueued = { events += "edit-queued:$it" },
            onShowArchived = { state = state.copy(showingArchived = it) },
            onOpenModels = { events += "models" },
            onSelectModel = {
                events += "model:$it"
                state = state.copy(model = it)
            },
        )
}

private fun turn(
    runId: String,
    includeFinding: Boolean = false,
    summary: String = "Verified summary $runId",
): ChatTurn =
    ChatTurn(
        runId,
        generation = 1,
        status = RunStatus.SUCCEEDED,
        question = "Question $runId",
        result =
            AnalysisResult(
                AnalysisSessionId("session"),
                "test-model",
                summary,
                if (includeFinding) {
                    listOf(
                        AnalysisFinding(
                            AnalysisFindingId("finding"),
                            AnalysisSeverity.INFO,
                            "Finding",
                            "Explanation",
                            "Recommendation",
                            1f,
                            listOf("evidence"),
                            listOf("source"),
                        ),
                    )
                } else {
                    emptyList()
                },
            ),
        timestampEpochMillis = 1,
        artifactHashes = emptyList(),
    )
