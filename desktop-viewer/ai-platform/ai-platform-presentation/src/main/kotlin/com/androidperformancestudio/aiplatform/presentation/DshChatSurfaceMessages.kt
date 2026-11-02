@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@file:Suppress("FunctionNaming", "LongParameterList", "ktlint:standard:function-naming")

package com.androidperformancestudio.aiplatform.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.ai.AnalysisFinding
import com.androidperformancestudio.ai.AnalysisResult
import com.androidperformancestudio.aiplatform.model.RunStatus
import com.androidperformancestudio.aiplatform.runtime.ChatTurn
import com.androidperformancestudio.aiplatform.runtime.PreparedAgentRun
import com.androidperformancestudio.aiplatform.storage.ExecutionAuditEvent
import com.androidperformancestudio.ui.UiLanguage
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Add
import io.github.composefluent.icons.regular.ArrowClockwise
import io.github.composefluent.icons.regular.ArrowDown
import io.github.composefluent.icons.regular.ArrowDownload
import io.github.composefluent.icons.regular.ArrowUp
import io.github.composefluent.icons.regular.BranchFork
import io.github.composefluent.icons.regular.ChatSparkle
import io.github.composefluent.icons.regular.Checkmark
import io.github.composefluent.icons.regular.Code
import io.github.composefluent.icons.regular.DocumentText
import io.github.composefluent.icons.regular.Edit
import io.github.composefluent.icons.regular.PanelLeft
import io.github.composefluent.icons.regular.TextBulletListLtr
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun DshConversation(
    state: ChatSurfaceState,
    actions: ChatSurfaceActions,
    language: UiLanguage,
    sidebarVisible: Boolean,
    onToggleSidebar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDshChatColors.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val navigationGutter = DshChatStyle.scrollButtonSize + DshChatStyle.scrollControlsInset
        val chatWidth =
            DshChatStyle
                .chatWidth(maxWidth)
                .coerceAtMost((maxWidth - navigationGutter * 2).coerceAtLeast(0.dp))
        val composerWidth = DshChatStyle.composerWidth(maxWidth)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!sidebarVisible) {
                    DshIconButton(
                        Icons.Regular.PanelLeft,
                        dshLabel(language, "Open sidebar", "展开侧栏"),
                        "ai-sidebar-open",
                        onClick = onToggleSidebar,
                    )
                    DshIconButton(Icons.Regular.Add, dshLabel(language, "New chat", "新建会话"), "ai-new-chat", onClick = actions.onNew)
                }
                DshText(
                    state.sessions
                        .firstOrNull { it.id == state.selectedConversationId }
                        ?.title
                        .orEmpty(),
                    Modifier.weight(1f).padding(horizontal = 8.dp),
                    style = DshChatStyle.user,
                    maxLines = 1,
                )
            }
            DshMessageList(state, actions, language, chatWidth, Modifier.weight(1f).fillMaxWidth())
            DshComposer(state, actions, language, Modifier.width(composerWidth).padding(bottom = 16.dp))
        }
        // Paint the workspace itself, not a floating conversation card.
        Spacer(Modifier.width(0.dp).background(colors.background))
    }
}

@Composable
private fun DshMessageList(
    state: ChatSurfaceState,
    actions: ChatSurfaceActions,
    language: UiLanguage,
    contentWidth: Dp,
    modifier: Modifier,
) {
    val colors = LocalDshChatColors.current
    val list = remember(state.selectedConversationId) { LazyListState() }
    val scope = rememberCoroutineScope()
    var followLatest by remember(state.selectedConversationId) { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (scrolling, canScroll) ->
            if (scrolling) followLatest = !canScroll
        }
    }
    LaunchedEffect(state.selectedConversationId) {
        val count = snapshotFlow { list.layoutInfo.totalItemsCount }.first { it > 0 }
        list.scrollToItem(count - 1)
        followLatest = true
    }
    LaunchedEffect(state.turns, state.pendingApproval?.approval?.id, state.timeline.size, state.status, state.errorCode) {
        if (followLatest && !list.isScrollInProgress) {
            val count = list.layoutInfo.totalItemsCount
            if (count > 0) list.scrollToItem(count - 1)
        }
    }
    Box(modifier.testTag("ai-chat-messages")) {
        LazyColumn(
            state = list,
            modifier =
                Modifier
                    .fillMaxHeight()
                    .width(contentWidth)
                    .align(Alignment.TopCenter)
                    .testTag("ai-chat-history"),
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (state.turns.isEmpty() && state.pendingApproval == null) {
                item("empty") {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 72.dp, bottom = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Regular.ChatSparkle, null, Modifier.size(36.dp), tint = colors.send)
                        Spacer(Modifier.height(20.dp))
                        DshText(dshLabel(language, "What would you like to explore?", "有什么想了解的？"), style = DshChatStyle.title)
                    }
                }
            }
            items(state.turns, key = { "${it.runId}:${it.generation}" }) { turn ->
                DshTurn(turn, state.selectedConversationId, actions, language)
            }
            state.pendingApproval?.let { pending ->
                item("approval-${pending.approval.id}") { DshApproval(pending, actions, language) }
            }
            if (state.timeline.isNotEmpty()) {
                item("activity") { DshActivity(state.timeline, language) }
            }
            if (state.busy && state.pendingApproval == null) {
                item("status") {
                    DshText(dshRunLabel(state.status ?: RunStatus.RUNNING, language), color = colors.muted, style = DshChatStyle.secondary)
                }
            }
            if (state.errorCode != null) {
                item("error") {
                    Column {
                        SelectionContainer { DshText(state.errorCode, color = colors.error, modifier = Modifier.testTag("ai-chat-error")) }
                        DshCommand(
                            Icons.Regular.ArrowClockwise,
                            dshLabel(language, "Retry", "重试"),
                            "ai-retry",
                            enabled = !state.busy,
                            onClick = actions.onRetry,
                        )
                    }
                }
            }
            item("tail") { Spacer(Modifier.height(8.dp)) }
        }
        if (list.canScrollBackward || list.canScrollForward) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides DshChatStyle.scrollButtonSize) {
                Column(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = DshChatStyle.scrollControlsInset, bottom = DshChatStyle.scrollControlsInset)
                        .testTag("ai-scroll-controls"),
                    verticalArrangement = Arrangement.spacedBy(DshChatStyle.scrollButtonGap),
                ) {
                    if (list.canScrollBackward) {
                        DshIconButton(
                            Icons.Regular.ArrowUp,
                            dshLabel(language, "Back to top", "回到顶部"),
                            "ai-scroll-top",
                            modifier = Modifier.border(1.dp, colors.border, CircleShape),
                            size = DshChatStyle.scrollButtonSize,
                            background = colors.sidebar,
                        ) {
                            followLatest = false
                            scope.launch { list.animateScrollToItem(0) }
                        }
                    }
                    if (list.canScrollForward) {
                        DshIconButton(
                            Icons.Regular.ArrowDown,
                            dshLabel(language, "Back to latest", "回到最新消息"),
                            "ai-scroll-bottom",
                            modifier = Modifier.border(1.dp, colors.border, CircleShape),
                            size = DshChatStyle.scrollButtonSize,
                            background = colors.sidebar,
                        ) {
                            scope.launch {
                                list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                                followLatest = true
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DshTurn(
    turn: ChatTurn,
    conversationId: String?,
    actions: ChatSurfaceActions,
    language: UiLanguage,
) {
    val colors = LocalDshChatColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        turn.question?.let { question ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    SelectionContainer(
                        Modifier
                            .align(Alignment.TopEnd)
                            .widthIn(max = maxWidth * 0.702f)
                            .background(colors.bubble, RoundedCornerShape(DshChatStyle.extraLargeRadius))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("ai-user-${turn.runId}"),
                    ) {
                        DshText(question, style = DshChatStyle.user)
                    }
                }
                Row {
                    DshCopyButton(question, language, "ai-copy-question-${turn.runId}")
                    DshIconButton(Icons.Regular.Edit, dshLabel(language, "Edit question", "编辑问题"), "ai-edit-question-${turn.runId}") {
                        actions.onEditQuestion(question)
                    }
                    if (conversationId != null) {
                        DshIconButton(
                            Icons.Regular.BranchFork,
                            dshLabel(language, "Branch from here", "从此处分支"),
                            "ai-branch-question-${turn.runId}",
                        ) {
                            actions.onFork(conversationId, turn.runId)
                        }
                    }
                }
            }
        }
        turn.result?.let { result ->
            Column(Modifier.fillMaxWidth().testTag("ai-assistant-${turn.runId}"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DshSafeText(result.summary, language, "ai-summary-${turn.runId}")
                result.findings.forEach { finding -> DshFinding(finding, actions, language) }
                turn.artifactHashes.distinct().forEach { hash ->
                    DshCommand(Icons.Regular.ArrowDownload, dshLabel(language, "Open verified report", "打开已验证报告"), "ai-artifact-$hash") {
                        actions.onArtifact(hash)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DshCopyButton(dshResultText(result), language, "ai-copy-answer-${turn.runId}")
                    if (conversationId != null) {
                        DshIconButton(
                            Icons.Regular.BranchFork,
                            dshLabel(language, "Branch from here", "从此处分支"),
                            "ai-branch-answer-${turn.runId}",
                        ) {
                            actions.onFork(conversationId, turn.runId)
                        }
                    }
                    DshText(
                        result.model,
                        Modifier.weight(1f).padding(start = 8.dp),
                        color = colors.muted,
                        style = DshChatStyle.secondary,
                        maxLines = 1,
                    )
                }
            }
        }
        if (turn.result == null && turn.status.terminal) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DshText(dshRunLabel(turn.status, language), color = colors.muted, style = DshChatStyle.secondary)
                if (turn.status == RunStatus.RETRYABLE_FAILURE || turn.status == RunStatus.INTERRUPTED) {
                    DshCommand(
                        Icons.Regular.ArrowClockwise,
                        dshLabel(language, "Retry", "重试"),
                        "ai-retry-${turn.runId}",
                        onClick = actions.onRetry,
                    )
                }
            }
        }
    }
}

@Composable
private fun DshFinding(
    finding: AnalysisFinding,
    actions: ChatSurfaceActions,
    language: UiLanguage,
) {
    val colors = LocalDshChatColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectionContainer { DshText(finding.title, style = DshChatStyle.body.copy(fontWeight = FontWeight.Medium)) }
        DshMarkdownText(finding.explanation)
        if (finding.recommendation.isNotBlank()) {
            Box(Modifier.padding(top = 6.dp)) { DshMarkdownText(finding.recommendation) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            finding.performanceEvidenceIds.distinct().forEach { id ->
                DshCommand(Icons.Regular.DocumentText, id, "ai-finding-evidence-$id") { actions.onEvidence(id) }
            }
            finding.sourceCandidateIds.distinct().forEach { id ->
                DshCommand(Icons.Regular.Code, id, "ai-finding-source-$id") { actions.onSource(id) }
            }
        }
        if (finding.performanceEvidenceIds.isEmpty() && finding.sourceCandidateIds.isEmpty()) {
            DshText(dshLabel(language, "No references", "无引用"), color = colors.muted, style = DshChatStyle.secondary)
        }
    }
}

@Composable
private fun DshApproval(
    pending: PreparedAgentRun,
    actions: ChatSurfaceActions,
    language: UiLanguage,
) {
    val colors = LocalDshChatColors.current
    var historyExpanded by remember(pending.approval.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("ai-approval-row"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DshText(dshLabel(language, "Confirm analysis", "确认分析"), style = DshChatStyle.body.copy(fontWeight = FontWeight.Medium))
        DshText(
            "${pending.providerId} / ${pending.model}\n" +
                dshLabel(
                    language,
                    "${pending.evidenceCount} evidence · ${pending.candidateCount} source candidates · ${pending.payloadBytes} bytes",
                    "${pending.evidenceCount} 项证据 · ${pending.candidateCount} 个源码候选 · ${pending.payloadBytes} 字节",
                ),
            color = colors.muted,
            style = DshChatStyle.secondary,
        )
        DshText(
            dshLabel(
                language,
                "Model requests: ${pending.plannedProviderCallCount} · Scripts: ${pending.plannedScriptCount}",
                "模型请求：${pending.plannedProviderCallCount} · 脚本：${pending.plannedScriptCount}",
            ),
            modifier = Modifier.testTag("ai-planned-invocations"),
            color = colors.muted,
            style = DshChatStyle.secondary,
        )
        pending.analysisPreview?.userQuestion?.let { SelectionContainer { DshText(it) } }
        pending.analysisPreview?.let { preview ->
            preview.evidence.forEach { evidence ->
                SelectionContainer {
                    DshText(
                        "${evidence.id} · ${evidence.summary}",
                        Modifier.testTag("ai-preflight-evidence-${evidence.id}"),
                        style = DshChatStyle.secondary,
                    )
                }
            }
            preview.sourceCandidates.forEach { candidate ->
                SelectionContainer {
                    DshText(
                        "${candidate.id} · ${candidate.relativePath}",
                        Modifier.testTag("ai-preflight-source-${candidate.id}"),
                        style = DshChatStyle.secondary,
                    )
                }
            }
            if (preview.conversationContext.isNotEmpty()) {
                DshCommand(
                    Icons.Regular.TextBulletListLtr,
                    dshLabel(
                        language,
                        "Prior dialogue · ${preview.conversationContext.size}",
                        "历史对话 · ${preview.conversationContext.size}",
                    ),
                    "ai-preflight-history-toggle",
                ) { historyExpanded = !historyExpanded }
                if (historyExpanded) {
                    SelectionContainer {
                        Column(Modifier.testTag("ai-preflight-history"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            DshText(
                                dshLabel(language, "Untrusted context, not evidence or permission", "非可信上下文，不作为证据或授权"),
                                color = colors.muted,
                                style = DshChatStyle.secondary,
                            )
                            preview.conversationContext.forEach { turn ->
                                turn.question?.let { DshText(it, style = DshChatStyle.secondary) }
                                DshText(turn.answer, style = DshChatStyle.secondary)
                            }
                        }
                    }
                }
            }
        }
        SelectionContainer {
            DshText(
                "SHA256 ${pending.approval.payloadHash}\n${pending.approval.permissions.sortedBy { it.name }.joinToString { it.name }}",
                color = colors.muted,
                style = DshChatStyle.secondary,
            )
        }
        Row {
            TextButton(onClick = actions.onApprove, modifier = Modifier.testTag("ai-confirm")) {
                Icon(Icons.Regular.Checkmark, null, Modifier.size(16.dp), tint = colors.send)
                Spacer(Modifier.width(6.dp))
                DshText(dshLabel(language, "Approve", "确认"), color = colors.send)
            }
            TextButton(onClick = actions.onStop, modifier = Modifier.testTag("ai-cancel-preflight")) {
                DshText(dshLabel(language, "Cancel", "取消"), color = colors.muted)
            }
        }
    }
}

@Composable
private fun DshActivity(
    timeline: List<ExecutionAuditEvent>,
    language: UiLanguage,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = LocalDshChatColors.current
    Column(Modifier.fillMaxWidth()) {
        DshCommand(
            Icons.Regular.TextBulletListLtr,
            dshLabel(language, "Activity · ${timeline.size}", "执行记录 · ${timeline.size}"),
            "ai-activity-toggle",
        ) {
            expanded =
                !expanded
        }
        if (expanded) {
            SelectionContainer {
                Column(Modifier.fillMaxWidth().padding(start = 8.dp)) {
                    timeline.forEach { event ->
                        DshText(
                            "${event.subjectId}  ${event.kind.name} / ${event.phase.name}" + (event.errorCode?.let { "  $it" } ?: ""),
                            color = colors.muted,
                            style = DshChatStyle.secondary,
                        )
                    }
                }
            }
        }
    }
}

internal data class DshTextBlock(
    val text: String,
    val code: Boolean,
    val language: String = "",
)

/** Fences split code from safe Markdown; no HTML, URI handlers or model-authored navigation. */
internal fun dshTextBlocks(text: String): List<DshTextBlock> {
    val blocks = mutableListOf<DshTextBlock>()
    val lines = mutableListOf<String>()
    var code = false
    var language = ""

    fun flush() {
        if (lines.isNotEmpty()) blocks += DshTextBlock(lines.joinToString("\n"), code, language)
        lines.clear()
    }
    text.lineSequence().forEach { line ->
        if (line.startsWith("```")) {
            flush()
            if (!code) language = line.removePrefix("```").trim().take(32)
            code = !code
        } else {
            lines += line
        }
    }
    flush()
    return blocks
}

@Composable
private fun DshSafeText(
    text: String,
    language: UiLanguage,
    tag: String,
) {
    val colors = LocalDshChatColors.current
    val blocks = remember(text) { dshTextBlocks(text) }
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEachIndexed { index, block ->
            if (block.code) {
                Column(Modifier.fillMaxWidth().background(colors.sidebar, RoundedCornerShape(DshChatStyle.smallRadius)).padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        DshText(block.language, Modifier.weight(1f), color = colors.muted, style = DshChatStyle.secondary)
                        DshCopyButton(block.text, language, "$tag-code-copy-$index")
                    }
                    SelectionContainer {
                        DshText(
                            block.text,
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            style = DshChatStyle.body.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            } else {
                DshMarkdownText(block.text)
            }
        }
    }
}

internal data class DshMarkdownLine(
    val text: AnnotatedString,
    val headingLevel: Int = 0,
    val marker: String? = null,
)

/** Deliberately bounded Markdown presentation, never an HTML renderer or a link executor. */
internal fun dshMarkdownLines(text: String): List<DshMarkdownLine> =
    text
        .lineSequence()
        .map { line ->
            val heading = DSH_HEADING_PATTERN.matchEntire(line)
            val list = if (heading == null) DSH_LIST_PATTERN.matchEntire(line) else null
            val content = heading?.groupValues?.get(2) ?: list?.groupValues?.get(2) ?: line
            val marker = list?.groupValues?.get(1)?.let { if (it in listOf("-", "*", "+")) "•" else it }
            DshMarkdownLine(dshInlineMarkdown(content), heading?.groupValues?.get(1)?.length ?: 0, marker)
        }.toList()

private val DSH_HEADING_PATTERN = Regex("^ {0,3}(#{1,6})\\s+(.+)$")
private val DSH_LIST_PATTERN = Regex("^ {0,3}([-*+]|[0-9]{1,9}[.)])\\s+(.+)$")

internal fun dshInlineMarkdown(text: String): AnnotatedString =
    buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            when {
                text[index] == '\\' && index + 1 < text.length && text[index + 1] in "\\*`#_[]" -> {
                    append(text[index + 1])
                    index += 2
                }
                text[index] == '`' -> {
                    val end = text.indexOf('`', index + 1)
                    if (end > index + 1) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(text.substring(index + 1, end)) }
                        index = end + 1
                    } else {
                        append(text[index++])
                    }
                }
                text.startsWith("**", index) || text.startsWith("__", index) -> {
                    val delimiter = text.substring(index, index + 2)
                    val end = text.indexOf(delimiter, index + 2)
                    if (end > index + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(index + 2, end)) }
                        index = end + 2
                    } else {
                        append(text[index++])
                    }
                }
                else -> append(text[index++])
            }
        }
    }

@Composable
private fun DshMarkdownText(text: String) {
    val colors = LocalDshChatColors.current
    val lines = remember(text) { dshMarkdownLines(text) }
    SelectionContainer {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            lines.forEach { line ->
                if (line.text.isEmpty()) {
                    Spacer(Modifier.height(10.dp))
                } else {
                    val style =
                        when (line.headingLevel) {
                            1 -> DshChatStyle.body.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                            2 -> DshChatStyle.body.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                            in 3..6 -> DshChatStyle.body.copy(fontWeight = FontWeight.SemiBold)
                            else -> DshChatStyle.body
                        }
                    Row(Modifier.fillMaxWidth()) {
                        line.marker?.let { DshText("$it ", Modifier.widthIn(min = 20.dp), color = colors.muted) }
                        Text(
                            line.text,
                            modifier = Modifier.weight(1f).then(if (line.headingLevel > 0) Modifier.semantics { heading() } else Modifier),
                            color = colors.text,
                            style = style,
                        )
                    }
                }
            }
        }
    }
}

internal fun dshResultText(result: AnalysisResult): String =
    buildString {
        append(result.summary)
        result.findings.forEach { finding ->
            append("\n\n${finding.title}\n${finding.explanation}\n${finding.recommendation}")
            val references = finding.performanceEvidenceIds + finding.sourceCandidateIds
            if (references.isNotEmpty()) append("\n${references.joinToString()}")
        }
    }
