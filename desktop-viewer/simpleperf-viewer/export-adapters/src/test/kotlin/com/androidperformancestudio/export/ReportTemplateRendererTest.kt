@file:Suppress("MaxLineLength", "ktlint:standard:max-line-length")

package com.androidperformancestudio.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReportTemplateRendererTest {
    @Test
    fun `renders named report fields and fails closed on missing values`() {
        val template = ReportTemplate("ci", "# {{title}}\nRegressions: {{regressions}}")
        assertEquals(
            "# Build 42\nRegressions: 3",
            ReportTemplateRenderer().render(
                template,
                mapOf(
                    "title" to "Build 42",
                    "regressions" to "3",
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> { ReportTemplateRenderer().render(template, mapOf("title" to "Build")) }
    }
}
