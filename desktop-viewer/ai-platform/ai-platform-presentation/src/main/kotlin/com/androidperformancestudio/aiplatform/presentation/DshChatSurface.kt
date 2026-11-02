@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("FunctionNaming", "LongParameterList", "ktlint:standard:function-naming")

package com.androidperformancestudio.aiplatform.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.aiplatform.model.RunStatus
import com.androidperformancestudio.aiplatform.runtime.ChatSessionSummary
import com.androidperformancestudio.aiplatform.runtime.ChatTurn
import com.androidperformancestudio.aiplatform.runtime.PreparedAgentRun
import com.androidperformancestudio.aiplatform.storage.ExecutionAuditEvent
import com.androidperformancestudio.ui.UiLanguage

internal data class ChatSurfaceState(
    val sessions: List<ChatSessionSummary>,
    val selectedConversationId: String?,
    val turns: List<ChatTurn>,
    val draft: TextFieldValue,
    val contextTitle: String?,
    val canSend: Boolean,
    val model: String,
    val busy: Boolean,
    val status: RunStatus?,
    val errorCode: String?,
    val pendingApproval: PreparedAgentRun?,
    val timeline: List<ExecutionAuditEvent>,
    val includeSource: Boolean,
    val canIncludeSource: Boolean,
    val queued: List<String> = emptyList(),
    val showingArchived: Boolean = false,
    val models: List<String> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelsError: String? = null,
)

internal class ChatSurfaceActions(
    val onNew: () -> Unit,
    val onSelect: (String) -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val onApprove: () -> Unit,
    val onDraft: (TextFieldValue) -> Unit,
    val onRename: (String, String) -> Unit,
    val onPin: (String, Boolean) -> Unit,
    val onArchive: (String, Boolean) -> Unit,
    val onFork: (String, String?) -> Unit,
    val onCopySession: (String) -> Unit,
    val onEditQuestion: (String) -> Unit,
    val onRetry: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onBindContext: () -> Unit,
    val onIncludeSource: (Boolean) -> Unit,
    val onEvidence: (String) -> Unit,
    val onSource: (String) -> Unit,
    val onArtifact: (String) -> Unit,
    val onRemoveQueued: (Int) -> Unit,
    val onEditQueued: (Int) -> Unit,
    val onShowArchived: (Boolean) -> Unit,
    val onOpenModels: () -> Unit = {},
    val onSelectModel: (String) -> Unit = {},
    val onRefreshModels: () -> Unit = onOpenModels,
    val onCloseModels: () -> Unit = {},
)

/** Pure presentation: all execution, context authority and persistence stay with the host. */
@Composable
internal fun DshChatSurface(
    state: ChatSurfaceState,
    actions: ChatSurfaceActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val colors = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) DshChatColors.Dark else DshChatColors.Light
    val searchFocus = remember { FocusRequester() }
    var searchRequest by remember { mutableStateOf(0) }
    val stopGesture = remember(state.selectedConversationId, state.busy, state.pendingApproval?.approval?.id) { DshStopGesture() }
    CompositionLocalProvider(
        LocalDshChatColors provides colors,
        LocalContentColor provides colors.text,
        LocalTextStyle provides DshChatStyle.body,
    ) {
        BoxWithConstraints(modifier.fillMaxSize().background(colors.background).testTag("ai-workspace")) {
            val availableWidth = maxWidth
            val wide = availableWidth >= 1000.dp
            var sidebarVisible by remember(wide) { mutableStateOf(wide) }
            LaunchedEffect(sidebarVisible, searchRequest) {
                if (sidebarVisible && searchRequest > 0) searchFocus.requestFocus()
            }
            Box(
                Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                    if (event.key == Key.Escape && event.type == KeyEventType.KeyUp) {
                        stopGesture.release()
                        false
                    } else if (event.type != KeyEventType.KeyDown) {
                        false
                    } else if (state.draft.composition != null || event.key == Key.Escape && state.pendingApproval != null) {
                        stopGesture.reset()
                        false
                    } else if ((event.isCtrlPressed || event.isMetaPressed) && event.key == Key.N) {
                        actions.onNew()
                        true
                    } else if ((event.isCtrlPressed || event.isMetaPressed) && event.key == Key.K) {
                        sidebarVisible = true
                        searchRequest++
                        true
                    } else if (event.key == Key.Escape && state.busy) {
                        if (stopGesture.press(System.nanoTime() / 1_000_000)) actions.onStop()
                        true
                    } else {
                        stopGesture.reset()
                        false
                    }
                },
            ) {
                Row(Modifier.fillMaxSize()) {
                    if (sidebarVisible && availableWidth >= 640.dp) {
                        DshSidebar(state, actions, language, searchFocus, onClose = { sidebarVisible = false })
                    }
                    DshConversation(
                        state,
                        actions,
                        language,
                        sidebarVisible,
                        onToggleSidebar = { sidebarVisible = !sidebarVisible },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (sidebarVisible && availableWidth < 640.dp) {
                    DshSidebar(state, actions, language, searchFocus, onClose = { sidebarVisible = false })
                }
            }
        }
    }
}

internal fun dshLabel(
    language: UiLanguage,
    en: String,
    zh: String,
): String = if (language == UiLanguage.SIMPLIFIED_CHINESE) zh else en

/** Ignore held-key repeats; two separate Escape presses must fit within 500 ms. */
internal class DshStopGesture {
    private var previous: Long? = null
    private var held = false

    fun press(nowMillis: Long): Boolean {
        if (held) return false
        held = true
        val stop = previous?.let { nowMillis - it in 0..500 } == true
        previous = if (stop) null else nowMillis
        return stop
    }

    fun release() {
        held = false
    }

    fun reset() {
        previous = null
        held = false
    }
}
