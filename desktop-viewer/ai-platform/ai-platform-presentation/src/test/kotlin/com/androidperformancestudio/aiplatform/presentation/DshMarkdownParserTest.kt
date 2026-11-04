package com.androidperformancestudio.aiplatform.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DshMarkdownParserTest {
    @Test
    fun `empty and whitespace only input has no blocks`() {
        listOf("", "\n", " \n\t\n").forEach { assertEquals(emptyList(), parseDshMarkdown(it)) }
    }

    @Test
    fun `paragraphs fold soft lines and preserve two space hard breaks`() {
        assertEquals(
            listOf(paragraph("one two\nthree four"), paragraph("next")),
            parseDshMarkdown("one\ntwo  \nthree\nfour\n\nnext"),
        )
        assertEquals(listOf(paragraph("one two")), parseDshMarkdown("one\r\ntwo\r\n"))
    }

    @Test
    fun `atx and setext headings include empty atx and multiline setext`() {
        assertEquals(
            listOf(
                DshMarkdownBlock.Heading(1, "title"),
                DshMarkdownBlock.Heading(6, "six"),
                DshMarkdownBlock.Heading(2, ""),
                DshMarkdownBlock.Heading(1, "setext title"),
                DshMarkdownBlock.Heading(2, "subtitle"),
                paragraph("#not-heading ####### seven"),
            ),
            parseDshMarkdown("# title ###\n###### six\n##\n\nsetext\ntitle\n===\nsubtitle\n---\n\n#not-heading\n####### seven"),
        )
    }

    @Test
    fun `rules do not become empty lists or single column tables`() {
        assertEquals(
            listOf(DshMarkdownBlock.Rule, DshMarkdownBlock.Rule, DshMarkdownBlock.Rule, paragraph("tail")),
            parseDshMarkdown("---\n* * *\n___\ntail"),
        )
        assertEquals(listOf(DshMarkdownBlock.Heading(2, "title")), parseDshMarkdown("title\n---"))
    }

    @Test
    fun `fenced code preserves whitespace and markdown without inline rewrites`() {
        val code = "  # literal  \n\t[x](file:///secret)\n\n| a \\| b |\n**bold**"
        assertEquals(
            listOf(DshMarkdownBlock.Code(code, "kotlin"), paragraph("after")),
            parseDshMarkdown("```kotlin extra-info\n$code\n```\nafter"),
        )
    }

    @Test
    fun `fence closers need matching character sufficient length and no suffix`() {
        val code = "```\n~~~\n```` suffix\n# still code"
        assertEquals(
            listOf(DshMarkdownBlock.Code(code, "txt"), paragraph("done")),
            parseDshMarkdown("````txt\n$code\n`````\ndone"),
        )
        assertEquals(
            listOf(DshMarkdownBlock.Code("```\n~~", "text")),
            parseDshMarkdown("~~~text\n```\n~~\n~~~"),
        )
    }

    @Test
    fun `unclosed and empty fences keep all remaining code`() {
        assertEquals(listOf(DshMarkdownBlock.Code("a  \n\n")), parseDshMarkdown("```\na  \n\n"))
        assertEquals(listOf(DshMarkdownBlock.Code("")), parseDshMarkdown("```\n```"))
        assertEquals(listOf(DshMarkdownBlock.Code("")), parseDshMarkdown("~~~"))
        assertEquals(listOf(paragraph("```bad`info text")), parseDshMarkdown("```bad`info\ntext"))
    }

    @Test
    fun `indented fences remove only opening indentation`() {
        assertEquals(
            listOf(DshMarkdownBlock.Code("  x\n y\n```\n    ```")),
            parseDshMarkdown("  ````\n    x\n   y\n  ```\n      ```\n  ````"),
        )
    }

    @Test
    fun `indented code keeps internal blanks tabs and trailing spaces`() {
        assertEquals(
            listOf(DshMarkdownBlock.Code("x  \n\n\t# raw"), paragraph("tail")),
            parseDshMarkdown("    x  \n\n\t\t# raw\n\ntail"),
        )
        assertEquals(listOf(paragraph("body not code")), parseDshMarkdown("body\n    not code"))
    }

    @Test
    fun `quotes contain nested blocks and preserve explicitly quoted blank lines`() {
        assertEquals(
            listOf(
                DshMarkdownBlock.Quote(
                    listOf(
                        DshMarkdownBlock.Heading(1, "title"),
                        paragraph("first second"),
                        DshMarkdownBlock.Quote(listOf(paragraph("nested"))),
                        DshMarkdownBlock.Code("# code", "txt"),
                    ),
                ),
                paragraph("outside"),
            ),
            parseDshMarkdown("> # title\n> first\n> second\n>\n> > nested\n>\n> ~~~txt\n> # code\n> ~~~\noutside"),
        )
        assertEquals(listOf(DshMarkdownBlock.Quote(emptyList())), parseDshMarkdown(">"))
    }

    @Test
    fun `mixed nested lists preserve start task state and multiline item bodies`() {
        val nested =
            DshMarkdownBlock.ListBlock(
                true,
                3,
                listOf(
                    DshMarkdownListItem(listOf(paragraph("child continued")), false),
                    DshMarkdownListItem(listOf(paragraph("done")), true),
                ),
            )
        assertEquals(
            listOf(
                DshMarkdownBlock.ListBlock(
                    false,
                    1,
                    listOf(
                        DshMarkdownListItem(listOf(paragraph("parent wraps"), nested, paragraph("second paragraph")), true),
                        DshMarkdownListItem(listOf(paragraph("sibling"))),
                    ),
                ),
            ),
            parseDshMarkdown("- [x] parent\n  wraps\n  3. [ ] child\n     continued\n  4. [X] done\n\n  second paragraph\n- sibling"),
        )
    }

    @Test
    fun `list item contains quote code and multiple paragraphs`() {
        assertEquals(
            listOf(
                DshMarkdownBlock.ListBlock(
                    true,
                    7,
                    listOf(
                        DshMarkdownListItem(
                            listOf(
                                paragraph("first lazy line"),
                                DshMarkdownBlock.Quote(listOf(paragraph("quote"))),
                                DshMarkdownBlock.Code("  # raw  ", "txt"),
                                paragraph("last"),
                            ),
                        ),
                    ),
                ),
                paragraph("outside"),
            ),
            parseDshMarkdown("7) first\nlazy line\n\n   > quote\n\n   ```txt\n     # raw  \n   ```\n\n   last\n\noutside"),
        )
    }

    @Test
    fun `empty items and marker changes do not lose content`() {
        assertEquals(
            listOf(
                DshMarkdownBlock.ListBlock(false, 1, listOf(DshMarkdownListItem(emptyList()))),
                DshMarkdownBlock.ListBlock(false, 1, listOf(DshMarkdownListItem(listOf(paragraph("[?] literal"))))),
                DshMarkdownBlock.ListBlock(true, 0, listOf(DshMarkdownListItem(listOf(paragraph("zero"))))),
            ),
            parseDshMarkdown("-\n+ [?] literal\n0. zero"),
        )
    }

    @Test
    fun `tables keep escaped and code pipes with alignments and normalized row widths`() {
        assertEquals(
            listOf(
                DshMarkdownBlock.Table(
                    listOf("left", "center", "right"),
                    listOf(
                        listOf("a\\|b", "`c|d`", "``e`|f``"),
                        listOf("short", "", ""),
                        listOf("1", "2", "3"),
                    ),
                    listOf(DshMarkdownAlignment.LEFT, DshMarkdownAlignment.CENTER, DshMarkdownAlignment.RIGHT),
                ),
                DshMarkdownBlock.Heading(2, "after"),
            ),
            parseDshMarkdown(
                "| left | center | right |\n| :--- | :---: | ---: |\n| a\\|b | `c|d` | ``e`|f`` |\nshort\n| 1 | 2 | 3 | ignored |\n## after",
            ),
        )
    }

    @Test
    fun `single column table supports empty and short cells`() {
        assertEquals(
            listOf(
                DshMarkdownBlock.Table(
                    listOf("name"),
                    listOf(listOf(""), listOf("value")),
                    listOf(DshMarkdownAlignment.LEFT),
                ),
            ),
            parseDshMarkdown("| name |\n| --- |\n||\nvalue"),
        )
        assertIs<DshMarkdownBlock.Paragraph>(parseDshMarkdown("a | b\n--- | nope").single())
        assertIs<DshMarkdownBlock.Paragraph>(parseDshMarkdown("a | b\n| --- |").single())
    }

    @Test
    fun `unmatched backticks and escaped backslashes do not hide table separators`() {
        val table = assertIs<DshMarkdownBlock.Table>(parseDshMarkdown("a | b\n--- | ---\n`open | tail\na\\\\|b").single())
        assertEquals(listOf(listOf("`open", "tail"), listOf("a\\\\", "b")), table.rows)
    }

    @Test
    fun `unsupported inline html links images and syntax remain literal`() {
        val text = "<script>alert(1)</script> [go](javascript:alert(1)) ![img](file:///tmp/a) **bold** ~~strike~~ &amp; \\*raw\\*"
        assertEquals(listOf(paragraph(text)), parseDshMarkdown(text))
        assertEquals(
            listOf(paragraph("[id]: https://example.test/path {extension}")),
            parseDshMarkdown("[id]: https://example.test/path\n{extension}"),
        )
    }

    @Test
    fun `pathological quotes stop at twelve containers and retain literal remainder`() {
        var blocks = parseDshMarkdown("> ".repeat(1000) + "# intact  \n" + "> ".repeat(1000) + "tail")
        repeat(12) { blocks = assertIs<DshMarkdownBlock.Quote>(blocks.single()).blocks }
        assertEquals(listOf(paragraph("> ".repeat(988) + "# intact  \n" + "> ".repeat(988) + "tail")), blocks)
    }

    @Test
    fun `pathological mixed containers retain the literal remainder after the depth budget`() {
        for (pairs in listOf(6, 100)) {
            // Continuation lines retain every quote marker and indent under each list item.
            val input = "- > ".repeat(pairs) + "# intact  \n" + "  > ".repeat(pairs) + "tail"
            var blocks = parseDshMarkdown(input)
            repeat(6) {
                val list = assertIs<DshMarkdownBlock.ListBlock>(blocks.single())
                blocks =
                    assertIs<DshMarkdownBlock.Quote>(
                        list.items
                            .single()
                            .blocks
                            .single(),
                    ).blocks
            }
            val remaining = pairs - 6
            assertEquals(
                listOf(paragraph("- > ".repeat(remaining) + "# intact  \n" + "  > ".repeat(remaining) + "tail")),
                blocks,
            )
        }
    }

    @Test
    fun `long output is never silently truncated`() {
        val text = "x".repeat(100_000)
        assertEquals(listOf(paragraph(text)), parseDshMarkdown(text))
        assertEquals(listOf(DshMarkdownBlock.Code(text)), parseDshMarkdown("```\n$text\n```"))
    }

    private fun paragraph(text: String) = DshMarkdownBlock.Paragraph(text)
}
