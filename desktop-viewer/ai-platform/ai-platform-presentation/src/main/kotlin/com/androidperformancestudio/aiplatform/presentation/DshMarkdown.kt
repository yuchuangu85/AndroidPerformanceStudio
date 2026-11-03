@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package com.androidperformancestudio.aiplatform.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.ui.UiLanguage
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.CheckboxChecked
import io.github.composefluent.icons.regular.CheckboxUnchecked
import io.github.composefluent.icons.regular.TextWrap
import io.github.composefluent.icons.regular.TextWrapOff

/** Native, inert Markdown presentation. Parsing cannot grant navigation or load remote assets. */
@Composable
internal fun DshSafeText(
    text: String,
    language: UiLanguage,
    tag: String,
) {
    val blocks = remember(text) { parseDshMarkdown(text) }
    MarkdownBlocks(blocks, language, tag, Modifier.fillMaxWidth().testTag(tag))
}

@Composable
private fun MarkdownBlocks(
    blocks: List<DshMarkdownBlock>,
    language: UiLanguage,
    tag: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = LocalDshChatColors.current
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            val gap =
                when {
                    index == 0 -> 0.dp
                    compact -> 8.dp
                    block is DshMarkdownBlock.Heading && block.level <= 3 -> 32.dp
                    block is DshMarkdownBlock.Rule || blocks[index - 1] is DshMarkdownBlock.Rule -> 32.dp
                    else -> 16.dp
                }
            val blockTag = "$tag-block-$index"
            Box(Modifier.fillMaxWidth().padding(top = gap)) {
                when (block) {
                    is DshMarkdownBlock.Paragraph -> MarkdownText(block.text)
                    is DshMarkdownBlock.Heading ->
                        MarkdownText(block.text, Modifier.semantics { heading() }, markdownHeadingStyle(block.level))
                    is DshMarkdownBlock.Code -> MarkdownCode(block, language, blockTag)
                    is DshMarkdownBlock.Quote -> {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag("$blockTag-quote")) {
                            Box(Modifier.width(2.dp).fillMaxHeight().background(colors.border))
                            MarkdownBlocks(
                                block.blocks,
                                language,
                                blockTag,
                                Modifier.weight(1f).padding(start = 14.dp),
                                compact = true,
                            )
                        }
                    }
                    is DshMarkdownBlock.ListBlock -> MarkdownList(block, language, blockTag)
                    is DshMarkdownBlock.Table -> MarkdownTable(block, blockTag)
                    DshMarkdownBlock.Rule ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(colors.border)
                                .testTag("$blockTag-rule"),
                        )
                }
            }
        }
    }
}

@Composable
private fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = DshChatStyle.body,
    textAlign: TextAlign? = null,
) {
    val colors = LocalDshChatColors.current
    val inline = remember(text, colors) { dshInlineMarkdown(text, colors) }
    SelectionContainer {
        Text(
            inline,
            modifier.fillMaxWidth(),
            color = colors.text,
            style = style.copy(fontWeight = style.fontWeight ?: FontWeight.Normal),
            textAlign = textAlign,
        )
    }
}

private fun markdownHeadingStyle(level: Int): TextStyle =
    when (level) {
        1 -> DshChatStyle.body.copy(fontSize = 21.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
        2 -> DshChatStyle.body.copy(fontSize = 19.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
        3 -> DshChatStyle.body.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
        else -> DshChatStyle.body.copy(fontWeight = FontWeight.SemiBold)
    }

@Composable
private fun MarkdownList(
    block: DshMarkdownBlock.ListBlock,
    language: UiLanguage,
    tag: String,
) {
    val colors = LocalDshChatColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.items.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth()) {
                val markerWidth = if (block.ordered) 32.dp else 22.dp
                if (item.checked != null) {
                    Icon(
                        if (item.checked) Icons.Regular.CheckboxChecked else Icons.Regular.CheckboxUnchecked,
                        contentDescription = null,
                        modifier =
                            Modifier.padding(top = 4.dp, end = 6.dp).size(16.dp).semantics {
                                stateDescription =
                                    if (item.checked) dshLabel(language, "Completed", "已完成") else dshLabel(language, "Not completed", "未完成")
                            },
                        tint = if (item.checked) colors.send else colors.muted,
                    )
                } else {
                    DshText(
                        if (block.ordered) "${block.start + index}." else "\u2022",
                        Modifier.width(markerWidth).padding(end = 6.dp),
                        color = colors.muted,
                        style = DshChatStyle.body,
                    )
                }
                MarkdownBlocks(item.blocks, language, "$tag-item-$index", Modifier.weight(1f), compact = true)
            }
        }
    }
}

@Composable
private fun MarkdownCode(
    block: DshMarkdownBlock.Code,
    language: UiLanguage,
    tag: String,
) {
    val colors = LocalDshChatColors.current
    var wrap by remember(block) { mutableStateOf(false) }
    val highlighted = remember(block, colors) { dshHighlightCode(block.text, block.language, colors) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshChatStyle.smallRadius))
            .background(colors.sidebar)
            .border(1.dp, colors.border, RoundedCornerShape(DshChatStyle.smallRadius))
            .testTag("$tag-code"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DshText(block.language, Modifier.weight(1f), color = colors.muted, style = DshChatStyle.secondary, maxLines = 1)
            DshIconButton(
                if (wrap) Icons.Regular.TextWrapOff else Icons.Regular.TextWrap,
                dshLabel(language, if (wrap) "Disable line wrapping" else "Wrap lines", if (wrap) "关闭自动换行" else "自动换行"),
                "$tag-code-wrap",
                tint = if (wrap) colors.send else colors.muted,
            ) { wrap = !wrap }
            DshCopyButton(block.text, language, "$tag-code-copy")
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        SelectionContainer {
            val codeModifier =
                if (wrap) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                }
            Text(
                highlighted,
                modifier = codeModifier.padding(16.dp).testTag("$tag-code-text"),
                color = colors.text,
                style = DshChatStyle.body.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp),
                softWrap = wrap,
            )
        }
    }
}

@Composable
private fun MarkdownTable(
    block: DshMarkdownBlock.Table,
    tag: String,
) {
    val colors = LocalDshChatColors.current
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("$tag-table")) {
        val columnWidth = (maxWidth / block.headers.size.coerceAtLeast(1)).coerceIn(144.dp, 280.dp)
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("$tag-table-scroll")) {
            Column(Modifier.width(columnWidth * block.headers.size).border(1.dp, colors.border)) {
                (listOf(block.headers) + block.rows).forEachIndexed { rowIndex, cells ->
                    if (rowIndex > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
                    Row(Modifier.fillMaxWidth().background(if (rowIndex == 0) colors.sidebar else Color.Transparent)) {
                        cells.forEachIndexed { columnIndex, cell ->
                            Box(Modifier.width(columnWidth).padding(horizontal = 10.dp, vertical = 10.dp)) {
                                MarkdownText(
                                    cell,
                                    style =
                                        if (rowIndex == 0) DshChatStyle.body.copy(fontWeight = FontWeight.SemiBold) else DshChatStyle.body,
                                    textAlign =
                                        when (block.alignments.getOrElse(columnIndex) { DshMarkdownAlignment.LEFT }) {
                                            DshMarkdownAlignment.LEFT -> TextAlign.Start
                                            DshMarkdownAlignment.CENTER -> TextAlign.Center
                                            DshMarkdownAlignment.RIGHT -> TextAlign.End
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Lightweight token colors for common report snippets; unknown languages stay verbatim. */
internal fun dshHighlightCode(
    text: String,
    language: String,
    colors: DshChatColors,
): AnnotatedString {
    if (language.lowercase() !in CODE_LANGUAGES) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        CODE_TOKENS.findAll(text).forEach { match ->
            val color =
                when {
                    match.value.startsWith("//") || match.value.startsWith("#") -> colors.muted
                    match.value.startsWith('"') || match.value.startsWith('\'') ->
                        if (colors ==
                            DshChatColors.Dark
                        ) {
                            Color(0xFFA3D9A5)
                        } else {
                            Color(0xFF257048)
                        }
                    match.value.first().isDigit() -> if (colors == DshChatColors.Dark) Color(0xFFF1C88A) else Color(0xFF8A5324)
                    else -> colors.send
                }
            addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
        }
    }
}

private val CODE_LANGUAGES =
    setOf(
        "kotlin",
        "kt",
        "java",
        "javascript",
        "js",
        "typescript",
        "ts",
        "json",
        "python",
        "py",
        "bash",
        "sh",
        "shell",
        "sql",
        "c",
        "cpp",
        "c++",
        "swift",
        "rust",
        "rs",
        "yaml",
        "yml",
    )
private val CODE_TOKENS =
    Regex(
        "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|//[^\\n]*|#[^\\n]*|\\b\\d+(?:\\.\\d+)?\\b|\\b(?:val|var|fun|class|public|private|return|if|else|for|while|when|in|is|as|import|package|object|interface|true|false|null|def|from|let|const|function|async|await|try|catch|throw|new|void|static|SELECT|FROM|WHERE|JOIN|GROUP|BY|ORDER|LIMIT)\\b",
    )
