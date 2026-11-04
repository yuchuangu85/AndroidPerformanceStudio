package com.androidperformancestudio.aiplatform.presentation

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import java.net.URI

/** Display-only subset: no link annotations, resource resolution, or HTML execution. */
internal fun dshInlineMarkdown(
    text: String,
    colors: DshChatColors = DshChatColors.Light,
): AnnotatedString = DshInlineParser(text, colors).render()

private class DshInlineParser(
    private val source: String,
    private val colors: DshChatColors,
) {
    private var workRemaining = source.length.toLong() * 32 + 128

    fun render(): AnnotatedString {
        val result = parse(0, source.length, null, 0, SpanStyle())
        return result?.text?.takeIf { workRemaining >= 0 } ?: AnnotatedString(source)
    }

    private fun parse(
        start: Int,
        limit: Int,
        closing: String?,
        depth: Int,
        style: SpanStyle,
    ): InlineResult? {
        if (depth > MAX_INLINE_DEPTH) return null
        val output = AnnotatedString.Builder()
        var index = start
        var plainStart = start

        fun flush(end: Int) {
            output.styled(source.substring(plainStart, end), style)
        }
        while (index < limit) {
            if (!spend()) return null
            val char = source[index]
            if (char == '\\' && index + 1 < limit && source[index + 1] in ESCAPABLE) {
                flush(index)
                output.styled(source.substring(index + 1, index + 2), style)
                index += 2
                plainStart = index
                continue
            }
            if (char == '`') {
                val width = runEnd(index, limit) - index
                var end = index + width
                while (end < limit) {
                    if (!spend()) return null
                    if (source[end] == '`') {
                        val next = runEnd(end, limit)
                        if (next - end == width) break
                        end = next
                    } else {
                        end++
                    }
                }
                if (end == limit) return null
                flush(index)
                output.styled(
                    source.substring(index + width, end),
                    style.merge(
                        SpanStyle(
                            color = colors.text,
                            background = colors.sidebar,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            letterSpacing = 0.sp,
                        ),
                    ),
                )
                index = end + width
                plainStart = index
                continue
            }
            if (char == '<') {
                flush(index)
                val end = htmlEnd(index, limit)
                output.styled(source.substring(index, end), style)
                index = end
                plainStart = index
                continue
            }
            if (char == '[' || (char == '!' && index + 1 < limit && source[index + 1] == '[')) {
                val image = char == '!'
                val labelStart = index + if (image) 2 else 1
                val labelEnd = balancedEnd(labelStart - 1, limit, '[', ']') ?: return null
                val targetStart = labelEnd + 1
                val targetEnd =
                    if (targetStart < limit && source[targetStart] == '(') {
                        balancedEnd(targetStart, limit, '(', ')') ?: return null
                    } else {
                        null
                    }
                if (image || targetEnd != null) {
                    var end = targetEnd?.plus(1) ?: targetStart
                    if (image && targetEnd == null && targetStart < limit && source[targetStart] == '[') {
                        end = (balancedEnd(targetStart, limit, '[', ']') ?: return null) + 1
                    }
                    flush(index)
                    if (!image && targetEnd != null && safeHttpTarget(source.substring(targetStart + 1, targetEnd))) {
                        val label = parse(labelStart, labelEnd, null, depth + 1, style.linkStyle()) ?: return null
                        output.append(label.text)
                    } else {
                        output.styled(source.substring(index, end), style)
                    }
                    index = end
                    plainStart = index
                    continue
                }
            }
            if (char == '*' || char == '_' || char == '~') {
                val width = runEnd(index, limit) - index
                if (closing != null && char == closing[0] && canClose(index, width, limit)) {
                    val matches = if (closing.length == 1) width % 2 == 1 else width >= closing.length
                    if (matches) {
                        flush(index)
                        return InlineResult(output.toAnnotatedString(), index + closing.length)
                    }
                }
                if ((char == '~' && width != 2) || (char != '~' && width > 3)) return null
                if (canOpen(index, width, limit)) {
                    val marker = char.toString().repeat(if (width >= 2) 2 else 1)
                    val nestedStyle =
                        when {
                            char == '~' -> style.withDecoration(TextDecoration.LineThrough)
                            marker.length == 2 -> style.merge(SpanStyle(fontWeight = FontWeight.SemiBold))
                            else -> style.merge(SpanStyle(fontStyle = FontStyle.Italic))
                        }
                    val nested = parse(index + marker.length, limit, marker, depth + 1, nestedStyle) ?: return null
                    flush(index)
                    output.append(nested.text)
                    index = nested.end
                    plainStart = index
                    continue
                }
                index += width
            } else {
                index++
            }
        }
        if (closing != null) return null
        flush(limit)
        return InlineResult(output.toAnnotatedString(), limit)
    }

    private fun runEnd(
        start: Int,
        limit: Int,
    ): Int {
        var end = start + 1
        while (end < limit && source[end] == source[start] && spend()) end++
        return end
    }

    private fun canOpen(
        index: Int,
        width: Int,
        limit: Int,
    ): Boolean =
        index + width < limit &&
            !source[index + width].isWhitespace() &&
            !(source[index] == '_' && index > 0 && source[index - 1].isLetterOrDigit())

    private fun canClose(
        index: Int,
        width: Int,
        limit: Int,
    ): Boolean =
        index > 0 &&
            !source[index - 1].isWhitespace() &&
            !(source[index] == '_' && index + width < limit && source[index + width].isLetterOrDigit())

    private fun balancedEnd(
        start: Int,
        limit: Int,
        open: Char,
        close: Char,
    ): Int? {
        var nesting = 1
        var index = start + 1
        while (index < limit) {
            if (!spend()) return null
            when (source[index]) {
                '\\' -> index++
                open -> if (++nesting > MAX_INLINE_DEPTH) return null
                close -> if (--nesting == 0) return index
            }
            index++
        }
        return null
    }

    private fun htmlEnd(
        start: Int,
        limit: Int,
    ): Int {
        val firstEnd = tagEnd(start, limit)
        val tag = HTML_TAG.matchAt(source, start) ?: return firstEnd
        if (source.startsWith("</", start)) return firstEnd
        val name = tag.groupValues[1]
        if (name.lowercase() in VOID_TAGS || source.substring(start, firstEnd).trimEnd().endsWith("/>")) return firstEnd
        var nesting = 1
        var index = firstEnd
        while (index < limit) {
            if (!spend()) return limit
            if (source[index] == '<') {
                val next = HTML_TAG.matchAt(source, index)
                val closing = source.startsWith("</", index)
                val closingName = if (closing) next?.groupValues?.get(1) else null
                val end = tagEnd(index, limit)
                if (closingName.equals(name, ignoreCase = true) && --nesting == 0) return end
                if (!closing &&
                    next?.groupValues?.get(1).equals(name, ignoreCase = true) &&
                    !source.substring(index, end).endsWith("/>")
                ) {
                    nesting++
                }
                index = end
            } else {
                index++
            }
        }
        return limit
    }

    private fun tagEnd(
        start: Int,
        limit: Int,
    ): Int {
        var quote: Char? = null
        var index = start + 1
        while (index < limit) {
            if (!spend()) return limit
            val char = source[index++]
            if (quote != null) {
                if (char == quote) quote = null
            } else if (char == '\'' || char == '"') {
                quote = char
            } else if (char == '>') {
                return index
            }
        }
        return limit
    }

    private fun safeHttpTarget(value: String): Boolean =
        try {
            val uri = URI(value)
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrEmpty()
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: java.net.URISyntaxException) {
            false
        }

    private fun SpanStyle.linkStyle(): SpanStyle = withDecoration(TextDecoration.Underline).merge(SpanStyle(color = colors.send))

    private fun SpanStyle.withDecoration(decoration: TextDecoration): SpanStyle =
        copy(textDecoration = TextDecoration.combine(listOfNotNull(textDecoration, decoration)))

    private fun spend(): Boolean = --workRemaining >= 0

    private data class InlineResult(
        val text: AnnotatedString,
        val end: Int,
    )

    private companion object {
        const val MAX_INLINE_DEPTH = 12
        const val ESCAPABLE = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
        val HTML_TAG = Regex("</?([A-Za-z][A-Za-z0-9:-]*)\\b")
        val VOID_TAGS = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
    }
}

private fun AnnotatedString.Builder.styled(
    text: String,
    style: SpanStyle,
) {
    val start = length
    append(text)
    if (text.isNotEmpty() && style != SpanStyle()) addStyle(style, start, length)
}
