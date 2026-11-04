package com.androidperformancestudio.aiplatform.presentation

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DshMarkdownInlineTest {
    @Test
    fun `nested emphasis retains all active styles`() {
        val result = render("**bold _italic ~~deleted~~_ tail**")
        assertEquals("bold italic deleted tail", result.text)
        val deleted = result.styleAt(result.text.indexOf("deleted"))
        assertEquals(FontWeight.SemiBold, deleted.fontWeight)
        assertEquals(FontStyle.Italic, deleted.fontStyle)
        assertEquals(TextDecoration.LineThrough, deleted.textDecoration)
        assertEquals(FontWeight.SemiBold, result.styleAt(result.text.indexOf("tail")).fontWeight)
    }

    @Test
    fun `asterisk and underscore nesting and triple runs work`() {
        for (input in listOf("***both***", "___both___", "**_both_**", "__*both*__", "*__both__*")) {
            val result = render(input)
            assertEquals("both", result.text, input)
            assertEquals(FontWeight.SemiBold, result.styleAt(0).fontWeight, input)
            assertEquals(FontStyle.Italic, result.styleAt(0).fontStyle, input)
        }
        assertEquals("outer inner", render("*outer **inner***").text)
        assertEquals("outer inner", render("**outer *inner***").text)
    }

    @Test
    fun `backtick delimiters must match their whole run and preserve code literally`() {
        for (width in listOf(1, 2, 3, 8, 31)) {
            val fence = "`".repeat(width)
            val code = if (width == 1) "**raw** \\_ x" else "**raw** ` x \\_"
            val result = render("$fence$code$fence")
            assertEquals(code, result.text)
            assertEquals(FontFamily.Monospace, result.styleAt(0).fontFamily)
            assertEquals(12.sp, result.styleAt(0).fontSize)
            assertEquals(0.sp, result.styleAt(0).letterSpacing)
        }
        assertEquals("a `` b", render("```a `` b```").text)
    }

    @Test
    fun `escaped punctuation is literal and ordinary backslashes survive`() {
        val result = render("\\*\\*raw\\*\\* \\_x\\_ \\~\\~x\\~\\~ \\`x\\` \\[x\\] \\! C:\\temp")
        assertEquals("**raw** _x_ ~~x~~ `x` [x] ! C:\\temp", result.text)
        assertTrue(result.spanStyles.isEmpty())
        assertEquals("\\bold", render("\\\\**bold**").text)
    }

    @Test
    fun `unclosed and pathological markup falls back without dropping content`() {
        for (input in listOf("**unclosed", "_unclosed", "~~unclosed", "``a`", "***", "****x****", "[x](https://example.com", "![**alt**")) {
            assertEquals(input, render(input).text, input)
        }
        val nested = (1..20).fold("value") { value, index -> if (index % 2 == 0) "**$value**" else "_${value}_" }
        assertEquals(nested, render(nested).text)
        val pathological = "[".repeat(2000) + "**payload**"
        assertEquals(pathological, render(pathological).text)
    }

    @Test
    fun `intraword underscores and headings remain literal`() {
        for (input in listOf("foo_bar_baz", "foo__bar__baz", "# Heading", "## Heading", "a * b", "trailing \\")) {
            val result = render(input)
            assertEquals(input, result.text)
            assertTrue(result.spanStyles.isEmpty())
        }
    }

    @Test
    fun `safe links show styled labels without any executable annotations`() {
        for (target in listOf("https://example.com/a_(b)?x=1#part", "http://example.com", "HTTPS://example.com")) {
            val result = render("[**read _this_**]($target)")
            assertEquals("read this", result.text)
            val style = result.styleAt(result.text.indexOf("this"))
            assertEquals(DshChatColors.Light.send, style.color)
            assertEquals(TextDecoration.Underline, style.textDecoration)
            assertEquals(FontWeight.SemiBold, style.fontWeight)
            assertEquals(FontStyle.Italic, style.fontStyle)
            assertNoActions(result)
        }
    }

    @Test
    fun `unsafe links and their label markup remain verbatim`() {
        for (target in listOf(
            "file:///private/secret",
            "javascript:alert(1)",
            "data:text/html,<script>x</script>",
            "ftp://host/file",
            "/private/file",
            "https:",
            "https://",
            "https://bad host",
        )) {
            val input = "[**secret**]($target)"
            val result = render(input)
            assertEquals(input, result.text)
            assertTrue(result.spanStyles.isEmpty())
            assertNoActions(result)
        }
    }

    @Test
    fun `html and images remain inert source including embedded markup`() {
        for (input in listOf(
            "<script>**secret** alert(1)</script>",
            "<a href='javascript:alert(1)'>_secret_</a>",
            "<span><span>**nested**</span>_tail_</span>",
            "<img src='file:///private/secret' alt='**raw**'>",
            "![**alt**](https://example.com/image.png)",
            "![_alt_][image]",
            "![**alt**]",
            "<!-- **raw** -->",
        )) {
            val result = render(input)
            assertEquals(input, result.text, input)
            assertTrue(result.spanStyles.isEmpty(), input)
            assertNoActions(result)
        }
    }

    @Test
    fun `theme colors and combined decorations survive code and links`() {
        for (colors in listOf(DshChatColors.Light, DshChatColors.Dark)) {
            val result = dshInlineMarkdown("~~[**link**](https://example.com)~~ and **`code`**", colors)
            val link = result.styleAt(0)
            assertEquals(colors.send, link.color)
            assertTrue(link.textDecoration!!.contains(TextDecoration.Underline))
            assertTrue(link.textDecoration!!.contains(TextDecoration.LineThrough))
            val code = result.styleAt(result.text.indexOf("code"))
            assertEquals(colors.sidebar, code.background)
            assertEquals(colors.text, code.color)
            assertEquals(FontWeight.SemiBold, code.fontWeight)
            assertNoActions(result)
        }
    }

    private fun render(text: String): AnnotatedString = dshInlineMarkdown(text, DshChatColors.Light)

    private fun AnnotatedString.styleAt(index: Int): SpanStyle =
        spanStyles.filter { index >= it.start && index < it.end }.fold(SpanStyle()) { style, range -> style.merge(range.item) }

    private fun assertNoActions(result: AnnotatedString) {
        assertTrue(result.getStringAnnotations(0, result.length).isEmpty())
        // Exact equality also rejects LinkAnnotation and any future annotation type.
        assertEquals(AnnotatedString(result.text, result.spanStyles), result)
        assertFalse(result.spanStyles.any { it.item.letterSpacing.isSp && it.item.letterSpacing.value < 0 })
    }
}
