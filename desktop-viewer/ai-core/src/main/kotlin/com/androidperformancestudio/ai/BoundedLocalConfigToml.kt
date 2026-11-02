package com.androidperformancestudio.ai

internal sealed interface TomlValue
private class TomlString(val value: String, val multiline: Boolean = false) : TomlValue
private class TomlBoolean(val value: Boolean) : TomlValue
private data object TomlOther : TomlValue
private class TomlArrayTable(val items: MutableList<TomlTable> = mutableListOf()) : TomlValue

internal class TomlTable(
    val values: MutableMap<String, TomlValue> = linkedMapOf(),
    val inline: Boolean = false,
) : TomlValue {
    fun text(key: String): String? = values[key]?.let {
        val string = it as? TomlString ?: invalidLocalConfig()
        if (string.multiline) invalidLocalConfig(LocalAiImportFailure.UNSUPPORTED_CONFIG)
        string.value
    }

    fun boolean(key: String): Boolean? = values[key]?.let { (it as? TomlBoolean)?.value ?: invalidLocalConfig() }
    fun table(key: String): TomlTable? = values[key]?.let { it as? TomlTable ?: invalidLocalConfig() }
    fun hasHelper(): Boolean = values.keys.any { it in setOf("api_key_helper", "credential_helper", "apiKeyHelper") }

    fun authorization(): String? {
        val keys = values.keys.filter { it.equals("Authorization", ignoreCase = true) }
        if (keys.size > 1) invalidLocalConfig(LocalAiImportFailure.UNSUPPORTED_CONFIG)
        return keys.singleOrNull()?.let(::text)
    }
}

/** Bounded TOML subset with explicit ownership for unrelated array tables and multiline text.
 * Connection fields never accept multiline strings or array tables as connection tables.
 */
internal class BoundedLocalConfigToml(input: String) {
    private val scanner = TomlScanner(input)
    private val strings = TomlStringReader(scanner)
    private var entries = 0
    private val root = TomlTable()
    private val sections = mutableSetOf<TomlTable>()

    fun parse(): TomlTable {
        var current = root
        scanner.whitespace(multiline = true)
        while (scanner.peek() != null) {
            if (scanner.take('[')) current = section() else assignment(current, 0)
            scanner.whitespace(multiline = false)
            if (scanner.peek() != null && scanner.peek() !in LINE_ENDINGS) invalidLocalConfig()
            scanner.whitespace(multiline = true)
        }
        return root
    }

    private fun section(): TomlTable {
        val array = scanner.take('[')
        val path = keyPath()
        scanner.expect(']')
        if (array) {
            scanner.expect(']')
            return arrayTable(path)
        }
        val table = tableAt(root, path)
        if (!sections.add(table)) invalidLocalConfig()
        return table
    }

    private fun arrayTable(path: List<String>): TomlTable {
        if (path.first() in CONNECTION_ROOTS) invalidLocalConfig(LocalAiImportFailure.UNSUPPORTED_CONFIG)
        countEntry()
        val parent = tableAt(root, path.dropLast(1))
        val tables = parent.values.getOrPut(path.last()) { TomlArrayTable() } as? TomlArrayTable
            ?: invalidLocalConfig()
        return TomlTable().also { tables.items.add(it) }
    }

    private fun keyPath(): List<String> {
        val keys = mutableListOf<String>()
        do {
            scanner.whitespace(multiline = false)
            val key = if (scanner.peek() in QUOTES) {
                strings.read().value
            } else {
                scanner.readWhile(::isBareKeyCharacter).takeIf { it.isNotEmpty() } ?: invalidLocalConfig()
            }
            if (keys.size >= MAX_KEY_DEPTH || key.length > MAX_KEY_LENGTH) invalidLocalConfig()
            keys += key
            scanner.whitespace(multiline = false)
        } while (scanner.take('.'))
        return keys
    }

    private fun assignment(table: TomlTable, depth: Int) {
        val path = keyPath()
        scanner.expect('=')
        scanner.whitespace(multiline = false)
        val value = value(depth)
        val parent = tableAt(table, path.dropLast(1))
        if (parent.values.putIfAbsent(path.last(), value) != null) invalidLocalConfig()
    }

    private fun value(depth: Int): TomlValue {
        if (depth > MAX_VALUE_DEPTH) invalidLocalConfig()
        countEntry()
        return when (scanner.peek()) {
            '"', '\'' -> strings.read(allowMultiline = true)
            '{' -> inlineTable(depth + 1)
            '[' -> array(depth + 1)
            else -> scanner.atom()
        }
    }

    private fun array(depth: Int): TomlValue {
        scanner.expect('[')
        scanner.whitespace(multiline = true)
        while (!scanner.take(']')) {
            value(depth)
            scanner.whitespace(multiline = true)
            if (scanner.take(']')) break
            scanner.expect(',')
            scanner.whitespace(multiline = true)
        }
        return TomlOther
    }

    private fun inlineTable(depth: Int): TomlTable {
        scanner.expect('{')
        val table = TomlTable(inline = true)
        scanner.whitespace(multiline = false)
        if (!scanner.take('}')) {
            do {
                assignment(table, depth)
                scanner.whitespace(multiline = false)
                if (scanner.take('}')) break
                scanner.expect(',')
            } while (true)
        }
        return table
    }

    private fun countEntry() {
        if (++entries > MAX_ENTRIES) invalidLocalConfig()
    }

    private companion object {
        const val MAX_KEY_DEPTH = 16
        const val MAX_KEY_LENGTH = 256
        const val MAX_VALUE_DEPTH = 16
        const val MAX_ENTRIES = 4096
        val QUOTES = setOf('"', '\'')
        val LINE_ENDINGS = setOf('\r', '\n')
        val CONNECTION_ROOTS = setOf("profiles", "model_providers", "model", "model_provider", "profile")
    }
}

private class TomlScanner(private val input: String) {
    private var position = 0

    fun peek(offset: Int = 0): Char? = input.getOrNull(position + offset)

    fun read(): Char {
        val character = peek() ?: invalidLocalConfig()
        position++
        return character
    }

    fun take(character: Char): Boolean = if (peek() == character) { position++; true } else false
    fun expect(character: Char) { if (!take(character)) invalidLocalConfig() }

    fun readWhile(predicate: (Char) -> Boolean): String {
        val start = position
        while (peek()?.let(predicate) == true) position++
        return input.substring(start, position)
    }

    fun whitespace(multiline: Boolean) {
        while (true) {
            when (peek()) {
                ' ', '\t' -> position++
                '\r', '\n' -> if (multiline) position++ else return
                '#' -> readWhile { it != '\r' && it != '\n' }
                else -> return
            }
        }
    }

    fun atom(): TomlValue {
        val atom = readWhile { !it.isWhitespace() && it !in ",]}#" }
        return when (atom) {
            "true" -> TomlBoolean(true)
            "false" -> TomlBoolean(false)
            else -> {
                if (!Regex("[+-]?(?:[0-9][0-9A-Za-z_.:+-]*|inf|nan)").matches(atom)) invalidLocalConfig()
                TomlOther
            }
        }
    }
}

private class TomlStringReader(private val scanner: TomlScanner) {
    fun read(allowMultiline: Boolean = false): TomlString {
        val quote = scanner.read()
        val multiline = openMultiline(quote, allowMultiline)
        val result = StringBuilder()
        while (true) {
            val character = scanner.read()
            when {
                character == quote -> if (closeQuotes(quote, multiline, result)) {
                    return TomlString(result.toString(), multiline)
                }
                character == '\\' && quote == '"' -> result.append(escape(multiline))
                else -> appendCharacter(character, multiline, result)
            }
        }
    }

    private fun openMultiline(quote: Char, allowed: Boolean): Boolean {
        val multiline = scanner.peek() == quote && scanner.peek(1) == quote
        if (multiline) {
            if (!allowed) invalidLocalConfig(LocalAiImportFailure.UNSUPPORTED_CONFIG)
            repeat(EXTRA_OPENING_QUOTES) { scanner.read() }
            if (scanner.take('\r')) scanner.expect('\n') else scanner.take('\n')
        }
        return multiline
    }

    private fun closeQuotes(quote: Char, multiline: Boolean, result: StringBuilder): Boolean {
        if (!multiline) return true
        var count = 1
        while (scanner.take(quote)) count++
        if (count > MAX_CLOSING_QUOTES) invalidLocalConfig()
        val closed = count >= DELIMITER_LENGTH
        repeat(if (closed) count - DELIMITER_LENGTH else count) { result.append(quote) }
        return closed
    }

    private fun escape(multiline: Boolean): String {
        if (multiline && scanner.peek() in CONTINUATION_WHITESPACE) {
            skipContinuation()
            return ""
        }
        return when (scanner.read()) {
            'b' -> "\b"
            't' -> "\t"
            'n' -> "\n"
            'f' -> "\u000c"
            'r' -> "\r"
            '"' -> "\""
            '\\' -> "\\"
            'u' -> unicode(SHORT_UNICODE_LENGTH)
            'U' -> unicode(LONG_UNICODE_LENGTH)
            else -> invalidLocalConfig()
        }
    }

    private fun skipContinuation() {
        var newline = false
        while (scanner.peek() in CONTINUATION_WHITESPACE) {
            when (scanner.read()) {
                '\n' -> newline = true
                '\r' -> {
                    scanner.expect('\n')
                    newline = true
                }
            }
        }
        if (!newline) invalidLocalConfig()
    }

    private fun appendCharacter(character: Char, multiline: Boolean, result: StringBuilder) {
        val lineEnding = character == '\n' || character == '\r'
        if (multiline && lineEnding) {
            if (character == '\r') scanner.expect('\n')
            result.append('\n')
        } else {
            val forbiddenControl = character.isISOControl() && character != '\t'
            if (lineEnding || forbiddenControl) invalidLocalConfig()
            result.append(character)
        }
    }

    private fun unicode(length: Int): String {
        val digits = buildString { repeat(length) { append(scanner.read()) } }
        if (digits.any { it.digitToIntOrNull(HEX_RADIX) == null }) invalidLocalConfig()
        val codePoint = digits.toLongOrNull(HEX_RADIX)?.takeIf {
            it in 0..MAX_CODE_POINT && it !in SURROGATE_RANGE
        }?.toInt() ?: invalidLocalConfig()
        return String(Character.toChars(codePoint))
    }

    private companion object {
        const val EXTRA_OPENING_QUOTES = 2
        const val DELIMITER_LENGTH = 3
        const val MAX_CLOSING_QUOTES = 5
        const val SHORT_UNICODE_LENGTH = 4
        const val LONG_UNICODE_LENGTH = 8
        const val HEX_RADIX = 16
        const val MAX_CODE_POINT = 0x10ffffL
        val SURROGATE_RANGE = 0xd800L..0xdfffL
        val CONTINUATION_WHITESPACE = setOf(' ', '\t', '\r', '\n')
    }
}

private fun isBareKeyCharacter(character: Char): Boolean {
    val letter = character in 'A'..'Z' || character in 'a'..'z'
    return letter || character in '0'..'9' || character in "_-"
}

private fun tableAt(table: TomlTable, path: List<String>): TomlTable {
    var current = table
    for (key in path) {
        val child = current.values.getOrPut(key) { TomlTable() }
        current = when (child) {
            is TomlTable -> child
            is TomlArrayTable -> child.items.lastOrNull() ?: invalidLocalConfig()
            else -> invalidLocalConfig()
        }
        if (current.inline) invalidLocalConfig()
    }
    return current
}
