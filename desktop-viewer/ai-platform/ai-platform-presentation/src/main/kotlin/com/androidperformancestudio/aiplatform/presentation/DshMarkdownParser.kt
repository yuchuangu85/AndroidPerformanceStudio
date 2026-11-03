package com.androidperformancestudio.aiplatform.presentation

internal sealed interface DshMarkdownBlock {
    data class Paragraph(
        val text: String,
    ) : DshMarkdownBlock

    data class Heading(
        val level: Int,
        val text: String,
    ) : DshMarkdownBlock

    data class Code(
        val text: String,
        val language: String = "",
    ) : DshMarkdownBlock

    data class Quote(
        val blocks: List<DshMarkdownBlock>,
    ) : DshMarkdownBlock

    data class ListBlock(
        val ordered: Boolean,
        val start: Int,
        val items: List<DshMarkdownListItem>,
    ) : DshMarkdownBlock

    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        val alignments: List<DshMarkdownAlignment>,
    ) : DshMarkdownBlock

    data object Rule : DshMarkdownBlock
}

internal data class DshMarkdownListItem(
    val blocks: List<DshMarkdownBlock>,
    val checked: Boolean? = null,
)

internal enum class DshMarkdownAlignment { LEFT, CENTER, RIGHT }

/** Display-only subset, not CommonMark: no inline interpretation, I/O, HTML or URI execution. */
internal fun parseDshMarkdown(text: String): List<DshMarkdownBlock> {
    val root = DshBlockFrame(text.replace("\r\n", "\n").replace('\r', '\n').split('\n'), 0)
    val pending = ArrayDeque<DshBlockFrame>()
    pending.addLast(root)
    // Explicit frames bound container nesting without using the JVM call stack.
    while (pending.isNotEmpty()) {
        val frame = pending.last()
        if (frame.depth >= 12) {
            frame.blocks += DshMarkdownBlock.Paragraph(frame.lines.joinToString("\n"))
            pending.removeLast()
        } else if (frame.index >= frame.lines.size) {
            pending.removeLast()
        } else {
            frame.advance().asReversed().forEach(pending::addLast)
        }
    }
    return root.blocks
}

private class DshBlockFrame(
    val lines: List<String>,
    val depth: Int,
) {
    val blocks = mutableListOf<DshMarkdownBlock>()
    var index = 0

    fun advance(): List<DshBlockFrame> {
        val line = lines[index]
        val fence = dshFence(line)
        val heading = DSH_BLOCK_HEADING.matchEntire(line)
        when {
            line.isBlank() -> index++
            fence != null -> readFence(fence)
            dshIndent(line) >= 4 -> readIndentedCode()
            heading != null -> {
                val text = heading.groupValues[2].trim().replace(Regex("[ \\t]+#+[ \\t]*$"), "")
                blocks += DshMarkdownBlock.Heading(heading.groupValues[1].length, text)
                index++
            }
            dshRule(line) -> {
                blocks += DshMarkdownBlock.Rule
                index++
            }
            DSH_BLOCK_QUOTE.matches(line) -> {
                val content = mutableListOf<String>()
                while (index < lines.size) {
                    val quote = DSH_BLOCK_QUOTE.matchEntire(lines[index]) ?: break
                    content += quote.groupValues[1]
                    index++
                }
                val child = DshBlockFrame(content, depth + 1)
                blocks += DshMarkdownBlock.Quote(child.blocks)
                return listOf(child)
            }
            dshListMarker(line) != null -> return readList()
            tableAlignments(index) != null -> readTable()
            else -> readParagraph()
        }
        return emptyList()
    }

    private fun readFence(fence: DshFence) {
        index++
        val content = mutableListOf<String>()
        while (index < lines.size) {
            val line = lines[index++]
            val candidate = line.dropWhile { it == ' ' }
            val length = candidate.takeWhile { it == fence.marker }.length
            if (dshIndent(line) <= 3 && length >= fence.length && candidate.drop(length).isBlank()) break
            content += dshRemoveIndent(line, fence.indent)
        }
        blocks += DshMarkdownBlock.Code(content.joinToString("\n"), fence.language)
    }

    private fun readIndentedCode() {
        val content = mutableListOf<String>()
        var end = index
        while (index < lines.size && (lines[index].isBlank() || dshIndent(lines[index]) >= 4)) {
            content += dshRemoveIndent(lines[index], 4)
            if (lines[index].isNotBlank()) end = content.size
            index++
        }
        blocks += DshMarkdownBlock.Code(content.take(end).joinToString("\n"))
    }

    private fun readList(): List<DshBlockFrame> {
        val first = checkNotNull(dshListMarker(lines[index]))
        val items = mutableListOf<DshMarkdownListItem>()
        val children = mutableListOf<DshBlockFrame>()
        while (index < lines.size) {
            val marker = dshListMarker(lines[index]) ?: break
            if (marker.indent != first.indent || marker.kind != first.kind) break
            val task = DSH_BLOCK_TASK.matchEntire(marker.text)
            val content = mutableListOf(task?.groupValues?.get(2) ?: marker.text)
            index++
            var afterBlank = false
            while (index < lines.size) {
                val line = lines[index]
                if (line.isBlank()) {
                    content += ""
                    afterBlank = true
                    index++
                } else if (dshIndent(line) >= marker.contentIndent) {
                    content += dshRemoveIndent(line, marker.contentIndent)
                    afterBlank = false
                    index++
                } else if (!afterBlank && content.last().isNotBlank() && !startsBlock(index) && dshListMarker(line) == null) {
                    // Lazy continuation is limited to an item's ordinary paragraph.
                    if (content.any { dshFence(it) != null || DSH_BLOCK_QUOTE.matches(it) || dshListMarker(it) != null }) break
                    content += line
                    index++
                } else {
                    break
                }
            }
            while (content.lastOrNull()?.isBlank() == true) content.removeAt(content.lastIndex)
            val child = DshBlockFrame(content, depth + 1)
            children += child
            items += DshMarkdownListItem(child.blocks, task?.groupValues?.get(1)?.let { it != " " })
        }
        blocks += DshMarkdownBlock.ListBlock(first.ordered, first.start, items)
        return children
    }

    private fun readParagraph() {
        val content = mutableListOf(lines[index++])
        while (index < lines.size && lines[index].isNotBlank()) {
            val level = dshSetext(lines[index])
            if (level != null) {
                blocks += DshMarkdownBlock.Heading(level, dshParagraph(content))
                index++
                return
            }
            if (startsBlock(index)) break
            content += lines[index++]
        }
        blocks += DshMarkdownBlock.Paragraph(dshParagraph(content))
    }

    private fun startsBlock(at: Int): Boolean =
        dshFence(lines[at]) != null ||
            DSH_BLOCK_HEADING.matches(lines[at]) ||
            DSH_BLOCK_QUOTE.matches(lines[at]) ||
            dshRule(lines[at]) ||
            dshListMarker(lines[at]) != null ||
            tableAlignments(at) != null

    private fun tableAlignments(at: Int): List<DshMarkdownAlignment>? {
        if (at + 1 >= lines.size || dshIndent(lines[at]) >= 4 || dshIndent(lines[at + 1]) >= 4) return null
        val header = dshTableCells(lines[at])
        val delimiter = dshTableCells(lines[at + 1])
        if ((!header.hasPipe && !delimiter.hasPipe) || header.cells.size != delimiter.cells.size) return null
        if (delimiter.cells.any { !DSH_TABLE_DELIMITER.matches(it) }) return null
        return delimiter.cells.map {
            when {
                it.startsWith(':') && it.endsWith(':') -> DshMarkdownAlignment.CENTER
                it.endsWith(':') -> DshMarkdownAlignment.RIGHT
                else -> DshMarkdownAlignment.LEFT
            }
        }
    }

    private fun readTable() {
        val alignments = checkNotNull(tableAlignments(index))
        val headers = dshTableCells(lines[index]).cells
        val rows = mutableListOf<List<String>>()
        index += 2
        while (index < lines.size && lines[index].isNotBlank()) {
            // An unpiped short row is valid, but a new block ends the table.
            if (dshIndent(lines[index]) >= 4 || startsBlock(index)) break
            val cells = dshTableCells(lines[index++]).cells
            rows += List(headers.size) { cells.getOrElse(it) { "" } }
        }
        blocks += DshMarkdownBlock.Table(headers, rows, alignments)
    }
}

private data class DshFence(
    val marker: Char,
    val length: Int,
    val language: String,
    val indent: Int,
)

private fun dshFence(line: String): DshFence? {
    val indent = line.takeWhile { it == ' ' }.length
    if (indent > 3) return null
    val content = line.drop(indent)
    val marker = content.firstOrNull() ?: return null
    if (marker != '`' && marker != '~') return null
    val length = content.takeWhile { it == marker }.length
    if (length < 3) return null
    val info = content.drop(length).trim()
    if (marker == '`' && '`' in info) return null
    return DshFence(marker, length, info.takeWhile { !it.isWhitespace() }, indent)
}

private data class DshListMarker(
    val indent: Int,
    val contentIndent: Int,
    val kind: Char,
    val ordered: Boolean,
    val start: Int,
    val text: String,
)

private fun dshListMarker(line: String): DshListMarker? {
    if (dshRule(line)) return null
    val match = DSH_BLOCK_LIST.matchEntire(line) ?: return null
    val indent = match.groupValues[1].length
    val marker = match.groupValues[2]
    val ordered = marker.first().isDigit()
    val prefix = indent + marker.length
    val padding = dshIndent(line.drop(prefix)).let { if (it in 1..4) it else 1 }
    return DshListMarker(
        indent,
        prefix + padding,
        marker.last(),
        ordered,
        if (ordered) marker.dropLast(1).toInt() else 1,
        dshRemoveIndent(line.drop(prefix), padding),
    )
}

private fun dshIndent(line: String): Int {
    var columns = 0
    for (char in line) {
        when (char) {
            ' ' -> columns++
            '\t' -> columns += 4 - columns % 4
            else -> break
        }
    }
    return columns
}

private fun dshRemoveIndent(
    line: String,
    count: Int,
): String {
    var index = 0
    var columns = 0
    while (index < line.length && columns < count) {
        when (line[index]) {
            ' ' -> columns++
            '\t' -> columns += 4 - columns % 4
            else -> break
        }
        index++
    }
    return " ".repeat((columns - count).coerceAtLeast(0)) + line.drop(index)
}

private fun dshParagraph(lines: List<String>): String =
    buildString {
        lines.forEachIndexed { index, line ->
            append(line.trim())
            if (index < lines.lastIndex) append(if (line.endsWith("  ")) '\n' else ' ')
        }
    }

private fun dshRule(line: String): Boolean {
    if (dshIndent(line) > 3) return false
    val compact = line.filterNot { it == ' ' || it == '\t' }
    return compact.length >= 3 && compact.first() in "-*_" && compact.all { it == compact.first() }
}

private fun dshSetext(line: String): Int? =
    if (DSH_BLOCK_SETEXT.matches(line)) {
        if (line.trim().first() == '=') 1 else 2
    } else {
        null
    }

private data class DshTableCells(
    val cells: List<String>,
    val hasPipe: Boolean,
)

private fun dshTableCells(line: String): DshTableCells {
    val text = line.trim()
    // Match exact backtick run lengths; unmatched ticks must not hide real separators.
    val runs = DSH_BACKTICKS.findAll(text).toList()
    val nextByLength = mutableMapOf<Int, Int>()
    val closing = mutableMapOf<Int, IntRange>()
    for (run in runs.asReversed()) {
        nextByLength[run.value.length]?.let { end -> closing[run.range.first] = end until end + run.value.length }
        nextByLength[run.value.length] = run.range.first
    }
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var index = 0
    var hasPipe = false
    while (index < text.length) {
        val char = text[index]
        val end = closing[index]
        when {
            char == '\\' && index + 1 < text.length -> {
                cell.append(char).append(text[index + 1])
                index += 2
            }
            char == '`' && end != null -> {
                cell.append(text.substring(index, end.last + 1))
                index = end.last + 1
            }
            char == '|' -> {
                cells += cell.toString().trim()
                cell.setLength(0)
                hasPipe = true
                index++
            }
            else -> cell.append(text[index++])
        }
    }
    cells += cell.toString().trim()
    if (hasPipe && cells.first().isEmpty()) cells.removeAt(0)
    if (hasPipe && cells.isNotEmpty() && cells.last().isEmpty()) cells.removeAt(cells.lastIndex)
    return DshTableCells(cells, hasPipe)
}

private val DSH_BLOCK_HEADING = Regex("^ {0,3}(#{1,6})(?:[ \\t]+(.*)|$)")
private val DSH_BLOCK_QUOTE = Regex("^ {0,3}>[ \\t]?(.*)$")
private val DSH_BLOCK_LIST = Regex("^( {0,3})([-+*]|[0-9]{1,9}[.)])(?:[ \\t]+.*|$)")
private val DSH_BLOCK_TASK = Regex("^\\[([ xX])] (.*)$")
private val DSH_BLOCK_SETEXT = Regex("^ {0,3}(?:=+|-+)[ \\t]*$")
private val DSH_TABLE_DELIMITER = Regex("^:?-{3,}:?$")
private val DSH_BACKTICKS = Regex("`+")
