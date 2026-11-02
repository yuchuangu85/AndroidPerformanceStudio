@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("FunctionNaming", "LongParameterList", "ktlint:standard:function-naming")

package com.androidperformancestudio.aiplatform.presentation

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.aiplatform.model.RunStatus
import com.androidperformancestudio.aiplatform.runtime.ChatSessionSummary
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTextField
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Add
import io.github.composefluent.icons.regular.Archive
import io.github.composefluent.icons.regular.ArrowClockwise
import io.github.composefluent.icons.regular.BranchFork
import io.github.composefluent.icons.regular.Checkmark
import io.github.composefluent.icons.regular.Copy
import io.github.composefluent.icons.regular.Edit
import io.github.composefluent.icons.regular.MoreHorizontal
import io.github.composefluent.icons.regular.PanelLeft
import io.github.composefluent.icons.regular.Pin
import io.github.composefluent.icons.regular.Search
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun DshText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalDshChatColors.current.text,
    style: TextStyle = DshChatStyle.body,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(text, modifier, color = color, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun DshIconButton(
    icon: ImageVector,
    title: String,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 32.dp,
    background: Color = Color.Transparent,
    tint: Color = LocalDshChatColors.current.text,
    onClick: () -> Unit,
) {
    val colors = LocalDshChatColors.current
    TooltipArea(tooltip = {
        Surface(color = colors.sidebar, contentColor = colors.text, shape = RoundedCornerShape(DshChatStyle.smallRadius)) {
            DshText(title, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = DshChatStyle.secondary)
        }
    }) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier =
                modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(background)
                    .testTag(tag),
        ) {
            Icon(icon, title, Modifier.size(18.dp), tint = tint.copy(alpha = if (enabled) 1f else 0.4f))
        }
    }
}

@Composable
internal fun DshCommand(
    icon: ImageVector,
    title: String,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(onClick, modifier.testTag(tag), enabled = enabled) {
        Icon(icon, null, Modifier.size(16.dp), tint = LocalDshChatColors.current.muted)
        Spacer(Modifier.width(6.dp))
        DshText(title, style = DshChatStyle.secondary, maxLines = 1)
    }
}

@Composable
internal fun DshCopyButton(
    text: String,
    language: UiLanguage,
    tag: String,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var feedback by remember(text) { mutableStateOf<String?>(null) }
    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(1000)
            feedback = null
        }
    }
    DshIconButton(
        if (feedback == "copied") Icons.Regular.Checkmark else Icons.Regular.Copy,
        when (feedback) {
            "copied" -> dshLabel(language, "Copied", "已复制")
            "failed" -> dshLabel(language, "Copy failed", "复制失败")
            else -> dshLabel(language, "Copy", "复制")
        },
        tag,
    ) {
        scope.launch {
            try {
                clipboard.setClipEntry(ClipEntry(StringSelection(text)))
                feedback = "copied"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                feedback = "failed"
            }
        }
    }
}

@Composable
internal fun DshSidebar(
    state: ChatSurfaceState,
    actions: ChatSurfaceActions,
    language: UiLanguage,
    searchFocus: FocusRequester,
    onClose: () -> Unit,
) {
    val colors = LocalDshChatColors.current
    var search by remember { mutableStateOf("") }
    val sessions =
        remember(state.sessions, search, state.showingArchived) {
            state.sessions
                .filter { it.archived == state.showingArchived && it.title.contains(search.trim(), ignoreCase = true) }
                .sortedWith(compareByDescending<ChatSessionSummary> { it.pinned }.thenByDescending { it.updatedAtEpochMillis })
        }
    Column(
        Modifier
            .width(DshChatStyle.sidebarWidth)
            .fillMaxHeight()
            .background(colors.sidebar)
            .padding(horizontal = 12.dp)
            .testTag("ai-chat-sidebar"),
    ) {
        Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
            DshText(dshLabel(language, "Conversations", "会话"), Modifier.weight(1f))
            DshIconButton(Icons.Regular.PanelLeft, dshLabel(language, "Close sidebar", "收起侧栏"), "ai-sidebar-close", onClick = onClose)
            DshIconButton(Icons.Regular.Add, dshLabel(language, "New chat", "新建会话"), "ai-new-chat", onClick = actions.onNew)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DshChatStyle.smallRadius))
                .background(colors.background)
                .border(1.dp, colors.border, RoundedCornerShape(DshChatStyle.smallRadius))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Regular.Search, null, Modifier.size(16.dp), tint = colors.muted)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                search,
                onValueChange = { search = it },
                modifier = Modifier.weight(1f).focusRequester(searchFocus).testTag("ai-chat-search"),
                textStyle = DshChatStyle.user.copy(color = colors.text),
                singleLine = true,
                cursorBrush = SolidColor(colors.send),
                decorationBox = { input ->
                    Box {
                        if (search.isEmpty()) {
                            DshText(
                                dshLabel(language, "Search conversations", "搜索会话"),
                                color = colors.muted,
                                style = DshChatStyle.user,
                            )
                        }
                        input()
                    }
                },
            )
        }
        Spacer(Modifier.height(16.dp))
        DshText(
            dshLabel(language, if (state.showingArchived) "Archived" else "Recent", if (state.showingArchived) "已归档" else "最近"),
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = colors.muted,
            style = DshChatStyle.secondary,
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (sessions.isEmpty()) {
                item {
                    DshText(
                        dshLabel(language, "No conversations", "暂无会话"),
                        Modifier.padding(8.dp),
                        color = colors.muted,
                        style = DshChatStyle.secondary,
                    )
                }
            }
            items(sessions, key = { it.id }) { session ->
                val selected = session.id == state.selectedConversationId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(DshChatStyle.smallRadius))
                        .background(if (selected) colors.bubble else Color.Transparent)
                        .semantics { this.selected = selected }
                        .clickable(role = Role.Button) { actions.onSelect(session.id) }
                        .padding(start = 10.dp, end = 2.dp, top = 7.dp, bottom = 7.dp)
                        .testTag("ai-session-${session.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (session.pinned) {
                                Icon(Icons.Regular.Pin, dshLabel(language, "Pinned", "已置顶"), Modifier.size(12.dp), tint = colors.muted)
                                Spacer(Modifier.width(4.dp))
                            }
                            DshText(
                                session.title.ifBlank { dshLabel(language, "New chat", "新建会话") },
                                style = DshChatStyle.user,
                                maxLines = 1,
                            )
                        }
                        DshText(
                            "${dshSessionDate(session.updatedAtEpochMillis)}  ·  ${session.turnCount}",
                            color = colors.muted,
                            style = DshChatStyle.secondary,
                            maxLines = 1,
                        )
                    }
                    DshSessionMenu(session, actions, language)
                }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically) {
            DshCommand(
                Icons.Regular.Archive,
                dshLabel(
                    language,
                    if (state.showingArchived) "Back to recent" else "Archived",
                    if (state.showingArchived) "返回最近" else "已归档",
                ),
                "ai-show-archived",
                Modifier.weight(1f),
            ) { actions.onShowArchived(!state.showingArchived) }
        }
    }
}

@Composable
private fun DshSessionMenu(
    session: ChatSessionSummary,
    actions: ChatSurfaceActions,
    language: UiLanguage,
) {
    val colors = LocalDshChatColors.current
    var expanded by remember(session.id) { mutableStateOf(false) }
    var renaming by remember(session.id) { mutableStateOf(false) }
    var confirmingArchive by remember(session.id) { mutableStateOf(false) }
    var title by remember(session.id, session.title) { mutableStateOf(session.title) }
    var copyFeedback by remember(session.id) { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(copyFeedback) {
        if (copyFeedback != null) {
            delay(1000)
            copyFeedback = null
        }
    }
    Box {
        DshIconButton(Icons.Regular.MoreHorizontal, dshLabel(language, "Conversation menu", "会话菜单"), "ai-session-menu-${session.id}") {
            expanded =
                true
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, containerColor = colors.sidebar) {
            DshMenuItem(
                Icons.Regular.Pin,
                dshLabel(language, if (session.pinned) "Unpin" else "Pin", if (session.pinned) "取消置顶" else "置顶"),
                "ai-session-pin",
            ) {
                expanded = false
                actions.onPin(session.id, !session.pinned)
            }
            DshMenuItem(Icons.Regular.Edit, dshLabel(language, "Rename", "重命名"), "ai-session-rename") {
                expanded = false
                title = session.title
                renaming = true
            }
            DshMenuItem(Icons.Regular.BranchFork, dshLabel(language, "Branch conversation", "分支会话"), "ai-session-fork") {
                expanded = false
                actions.onFork(session.id, null)
            }
            DshMenuItem(
                if (session.archived) Icons.Regular.ArrowClockwise else Icons.Regular.Archive,
                dshLabel(language, if (session.archived) "Unarchive" else "Archive", if (session.archived) "取消归档" else "归档"),
                "ai-session-archive",
            ) {
                expanded = false
                if (!session.archived && session.status?.terminal == false) {
                    confirmingArchive = true
                } else {
                    actions.onArchive(session.id, !session.archived)
                }
            }
            DshMenuItem(
                if (copyFeedback == "copied") Icons.Regular.Checkmark else Icons.Regular.Copy,
                when (copyFeedback) {
                    "copied" -> dshLabel(language, "Session ID copied", "会话 ID 已复制")
                    "failed" -> dshLabel(language, "Copy failed", "复制失败")
                    else -> dshLabel(language, "Copy session ID", "复制会话 ID")
                },
                "ai-session-copy-id",
            ) {
                scope.launch {
                    try {
                        clipboard.setClipEntry(ClipEntry(StringSelection(session.id)))
                        copyFeedback = "copied"
                        actions.onCopySession(session.id)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        copyFeedback = "failed"
                    }
                }
            }
        }
    }
    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = colors.background,
            title = { DshText(dshLabel(language, "Rename conversation", "重命名会话")) },
            text = {
                ViewerTextField(
                    title,
                    onValueChange = { title = it.take(120) },
                    modifier = Modifier.fillMaxWidth().testTag("ai-rename-title"),
                    textStyle = DshChatStyle.body.copy(color = colors.text),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.onRename(session.id, title.trim())
                        renaming = false
                    },
                    enabled = title.isNotBlank(),
                    modifier = Modifier.testTag("ai-rename-confirm"),
                ) {
                    DshText(dshLabel(language, "Rename", "重命名"))
                }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { DshText(dshLabel(language, "Cancel", "取消")) } },
        )
    }
    if (confirmingArchive) {
        AlertDialog(
            onDismissRequest = { confirmingArchive = false },
            containerColor = colors.background,
            title = { DshText(dshLabel(language, "Archive running conversation?", "归档正在运行的会话？")) },
            text = {
                DshText(
                    dshLabel(language, "This stops the current run before archiving. Saved messages are kept.", "归档前会停止当前运行，已保存的消息将保留。"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingArchive = false
                        actions.onArchive(session.id, true)
                    },
                    modifier = Modifier.testTag("ai-archive-confirm"),
                ) { DshText(dshLabel(language, "Stop and archive", "停止并归档")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingArchive = false }, modifier = Modifier.testTag("ai-archive-cancel")) {
                    DshText(dshLabel(language, "Cancel", "取消"))
                }
            },
        )
    }
}

@Composable
internal fun DshMenuItem(
    icon: ImageVector,
    title: String,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { DshText(title, color = LocalDshChatColors.current.text.copy(alpha = if (enabled) 1f else 0.45f)) },
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.testTag(tag),
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp), tint = LocalDshChatColors.current.muted) },
    )
}

internal fun dshRunLabel(
    status: RunStatus,
    language: UiLanguage,
): String =
    when (status) {
        RunStatus.QUEUED -> dshLabel(language, "Queued", "排队中")
        RunStatus.PREPARING -> dshLabel(language, "Preparing", "准备中")
        RunStatus.AWAITING_APPROVAL -> dshLabel(language, "Awaiting approval", "等待确认")
        RunStatus.RUNNING -> dshLabel(language, "Running", "运行中")
        RunStatus.SUCCEEDED -> dshLabel(language, "Completed", "已完成")
        RunStatus.CANCELLED -> dshLabel(language, "Stopped", "已停止")
        RunStatus.RETRYABLE_FAILURE -> dshLabel(language, "Failed", "失败")
        RunStatus.PERMANENT_FAILURE -> dshLabel(language, "Unavailable", "不可用")
        RunStatus.INTERRUPTED -> dshLabel(language, "Interrupted", "已中断")
    }

private fun dshSessionDate(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("MM/dd").format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
