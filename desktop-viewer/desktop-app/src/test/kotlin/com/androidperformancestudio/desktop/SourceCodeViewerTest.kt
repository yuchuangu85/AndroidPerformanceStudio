package com.androidperformancestudio.desktop

import androidx.compose.ui.graphics.Color
import com.androidperformancestudio.source.SourceLanguage
import com.androidperformancestudio.source.SourceRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceCodeViewerTest {
    @Test
    fun `Kotlin highlighting keeps comment markers inside strings as strings`() {
        val line = highlightSource("val url = \"https://example.test\" // trailing note", SourceLanguage.KOTLIN).single()

        assertHasToken(line, SourceTokenKind.KEYWORD, "val")
        assertHasToken(line, SourceTokenKind.STRING, "\"https://example.test\"")
        assertHasToken(line, SourceTokenKind.COMMENT, "// trailing note")
        assertEquals(1, line.tokens.count { it.kind == SourceTokenKind.COMMENT })
    }

    @Test
    fun `Kotlin block comments remain highlighted across lines`() {
        val lines =
            highlightSource(
                """
                /* start
                val ignored = 1
                */ val present = 2
                """.trimIndent(),
                SourceLanguage.KOTLIN,
            )

        assertHasToken(lines[0], SourceTokenKind.COMMENT, "/* start")
        assertHasToken(lines[1], SourceTokenKind.COMMENT, "val ignored = 1")
        assertFalse(lines[1].tokens.any { it.kind == SourceTokenKind.KEYWORD })
        assertHasToken(lines[2], SourceTokenKind.COMMENT, "*/")
        assertHasToken(lines[2], SourceTokenKind.KEYWORD, "val")
        assertHasToken(lines[2], SourceTokenKind.NUMBER, "2")
    }

    @Test
    fun `XML highlighting separates tags attributes and values`() {
        val line =
            highlightSource(
                "<LinearLayout android:id=\"@+id/root\" enabled=\"true\">",
                SourceLanguage.XML,
            ).single()

        assertHasToken(line, SourceTokenKind.TAG, "LinearLayout")
        assertHasToken(line, SourceTokenKind.ATTRIBUTE, "android:id")
        assertHasToken(line, SourceTokenKind.VALUE, "\"@+id/root\"")
        assertHasToken(line, SourceTokenKind.ATTRIBUTE, "enabled")
        assertHasToken(line, SourceTokenKind.VALUE, "\"true\"")
    }

    @Test
    fun `C and C plus plus highlighting recognizes directives keywords and comments`() {
        val cLine = highlightSource("#include <stdio.h>", SourceLanguage.C).single()
        val cppLine = highlightSource("constexpr int answer = 42; // immutable", SourceLanguage.CPP).single()

        assertHasToken(cLine, SourceTokenKind.PREPROCESSOR, "#include")
        assertHasToken(cppLine, SourceTokenKind.KEYWORD, "constexpr")
        assertHasToken(cppLine, SourceTokenKind.KEYWORD, "int")
        assertHasToken(cppLine, SourceTokenKind.NUMBER, "42")
        assertHasToken(cppLine, SourceTokenKind.COMMENT, "// immutable")
    }

    @Test
    fun `One Dark source palette maps editor and syntax colors consistently`() {
        assertEquals(Color(0xFF282C34), OneDarkSourceTheme.editorBackground)
        assertEquals(Color(0xFFABB2BF), OneDarkSourceTheme.foreground)
        assertEquals(Color(0xFF495162), OneDarkSourceTheme.lineNumber)
        assertEquals(Color(0xFF2C313C), OneDarkSourceTheme.currentLine)
        assertEquals(Color(0xFF404859), OneDarkSourceTheme.selection)
        assertEquals(Color(0xFFC678DD), OneDarkSourceTheme.colorFor(SourceTokenKind.KEYWORD))
        assertEquals(Color(0xFFC678DD), OneDarkSourceTheme.colorFor(SourceTokenKind.PREPROCESSOR))
        assertEquals(Color(0xFF98C379), OneDarkSourceTheme.colorFor(SourceTokenKind.STRING))
        assertEquals(Color(0xFF98C379), OneDarkSourceTheme.colorFor(SourceTokenKind.VALUE))
        assertEquals(Color(0xFFD19A66), OneDarkSourceTheme.colorFor(SourceTokenKind.NUMBER))
        assertEquals(Color(0xFFD19A66), OneDarkSourceTheme.colorFor(SourceTokenKind.ATTRIBUTE))
        assertEquals(Color(0xFF5C6370), OneDarkSourceTheme.colorFor(SourceTokenKind.COMMENT))
        assertEquals(Color(0xFFE5C07B), OneDarkSourceTheme.colorFor(SourceTokenKind.ANNOTATION))
        assertEquals(Color(0xFFE06C75), OneDarkSourceTheme.colorFor(SourceTokenKind.TAG))
    }

    @Test
    fun `unknown source preserves all text without syntax tokens`() {
        val line = highlightSource("plain <text> // untouched", SourceLanguage.OTHER).single()

        assertEquals("plain <text> // untouched", line.text)
        assertTrue(line.tokens.isEmpty())
    }

    @Test
    fun `source range helpers clamp scrolling and include the full target range`() {
        assertEquals(2, sourceLineIndex(SourceRange(startLine = 3), lineCount = 5))
        assertEquals(null, sourceLineIndex(SourceRange(startLine = 6), lineCount = 5))
        assertTrue(sourceLineIsHighlighted(5, SourceRange(startLine = 8, endLine = 5)))
        assertTrue(sourceLineIsHighlighted(8, SourceRange(startLine = 8, endLine = 5)))
        assertFalse(sourceLineIsHighlighted(4, SourceRange(startLine = 8, endLine = 5)))
    }

    private fun assertHasToken(
        line: HighlightedSourceLine,
        kind: SourceTokenKind,
        value: String,
    ) {
        assertTrue(
            line.tokens.any { token ->
                token.kind == kind && line.text.substring(token.start, token.end) == value
            },
            "Expected $kind token '$value' in '${line.text}', actual: ${line.tokens}",
        )
    }
}
