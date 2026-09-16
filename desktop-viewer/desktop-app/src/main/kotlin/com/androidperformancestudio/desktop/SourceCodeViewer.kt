package com.androidperformancestudio.desktop

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.source.SourceLanguage
import com.androidperformancestudio.source.SourceRange

/**
 * A read-only source viewer modeled after Compose Multiplatform's codeviewer example:
 * virtualized source lines, a non-selectable line-number gutter, and selectable code text.
 */
@Composable
internal fun SourceCodeViewer(
    sourceText: String,
    language: SourceLanguage,
    highlightedRange: SourceRange?,
    modifier: Modifier = Modifier,
) {
    val highlightedLines = remember(sourceText, language) { highlightSource(sourceText, language) }
    val listState = rememberLazyListState()
    val horizontalScrollState = rememberScrollState()
    val scrollbarStyle = LocalScrollbarStyle.current.copy(
        unhoverColor = OneDarkSourceTheme.scrollbar,
        hoverColor = OneDarkSourceTheme.scrollbarHover,
    )
    val targetLineIndex = sourceLineIndex(highlightedRange, highlightedLines.size)

    LaunchedEffect(sourceText, highlightedRange?.startLine) {
        targetLineIndex?.let { lineIndex -> listState.scrollToItem(lineIndex) }
    }

    Box(
        modifier = modifier.background(OneDarkSourceTheme.editorBackground),
    ) {
        CompositionLocalProvider(LocalTextSelectionColors provides OneDarkSourceTheme.textSelectionColors) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(end = 12.dp, bottom = 12.dp)
                            .horizontalScroll(horizontalScrollState),
                ) {
                    itemsIndexed(highlightedLines, key = { index, _ -> index }) { index, line ->
                        SourceCodeLine(
                            lineNumber = index + 1,
                            lineNumberDigits = highlightedLines.size.toString().length,
                            line = line,
                            selected = sourceLineIsHighlighted(index + 1, highlightedRange),
                        )
                    }
                }
            }
        }
        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(horizontalScrollState),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(end = 12.dp),
            style = scrollbarStyle,
        )
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = 12.dp),
            style = scrollbarStyle,
        )
    }
}

@Composable
private fun SourceCodeLine(
    lineNumber: Int,
    lineNumberDigits: Int,
    line: HighlightedSourceLine,
    selected: Boolean,
) {
    val lineBackground = if (selected) OneDarkSourceTheme.currentLine else Color.Transparent
    Row(
        modifier =
            Modifier
                .background(lineBackground)
                .defaultMinSize(minHeight = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DisableSelection {
            Text(
                text = lineNumber.toString().padStart(lineNumberDigits),
                color = OneDarkSourceTheme.lineNumber,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End,
                modifier = Modifier.width(56.dp).padding(horizontal = 8.dp),
            )
        }
        Text(
            text = line.asAnnotatedString(),
            color = OneDarkSourceTheme.foreground,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            softWrap = false,
            maxLines = 1,
            modifier = Modifier.padding(end = 16.dp),
        )
    }
}

internal data class HighlightedSourceLine(
    val text: String,
    val tokens: List<SourceToken>,
)

internal data class SourceToken(
    val kind: SourceTokenKind,
    val start: Int,
    val end: Int,
)

internal enum class SourceTokenKind {
    KEYWORD,
    STRING,
    NUMBER,
    COMMENT,
    ANNOTATION,
    TAG,
    ATTRIBUTE,
    VALUE,
    PREPROCESSOR,
}

private data class SourceLexerState(
    val inBlockComment: Boolean = false,
    val inKotlinRawString: Boolean = false,
    val inXmlComment: Boolean = false,
)

/** Tokenizes the document once so multiline comments and Kotlin raw strings retain their state. */
internal fun highlightSource(
    sourceText: String,
    language: SourceLanguage,
): List<HighlightedSourceLine> {
    val lines = sourceText.toSourceLines()
    var state = SourceLexerState()

    return lines.map { line ->
        val result =
            when (language) {
                SourceLanguage.XML -> highlightXmlLine(line, state)
                SourceLanguage.KOTLIN,
                SourceLanguage.JAVA,
                SourceLanguage.C,
                SourceLanguage.CPP,
                -> highlightCodeLine(line, language, state)
                SourceLanguage.OTHER -> LineHighlightResult(HighlightedSourceLine(line, emptyList()), state)
            }
        state = result.nextState
        result.line
    }
}

internal fun sourceLineIndex(
    highlightedRange: SourceRange?,
    lineCount: Int,
): Int? =
    highlightedRange
        ?.startLine
        ?.minus(1)
        ?.takeIf { it in 0 until lineCount }

internal fun sourceLineIsHighlighted(
    lineNumber: Int,
    highlightedRange: SourceRange?,
): Boolean {
    val range = highlightedRange ?: return false
    return lineNumber in minOf(range.startLine, range.endLine)..maxOf(range.startLine, range.endLine)
}

private data class LineHighlightResult(
    val line: HighlightedSourceLine,
    val nextState: SourceLexerState,
)

private fun String.toSourceLines(): List<String> =
    if (isEmpty()) {
        listOf("")
    } else {
        split('\n').map { it.removeSuffix("\r") }
    }

private fun highlightCodeLine(
    text: String,
    language: SourceLanguage,
    initialState: SourceLexerState,
): LineHighlightResult {
    val tokens = mutableListOf<SourceToken>()
    var index = 0
    var inBlockComment = initialState.inBlockComment
    var inKotlinRawString = initialState.inKotlinRawString
    val isNativeLanguage = language == SourceLanguage.C || language == SourceLanguage.CPP

    while (index < text.length) {
        when {
            inBlockComment -> {
                val commentEnd = text.indexOf("*/", index)
                if (commentEnd == -1) {
                    tokens += SourceToken(SourceTokenKind.COMMENT, index, text.length)
                    index = text.length
                } else {
                    tokens += SourceToken(SourceTokenKind.COMMENT, index, commentEnd + 2)
                    index = commentEnd + 2
                    inBlockComment = false
                }
            }
            inKotlinRawString -> {
                val stringEnd = text.indexOf("\"\"\"", index)
                if (stringEnd == -1) {
                    tokens += SourceToken(SourceTokenKind.STRING, index, text.length)
                    index = text.length
                } else {
                    tokens += SourceToken(SourceTokenKind.STRING, index, stringEnd + 3)
                    index = stringEnd + 3
                    inKotlinRawString = false
                }
            }
            text.startsWith("//", index) -> {
                tokens += SourceToken(SourceTokenKind.COMMENT, index, text.length)
                index = text.length
            }
            text.startsWith("/*", index) -> {
                val commentEnd = text.indexOf("*/", index + 2)
                if (commentEnd == -1) {
                    tokens += SourceToken(SourceTokenKind.COMMENT, index, text.length)
                    inBlockComment = true
                    index = text.length
                } else {
                    tokens += SourceToken(SourceTokenKind.COMMENT, index, commentEnd + 2)
                    index = commentEnd + 2
                }
            }
            language == SourceLanguage.KOTLIN && text.startsWith("\"\"\"", index) -> {
                val stringEnd = text.indexOf("\"\"\"", index + 3)
                if (stringEnd == -1) {
                    tokens += SourceToken(SourceTokenKind.STRING, index, text.length)
                    inKotlinRawString = true
                    index = text.length
                } else {
                    tokens += SourceToken(SourceTokenKind.STRING, index, stringEnd + 3)
                    index = stringEnd + 3
                }
            }
            text[index] == '"' || text[index] == '\'' -> {
                val end = quotedEnd(text, index)
                tokens += SourceToken(SourceTokenKind.STRING, index, end)
                index = end
            }
            text[index] == '@' && index + 1 < text.length && text[index + 1].isIdentifierStart() -> {
                val end = identifierEnd(text, index + 1, allowDotAndColon = true)
                tokens += SourceToken(SourceTokenKind.ANNOTATION, index, end)
                index = end
            }
            isNativeLanguage && text[index] == '#' &&
                text.substring(0, index).all(Char::isWhitespace) -> {
                val end = identifierEnd(text, index + 1)
                tokens += SourceToken(SourceTokenKind.PREPROCESSOR, index, end)
                index = end
            }
            text[index].isDigit() -> {
                val end = numberEnd(text, index)
                tokens += SourceToken(SourceTokenKind.NUMBER, index, end)
                index = end
            }
            text[index].isIdentifierStart() -> {
                val end = identifierEnd(text, index)
                val value = text.substring(index, end)
                tokenKindForIdentifier(value, language)?.let { kind ->
                    tokens += SourceToken(kind, index, end)
                }
                index = end
            }
            else -> index += 1
        }
    }

    return LineHighlightResult(
        line = HighlightedSourceLine(text, tokens),
        nextState = SourceLexerState(
            inBlockComment = inBlockComment,
            inKotlinRawString = inKotlinRawString,
        ),
    )
}

private fun highlightXmlLine(
    text: String,
    initialState: SourceLexerState,
): LineHighlightResult {
    val tokens = mutableListOf<SourceToken>()
    var index = 0
    var inXmlComment = initialState.inXmlComment

    while (index < text.length) {
        if (inXmlComment) {
            val commentEnd = text.indexOf("-->", index)
            if (commentEnd == -1) {
                tokens += SourceToken(SourceTokenKind.COMMENT, index, text.length)
                index = text.length
            } else {
                tokens += SourceToken(SourceTokenKind.COMMENT, index, commentEnd + 3)
                index = commentEnd + 3
                inXmlComment = false
            }
            continue
        }

        val tagStart = text.indexOf('<', index)
        if (tagStart == -1) break
        if (text.startsWith("<!--", tagStart)) {
            val commentEnd = text.indexOf("-->", tagStart + 4)
            if (commentEnd == -1) {
                tokens += SourceToken(SourceTokenKind.COMMENT, tagStart, text.length)
                inXmlComment = true
                index = text.length
            } else {
                tokens += SourceToken(SourceTokenKind.COMMENT, tagStart, commentEnd + 3)
                index = commentEnd + 3
            }
            continue
        }

        var cursor = tagStart + 1
        if (cursor < text.length && text[cursor] == '/') cursor += 1
        if (cursor < text.length && text[cursor] == '?') cursor += 1
        val nameStart = cursor
        cursor = xmlNameEnd(text, cursor)
        if (cursor > nameStart) {
            tokens += SourceToken(SourceTokenKind.TAG, nameStart, cursor)
        }

        while (cursor < text.length && text[cursor] != '>') {
            if (text[cursor].isWhitespace() || text[cursor] == '/' || text[cursor] == '?') {
                cursor += 1
                continue
            }
            val attributeStart = cursor
            cursor = xmlNameEnd(text, cursor)
            if (cursor == attributeStart) {
                cursor += 1
                continue
            }
            tokens += SourceToken(SourceTokenKind.ATTRIBUTE, attributeStart, cursor)
            while (cursor < text.length && text[cursor].isWhitespace()) cursor += 1
            if (cursor >= text.length || text[cursor] != '=') continue
            cursor += 1
            while (cursor < text.length && text[cursor].isWhitespace()) cursor += 1
            if (cursor >= text.length) break
            val valueStart = cursor
            cursor =
                if (text[cursor] == '"' || text[cursor] == '\'') {
                    quotedEnd(text, cursor)
                } else {
                    text.indexOfFirstFrom(cursor) { character -> character.isWhitespace() || character == '>' }
                }
            tokens += SourceToken(SourceTokenKind.VALUE, valueStart, cursor)
        }
        index = if (cursor < text.length) cursor + 1 else cursor
    }

    return LineHighlightResult(
        line = HighlightedSourceLine(text, tokens),
        nextState = SourceLexerState(inXmlComment = inXmlComment),
    )
}

private fun tokenKindForIdentifier(
    value: String,
    language: SourceLanguage,
): SourceTokenKind? =
    when {
        value in valueWords -> SourceTokenKind.VALUE
        value in keywordsFor(language) -> SourceTokenKind.KEYWORD
        else -> null
    }

private fun keywordsFor(language: SourceLanguage): Set<String> =
    when (language) {
        SourceLanguage.KOTLIN -> kotlinKeywords
        SourceLanguage.JAVA -> javaKeywords
        SourceLanguage.C -> cKeywords
        SourceLanguage.CPP -> cppKeywords
        SourceLanguage.XML,
        SourceLanguage.OTHER,
        -> emptySet()
    }

private fun HighlightedSourceLine.asAnnotatedString(): AnnotatedString =
    buildAnnotatedString {
        append(text)
        // Retain a logical line separator when copying multiple selected source rows.
        append('\n')
        tokens.forEach { token ->
            addStyle(SpanStyle(color = OneDarkSourceTheme.colorFor(token.kind)), token.start, token.end)
        }
    }

private fun quotedEnd(text: String, start: Int): Int {
    val quote = text[start]
    var index = start + 1
    while (index < text.length) {
        when (text[index]) {
            '\\' -> index += 2
            quote -> return index + 1
            else -> index += 1
        }
    }
    return text.length
}

private fun identifierEnd(
    text: String,
    start: Int,
    allowDotAndColon: Boolean = false,
): Int {
    var index = start
    while (
        index < text.length &&
            (text[index].isIdentifierPart() ||
                (allowDotAndColon && (text[index] == '.' || text[index] == ':')))
    ) {
        index += 1
    }
    return index
}

private fun numberEnd(text: String, start: Int): Int {
    var index = start + 1
    while (index < text.length && text[index] in numberCharacters) index += 1
    return index
}

private fun xmlNameEnd(text: String, start: Int): Int {
    var index = start
    while (index < text.length && text[index] in xmlNameCharacters) index += 1
    return index
}

private fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
    var index = start
    while (index < length && !predicate(this[index])) index += 1
    return index
}

private fun Char.isIdentifierStart(): Boolean = isLetter() || this == '_' || this == '$'

private fun Char.isIdentifierPart(): Boolean = isLetterOrDigit() || this == '_' || this == '$'

private val numberCharacters = ('0'..'9').toSet() + setOf('.', '_', 'x', 'X', 'a', 'b', 'c', 'd', 'e', 'f', 'A', 'B', 'C', 'D', 'E', 'F', '+', '-')
private val xmlNameCharacters = ('a'..'z').toSet() + ('A'..'Z').toSet() + ('0'..'9').toSet() + setOf('_', '-', ':', '.')
private val valueWords = setOf("true", "false", "null", "NULL", "nullptr")

private val kotlinKeywords =
    setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
        "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
        "val", "var", "when", "while", "by", "catch", "constructor", "delegate", "dynamic", "field", "file",
        "finally", "get", "import", "init", "param", "property", "receiver", "set", "setparam", "where",
        "actual", "abstract", "annotation", "companion", "const", "crossinline", "data", "enum", "expect", "external",
        "final", "infix", "inline", "inner", "internal", "lateinit", "noinline", "open", "operator", "out", "override",
        "private", "protected", "public", "reified", "sealed", "suspend", "tailrec", "vararg",
    )

private val javaKeywords =
    setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
        "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
        "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
        "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while", "record", "sealed", "permits", "var",
        "yield",
    )

private val cKeywords =
    setOf(
        "auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else", "enum", "extern",
        "float", "for", "goto", "if", "inline", "int", "long", "register", "restrict", "return", "short", "signed",
        "sizeof", "static", "struct", "switch", "typedef", "union", "unsigned", "void", "volatile", "while", "_Bool",
        "_Complex", "_Imaginary",
    )

private val cppKeywords =
    cKeywords +
        setOf(
            "alignas", "alignof", "and", "asm", "bitand", "bitor", "bool", "catch", "char8_t", "char16_t", "char32_t",
            "class", "concept", "consteval", "constexpr", "constinit", "const_cast", "co_await", "co_return", "co_yield",
            "decltype", "delete", "dynamic_cast", "explicit", "export", "false", "friend", "mutable", "namespace", "new",
            "noexcept", "not", "not_eq", "nullptr", "operator", "or", "or_eq", "private", "protected", "public", "reflexpr",
            "reinterpret_cast", "requires", "static_assert", "static_cast", "template", "this", "thread_local", "throw", "true",
            "try", "typeid", "typename", "using", "virtual", "wchar_t", "xor", "xor_eq",
        )
