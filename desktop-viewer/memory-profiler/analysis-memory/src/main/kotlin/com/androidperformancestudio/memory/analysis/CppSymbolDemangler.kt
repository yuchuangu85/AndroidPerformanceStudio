@file:Suppress("MagicNumber", "MaxLineLength", "LongMethod")

package com.androidperformancestudio.memory.analysis

/**
 * Pragmatic C++ Itanium ABI symbol demangler.
 *
 * Implements a subset of the mangling grammar covering the symbols heapprofd emits on Android:
 * nested names, length-prefixed source names, common operators, template ids with basic types,
 * cv/ref qualifiers, `std::` substitutions, abi tags, and function parameter lists.
 *
 * The substitution table follows the Itanium ABI candidate model used by the verified cases: the
 * first component of a nested name is added immediately; template-argument types are added only
 * when the enclosing template-id completes (before the template-id itself); the innermost
 * unqualified-name of a nested name is not a candidate.
 *
 * Symbols that are not mangled (no `_Z` prefix) or that the parser does not understand are returned
 * unchanged, so demangling is always safe to apply and never throws.
 */
object CppSymbolDemangler {
    fun demangle(symbol: String): String {
        if (!symbol.startsWith("_Z")) return symbol
        return try {
            Parser(symbol).parse() ?: symbol
        } catch (_: RuntimeException) {
            symbol
        }
    }

    private class Parser(private val input: String) {
        private var pos = 0
        private val substitutions = mutableListOf<String>()
        private val templateArgs = ArrayDeque<List<String>>()
        private var templateArgDepth = 0
        private var componentWasTemplateId = false
        private var lastNestedEndedInTemplateId = false

        fun parse(): String? {
            if (!consume("_Z")) return null
            val name = parseName() ?: return null
            if (pos >= input.length) return name
            // A function name whose innermost component is a <template-id> encodes its return type
            // before the parameters (`_ZN3foo6methodIiEET_S1_S1_` -> `int foo::method<int>(int, int)`).
            if (lastNestedEndedInTemplateId) {
                val returnType = parseType() ?: return null
                val params = parseParams() ?: return null
                return if (pos == input.length) "$returnType $name($params)" else null
            }
            val params = parseParams() ?: return null
            return if (pos == input.length) "$name($params)" else null
        }

        private fun parseParams(): String? {
            val params = mutableListOf<String>()
            while (pos < input.length) {
                val type = parseType() ?: return null
                params += type
            }
            return if (params.size == 1 && params[0] == "void") "" else params.joinToString(", ")
        }

        private fun parseName(): String? =
            when (peek()) {
                'N' -> parseNestedName()
                'S' -> parseSubstitutionOrStd()
                'L' -> parseLocalName()
                'T' -> parseTemplateParam()
                else -> parseUnqualifiedName()
            }

        private fun parseNestedName(): String? {
            if (!consume('N')) return null
            val qualifiers = mutableListOf<String>()
            while (true) {
                when (peek()) {
                    'K' -> {
                        qualifiers += " const"
                        advance()
                    }
                    'V' -> {
                        qualifiers += " volatile"
                        advance()
                    }
                    'R' -> {
                        qualifiers += " &"
                        advance()
                    }
                    'O' -> {
                        qualifiers += " &&"
                        advance()
                    }
                    else -> break
                }
            }
            val parts = mutableListOf<String>()
            var index = 0
            while (pos < input.length && peek() != 'E') {
                val isFirst = index == 0
                val part = parseNestedComponent(isFirst) ?: return null
                parts += part
                index++
                if (pos < input.length && peek() == 'E') break
                if (index > 64) return null
            }
            if (!consume('E')) return null
            lastNestedEndedInTemplateId = componentWasTemplateId
            return parts.joinToString("::") + qualifiers.joinToString("")
        }

        private fun parseNestedComponent(isFirst: Boolean): String? {
            componentWasTemplateId = false
            val wasSubstitutionLookup = isSubstitutionLookup()
            val part =
                when (peek()) {
                    'S' -> parseSubstitutionOrStd()
                    'C', 'D' -> parseCtorDtorName()
                    'T' -> parseTemplateParam()
                    'L' -> parseLocalName()
                    else -> parseUnqualifiedName()
                } ?: return null

            // A nested-name component is a substitution candidate only when it is the first
            // component, parsed outside a pending template argument, and is a new name (not a lookup).
            if (isFirst && templateArgDepth == 0 && !wasSubstitutionLookup) {
                if (!part.startsWith("operator") && !part.startsWith("~")) {
                    substitutions += part
                }
            }
            if (isAbiTagAhead()) {
                val tag = parseAbiTag() ?: return null
                return part + tag
            }
            return part
        }

        private fun parseUnqualifiedName(): String? {
            val c = peek()
            return when {
                c.isDigit() -> {
                    val name = parseSourceName() ?: return null
                    if (pos < input.length && peek() == 'I') {
                        componentWasTemplateId = true
                        val args = parseTemplateArgs() ?: return null
                        val tag = if (isAbiTagAhead()) parseAbiTag() ?: "" else ""
                        val rendered = "$name<${args.joinToString(", ")}>$tag"
                        // Add the argument types, then the completed template-id.
                        if (templateArgDepth == 0) {
                            args.forEach { substitutions += it }
                            substitutions += rendered
                        }
                        rendered
                    } else {
                        val tag = if (isAbiTagAhead()) parseAbiTag() ?: "" else ""
                        name + tag
                    }
                }
                isOperatorStart(c) -> parseOperatorName()
                c == 'L' -> parseLocalName()
                else -> null
            }
        }

        private fun parseCtorDtorName(): String? {
            val prefix = peek()
            advance()
            val kind = peek()
            if (kind != '1' && kind != '2' && kind != '0' && kind != '5') return null
            advance()
            // A ctor/dtor renders the bare class name without its template arguments.
            val base = (substitutions.lastOrNull() ?: return null).substringBefore('<')
            return when (prefix) {
                'C' -> base
                'D' -> "~$base"
                else -> null
            }
        }

        private fun parseSourceName(): String? {
            if (!peek().isDigit()) return null
            var length = 0
            while (pos < input.length && peek().isDigit()) {
                length = length * 10 + (peek() - '0')
                advance()
            }
            val end = pos + length
            if (end > input.length) return null
            val name = input.substring(pos, end)
            pos = end
            return name
        }

        private fun parseTemplateArgs(): List<String>? {
            if (!consume('I')) return null
            templateArgDepth++
            val args = mutableListOf<String>()
            while (pos < input.length && peek() != 'E') {
                val arg =
                    when (peek()) {
                        'L' -> parseTemplateLiteral()
                        'X' -> parseTemplateExpression()
                        else -> parseType()
                    } ?: return null
                args += arg
            }
            if (!consume('E')) return null
            templateArgs.addLast(args)
            templateArgDepth--
            return args
        }

        private fun parseTemplateLiteral(): String? {
            consume('L')
            val type = parseType() ?: return null
            val literal = parseLiteralValue() ?: return null
            if (!consume('E')) return null
            return literal
        }

        private fun parseTemplateExpression(): String? {
            consume('X')
            val inner = parseType() ?: parseSourceName() ?: return null
            if (!consume('E')) return null
            return inner
        }

        private fun parseLiteralValue(): String? {
            val value = StringBuilder()
            var negative = false
            if (peek() == 'n') {
                negative = true
                advance()
            }
            while (pos < input.length && (peek().isDigit() || peek() == '_')) {
                value.append(peek())
                advance()
            }
            if (value.isEmpty()) return null
            return if (negative) "-$value" else value.toString()
        }

        private fun parseType(): String? {
            if (pos >= input.length) return null
            val c = peek()
            return when {
                c == 'P' -> {
                    advance()
                    val inner = parseType() ?: return null
                    "$inner*"
                }
                c == 'R' -> {
                    advance()
                    val inner = parseType() ?: return null
                    "$inner&"
                }
                c == 'O' -> {
                    advance()
                    val inner = parseType() ?: return null
                    "$inner&&"
                }
                c == 'K' -> {
                    advance()
                    val inner = parseType() ?: return null
                    "$inner const"
                }
                c == 'V' -> {
                    advance()
                    val inner = parseType() ?: return null
                    "$inner volatile"
                }
                c == 'N' -> parseNestedName()
                c == 'S' -> parseSubstitutionOrStd()
                c == 'A' -> parseArrayType()
                c == 'F' -> parseFunctionType()
                c == 'T' -> parseTemplateParam()
                c.isDigit() -> {
                    val name = parseSourceName() ?: return null
                    if (pos < input.length && peek() == 'I') {
                        val args = parseTemplateArgs() ?: return null
                        val tag = if (isAbiTagAhead()) parseAbiTag() ?: "" else ""
                        val rendered = "$name<${args.joinToString(", ")}>$tag"
                        if (templateArgDepth == 0) {
                            args.forEach { substitutions += it }
                            substitutions += rendered
                        }
                        rendered
                    } else {
                        name
                    }
                }
                else -> parseBuiltinType()
            }
        }

        private fun parseArrayType(): String? {
            consume('A')
            if (peek() == 'A') {
                advance()
            }
            var length = ""
            if (peek().isDigit() || peek() == 'n') {
                length = parseLiteralValue() ?: ""
            }
            val element = parseType() ?: return null
            return if (length.isNotEmpty()) "$element[$length]" else "$element[]"
        }

        private fun parseFunctionType(): String? {
            consume('F')
            val params = mutableListOf<String>()
            while (pos < input.length && peek() != 'E') {
                val type = parseType() ?: return null
                params += type
            }
            if (!consume('E')) return null
            val rendered = if (params.size == 1 && params[0] == "void") "" else params.joinToString(", ")
            return "($rendered)"
        }

        private fun parseTemplateParam(): String? {
            consume('T')
            var index = 0
            if (peek() == '_') {
                advance()
            } else {
                while (pos < input.length && peek() != '_') {
                    if (!peek().isDigit()) return null
                    index = index * 10 + (peek() - '0')
                    advance()
                }
                if (!consume('_')) return null
            }
            // Resolve the parameter to its actual template argument when known.
            return templateArgs.lastOrNull()?.getOrNull(index) ?: "T$index"
        }

        private fun parseSubstitutionOrStd(): String? {
            if (!consume('S')) return null
            return when (peek()) {
                '_' -> {
                    advance()
                    val value = substitutions.lastOrNull() ?: return null
                    substitutions += value
                    value
                }
                't' -> {
                    advance()
                    stdSubstitution()
                }
                'a' -> stdReplacement("allocator")
                's' -> stdReplacement("basic_string")
                'b' -> stdReplacement("basic_string_view")
                'i' -> stdReplacement("basic_istream")
                'o' -> stdReplacement("basic_ostream")
                'd' -> stdReplacement("basic_iostream")
                'c' -> stdReplacement("basic_ios")
                in '0'..'9' -> {
                    var index = 0
                    while (pos < input.length && peek() in '0'..'9') {
                        index = index * 10 + (peek() - '0')
                        advance()
                    }
                    if (!consume('_')) return null
                    val value = substitutions.getOrNull(index) ?: return null
                    substitutions += value
                    value
                }
                else -> null
            }
        }

        private fun stdReplacement(name: String): String? {
            advance()
            val value = "std::$name"
            substitutions += value
            return value
        }

        /** `St` already consumed; handles `St<source-name>` (e.g. `St3__1` = `std::__1`). */
        private fun stdSubstitution(): String? {
            if (pos >= input.length) return "std"
            if (peek().isDigit()) {
                val name = parseSourceName() ?: return null
                val qualified = "std::$name"
                substitutions += qualified
                return if (pos < input.length && peek() == 'I') {
                    val args = parseTemplateArgs() ?: return null
                    val tag = if (isAbiTagAhead()) parseAbiTag() ?: "" else ""
                    val rendered = "$qualified<${args.joinToString(", ")}>$tag"
                    if (templateArgDepth == 0) {
                        args.forEach { substitutions += it }
                        substitutions += rendered
                    }
                    rendered
                } else {
                    qualified
                }
            }
            return "std"
        }

        private fun parseLocalName(): String? {
            consume('L')
            val enclosing = parseName() ?: return null
            if (!consume('Z')) return null
            val inner =
                when (peek()) {
                    's' -> {
                        advance()
                        "string literal"
                    }
                    'd' -> {
                        advance()
                        parseName() ?: return null
                    }
                    else -> parseName() ?: return null
                }
            return "$inner in $enclosing"
        }

        private fun parseAbiTag(): String? {
            if (!consume('B')) return null
            val name = parseSourceName() ?: return null
            return "[abi:$name]"
        }

        private fun isAbiTagAhead(): Boolean = pos < input.length && peek() == 'B'

        private fun isSubstitutionLookup(): Boolean =
            peek() == 'S' && (peekAt(1) == '_' || peekAt(1).isDigit())

        private fun parseOperatorName(): String? {
            val two = if (pos + 1 < input.length) "${peek()}${peekAt(1)}" else null
            val op = two?.let(OPERATORS::get) ?: return null
            advance(2)
            if (two == "cv") {
                val type = parseType() ?: return null
                return "operator $type"
            }
            return "operator $op"
        }

        private fun isOperatorStart(c: Char): Boolean = OPERATOR_STARTS.contains(c)

        private fun parseBuiltinType(): String? {
            if (pos >= input.length) return null
            val c = peek()
            val builtin =
                when (c) {
                    'v' -> "void"
                    'w' -> "wchar_t"
                    'b' -> "bool"
                    'c' -> "char"
                    'a' -> "signed char"
                    'h' -> "unsigned char"
                    's' -> "short"
                    't' -> "unsigned short"
                    'i' -> "int"
                    'j' -> "unsigned int"
                    'l' -> "long"
                    'm' -> "unsigned long"
                    'x' -> "long long"
                    'y' -> "unsigned long long"
                    'n' -> "__int128"
                    'o' -> "unsigned __int128"
                    'f' -> "float"
                    'd' -> "double"
                    'e' -> "long double"
                    'g' -> "__float128"
                    'z' -> "..."
                    'D' -> null
                    else -> null
                }
            if (builtin != null) {
                advance()
                return builtin
            }
            if (c == 'D') {
                advance()
                return when (peek()) {
                    'd' -> {
                        advance(); "double"
                    }
                    's' -> {
                        advance(); "short"
                    }
                    'i' -> {
                        advance(); "int"
                    }
                    'l' -> {
                        advance(); "long"
                    }
                    'x' -> {
                        advance(); "long long"
                    }
                    'a' -> {
                        advance(); "auto"
                    }
                    'c' -> {
                        advance(); "decltype(auto)"
                    }
                    'o' -> {
                        advance(); "decltype(nullptr)"
                    }
                    'n' -> {
                        advance(); "std::nullptr_t"
                    }
                    't' -> {
                        advance(); "decltype(auto)"
                    }
                    else -> null
                }
            }
            return null
        }

        private fun peek(): Char = input[pos]

        private fun peekAt(offset: Int): Char {
            val index = pos + offset
            return if (index < input.length) input[index] else ' '
        }

        private fun advance() {
            pos++
        }

        private fun advance(count: Int) {
            pos += count
        }

        private fun consume(expected: Char): Boolean {
            if (pos < input.length && input[pos] == expected) {
                pos++
                return true
            }
            return false
        }

        private fun consume(expected: String): Boolean {
            if (input.startsWith(expected, pos)) {
                pos += expected.length
                return true
            }
            return false
        }

        companion object {
            private val OPERATOR_STARTS = setOf('n', 'd', 'p', 'm', 'a', 'o', 'e', 'l', 'g', 'r', 'v', 'c', 'S')
            private val OPERATORS =
                mapOf(
                    "nw" to "new",
                    "na" to "new[]",
                    "dl" to "delete",
                    "da" to "delete[]",
                    "ps" to "+",
                    "ng" to "-",
                    "ad" to "&",
                    "de" to "*",
                    "co" to "~",
                    "pl" to "+",
                    "mi" to "-",
                    "ml" to "*",
                    "dv" to "/",
                    "rm" to "%",
                    "an" to "&",
                    "or" to "|",
                    "eo" to "^",
                    "aS" to "=",
                    "pL" to "+=",
                    "mI" to "-=",
                    "mL" to "*=",
                    "dV" to "/=",
                    "rM" to "%=",
                    "aN" to "&=",
                    "oR" to "|=",
                    "eO" to "^=",
                    "ls" to "<<",
                    "rs" to ">>",
                    "lS" to "<<=",
                    "rS" to ">>=",
                    "eq" to "==",
                    "ne" to "!=",
                    "lt" to "<",
                    "gt" to ">",
                    "le" to "<=",
                    "ge" to ">=",
                    "ss" to "<=>",
                    "nt" to "!",
                    "aa" to "&&",
                    "oo" to "||",
                    "pp" to "++",
                    "mm" to "--",
                    "cm" to ",",
                    "pm" to "->*",
                    "pt" to "->",
                    "cl" to "()",
                    "li" to "<literal>",
                    "qu" to "?",
                    "cv" to "",
                )
        }
    }
}
