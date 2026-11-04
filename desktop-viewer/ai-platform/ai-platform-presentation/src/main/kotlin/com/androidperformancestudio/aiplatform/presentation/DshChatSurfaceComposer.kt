@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("FunctionNaming", "LongParameterList", "ktlint:standard:function-naming")

package com.androidperformancestudio.aiplatform.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.UiLanguage
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Add
import io.github.composefluent.icons.regular.ArrowClockwise
import io.github.composefluent.icons.regular.ArrowUp
import io.github.composefluent.icons.regular.Checkmark
import io.github.composefluent.icons.regular.ChevronDown
import io.github.composefluent.icons.regular.Delete
import io.github.composefluent.icons.regular.DocumentText
import io.github.composefluent.icons.regular.Edit
import io.github.composefluent.icons.regular.Stop

@Composable
internal fun DshComposer(
    state: ChatSurfaceState,
    actions: ChatSurfaceActions,
    language: UiLanguage,
    modifier: Modifier,
) {
    val colors = LocalDshChatColors.current
    var menu by remember { mutableStateOf(false) }
    val canSubmit = state.canSend && state.draft.text.isNotBlank() && state.draft.text.length <= 4096
    val stopping = state.busy && !canSubmit
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.queued.isNotEmpty()) DshQueuedDock(state.queued, actions, language)
        state.contextTitle?.let { title ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Regular.DocumentText, null, Modifier.size(14.dp), tint = colors.muted)
                Spacer(Modifier.width(6.dp))
                DshText(
                    title,
                    Modifier.weight(1f).testTag("ai-bound-context"),
                    color = colors.muted,
                    style = DshChatStyle.secondary,
                    maxLines = 1,
                )
            }
        }
        if (!state.canSend && (!state.busy || state.pendingApproval != null)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DshText(
                    if (state.canIncludeSource && !state.includeSource) {
                        dshLabel(language, "Re-authorize source access to continue this conversation.", "继续此对话前，请重新授权源码访问。")
                    } else {
                        dshLabel(language, "This conversation's report evidence is unavailable.", "此对话的报告证据尚未绑定。")
                    },
                    Modifier.testTag("ai-context-unavailable"),
                    color = colors.error,
                    style = DshChatStyle.secondary,
                )
                if (!state.busy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (state.canIncludeSource && !state.includeSource) {
                            TextButton(onClick = { actions.onIncludeSource(true) }, modifier = Modifier.testTag("ai-reauthorize-source")) {
                                DshText(dshLabel(language, "Authorize source access", "授权源码访问"), style = DshChatStyle.secondary)
                            }
                        } else {
                            TextButton(onClick = actions.onBindContext, modifier = Modifier.testTag("ai-rebind-context")) {
                                DshText(dshLabel(language, "Rebind current report", "重新绑定当前报告"), style = DshChatStyle.secondary)
                            }
                        }
                        TextButton(onClick = actions.onStartOrdinaryChat, modifier = Modifier.testTag("ai-start-ordinary-chat")) {
                            DshText(dshLabel(language, "Start ordinary chat", "新建普通对话"), style = DshChatStyle.secondary)
                        }
                    }
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.composer, RoundedCornerShape(DshChatStyle.panelRadius))
                .border(1.dp, colors.border, RoundedCornerShape(DshChatStyle.panelRadius))
                .testTag("ai-composer")
                .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
        ) {
            BasicTextField(
                value = state.draft,
                onValueChange = actions.onDraft,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp, max = 160.dp)
                        .testTag("ai-question")
                        .onPreviewKeyEvent { event ->
                            if (dshShouldSend(event, state.draft)) {
                                if (canSubmit) actions.onSend()
                                true
                            } else if (dshShouldEditQuestion(event, state.draft)) {
                                val question = state.turns.lastOrNull { it.question != null }?.question
                                question?.let(actions.onEditQuestion)
                                question != null
                            } else {
                                false
                            }
                        },
                textStyle = DshChatStyle.user.copy(color = colors.text),
                cursorBrush = SolidColor(colors.send),
                maxLines = 6,
                decorationBox = { input ->
                    Box {
                        if (state.draft.text.isEmpty()) {
                            DshText(
                                dshLabel(language, "Ask a question...", "输入问题…"),
                                color = colors.muted,
                                style = DshChatStyle.user,
                            )
                        }
                        input()
                    }
                },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    DshIconButton(Icons.Regular.Add, dshLabel(language, "Context", "上下文"), "ai-context-menu") { menu = true }
                    DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = colors.sidebar) {
                        DshMenuItem(Icons.Regular.DocumentText, dshLabel(language, "Bind current evidence", "绑定当前证据"), "ai-bind-context") {
                            menu = false
                            actions.onBindContext()
                        }
                        DropdownMenuItem(
                            text = { DshText(dshLabel(language, "Include authorized source", "包含已授权源码")) },
                            onClick = {
                                if (state.canIncludeSource) {
                                    actions.onIncludeSource(!state.includeSource)
                                    menu = false
                                }
                            },
                            enabled = state.canIncludeSource,
                            modifier = Modifier.testTag("ai-include-source"),
                            leadingIcon = {
                                Checkbox(
                                    checked = state.includeSource && state.canIncludeSource,
                                    onCheckedChange = null,
                                    enabled = state.canIncludeSource,
                                    colors =
                                        CheckboxDefaults.colors(
                                            checkedColor = colors.send,
                                            checkmarkColor = colors.onSend,
                                            uncheckedColor = colors.muted,
                                        ),
                                )
                            },
                        )
                    }
                }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DshModelPicker(state, actions, language, Modifier.weight(1f, fill = false).widthIn(max = 240.dp))
                    Spacer(Modifier.width(4.dp))
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides DshChatStyle.sendButtonSize) {
                        DshIconButton(
                            if (stopping) Icons.Regular.Stop else Icons.Regular.ArrowUp,
                            when {
                                stopping -> dshLabel(language, "Stop", "停止")
                                state.busy -> dshLabel(language, "Add to queue", "加入队列")
                                else -> dshLabel(language, "Send", "发送")
                            },
                            if (stopping) "ai-stop" else "ai-send",
                            enabled = stopping || canSubmit,
                            size = DshChatStyle.sendButtonSize,
                            background = if (stopping) colors.text else colors.send,
                            tint = if (stopping) colors.background else colors.onSend,
                            onClick = if (stopping) actions.onStop else actions.onSend,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DshModelPicker(
    state: ChatSurfaceState,
    actions: ChatSurfaceActions,
    language: UiLanguage,
    modifier: Modifier,
) {
    val colors = LocalDshChatColors.current
    var expanded by remember(state.busy) { mutableStateOf(false) }
    val windowSize = LocalWindowInfo.current.containerSize
    val menuWidth = with(LocalDensity.current) { (windowSize.width.toDp() - 32.dp).coerceIn(0.dp, 280.dp) }
    val menuHeight = with(LocalDensity.current) { (windowSize.height.toDp() - 96.dp).coerceIn(0.dp, 320.dp) }
    val currentModel = state.model.ifBlank { dshLabel(language, "Choose model", "选择模型") }
    val closeModels by rememberUpdatedState(actions.onCloseModels)
    DisposableEffect(Unit) { onDispose { closeModels() } }
    LaunchedEffect(state.busy) { if (state.busy) actions.onCloseModels() }
    Box(modifier) {
        TextButton(
            onClick = {
                if (!state.busy) {
                    expanded = true
                    actions.onOpenModels()
                }
            },
            enabled = !state.busy,
            modifier = Modifier.testTag("ai-model"),
        ) {
            DshText(
                currentModel,
                Modifier.weight(1f, fill = false),
                color = colors.muted.copy(alpha = if (state.busy) 0.45f else 1f),
                style = DshChatStyle.secondary,
                maxLines = 1,
            )
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Regular.ChevronDown, null, Modifier.size(12.dp), tint = colors.muted)
        }
        DropdownMenu(
            expanded = expanded && !state.busy,
            onDismissRequest = {
                expanded = false
                actions.onCloseModels()
            },
            modifier = Modifier.width(menuWidth).heightIn(max = menuHeight).testTag("ai-chat-model-menu"),
            shape = RoundedCornerShape(8.dp),
            containerColor = colors.sidebar,
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                DshText(
                    currentModel,
                    Modifier.weight(1f).testTag("ai-chat-model-current"),
                    color = colors.muted,
                    style = DshChatStyle.secondary,
                    maxLines = 1,
                )
                DshIconButton(
                    Icons.Regular.ArrowClockwise,
                    dshLabel(language, "Refresh models", "刷新模型"),
                    "ai-chat-model-retry",
                    enabled = !state.busy && !state.modelsLoading,
                ) {
                    if (!state.busy && !state.modelsLoading) actions.onRefreshModels()
                }
            }
            val status =
                when {
                    state.modelsLoading -> dshLabel(language, "Loading models...", "正在加载模型…")
                    state.modelsError != null -> dshModelErrorLabel(state.modelsError, language)
                    state.models.isEmpty() -> dshLabel(language, "No models available", "暂无可用模型")
                    else -> null
                }
            if (status != null) {
                DshText(
                    status,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("ai-chat-model-status"),
                    color = if (state.modelsError != null && !state.modelsLoading) colors.error else colors.muted,
                    style = DshChatStyle.secondary,
                )
            }
            state.models.distinct().forEach { model ->
                val selected = model == state.model
                val enabled =
                    !state.busy && !state.modelsLoading && model.isNotBlank() && model.length <= 128 && model.none(Char::isISOControl)
                DropdownMenuItem(
                    text = { DshText(model, color = colors.text.copy(alpha = if (enabled) 1f else 0.45f), maxLines = 1) },
                    onClick = {
                        if (enabled) {
                            expanded = false
                            actions.onCloseModels()
                            actions.onSelectModel(model)
                        }
                    },
                    enabled = enabled,
                    modifier = Modifier.height(32.dp).testTag("ai-chat-model-option-$model").semantics { this.selected = selected },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    trailingIcon = {
                        if (selected) {
                            Icon(Icons.Regular.Checkmark, null, Modifier.size(16.dp), tint = colors.send)
                        } else {
                            Spacer(Modifier.size(16.dp))
                        }
                    },
                )
            }
        }
    }
}

private fun dshModelErrorLabel(
    code: String,
    language: UiLanguage,
): String =
    when (code) {
        "E_AI_MODEL_CATALOG_INVALID_ENDPOINT" -> dshLabel(language, "Model endpoint is unavailable", "模型接口不可用")
        "E_AI_MODEL_CATALOG_HTML_RESPONSE", "E_AI_MODEL_CATALOG_INVALID_RESPONSE" ->
            dshLabel(language, "Model list is unavailable", "模型列表不可用")
        else -> dshLabel(language, "Unable to load models", "无法加载模型")
    }

@Composable
private fun DshQueuedDock(
    queued: List<String>,
    actions: ChatSurfaceActions,
    language: UiLanguage,
) {
    val colors = LocalDshChatColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 148.dp)
            .verticalScroll(rememberScrollState())
            .testTag("ai-queued-dock"),
    ) {
        DshText(
            dshLabel(language, "Queued · ${queued.size}", "排队中 · ${queued.size}"),
            Modifier.padding(horizontal = 16.dp),
            color = colors.muted,
            style = DshChatStyle.secondary,
        )
        queued.forEachIndexed { index, question ->
            Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                DshText(question, Modifier.weight(1f).testTag("ai-queued-$index"), style = DshChatStyle.secondary, maxLines = 1)
                DshIconButton(Icons.Regular.Edit, dshLabel(language, "Edit queued question", "编辑排队问题"), "ai-edit-queued-$index") {
                    actions.onEditQueued(index)
                }
                DshIconButton(Icons.Regular.Delete, dshLabel(language, "Remove queued question", "移除排队问题"), "ai-remove-queued-$index") {
                    actions.onRemoveQueued(index)
                }
            }
        }
    }
}

internal fun dshShouldSend(
    event: KeyEvent,
    draft: TextFieldValue,
): Boolean =
    event.type == KeyEventType.KeyDown &&
        event.key == Key.Enter &&
        !event.isShiftPressed &&
        !event.isCtrlPressed &&
        !event.isMetaPressed &&
        !event.isAltPressed &&
        draft.composition == null

internal fun dshShouldEditQuestion(
    event: KeyEvent,
    draft: TextFieldValue,
): Boolean =
    event.type == KeyEventType.KeyDown &&
        event.key == Key.DirectionUp &&
        draft.text.isEmpty() &&
        !event.isShiftPressed &&
        !event.isCtrlPressed &&
        !event.isMetaPressed &&
        !event.isAltPressed &&
        draft.composition == null
