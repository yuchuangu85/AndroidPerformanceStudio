package com.androidperformancestudio.perfetto.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class PerfettoCopyableMessagesTest {
    @Test
    fun `device refresh failures status and initial trace notices expose full message copying`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/perfetto/app/PerfettoMainPage.kt"))
        val normalized = source.replace(Regex("\\s+"), " ")
        listOf(
            "localizedStringResource(Res.string.device_refresh_failed",
            "statusText,",
            "notice,",
        ).forEach { argument ->
            val call = Regex("\\bCopyableText\\(\\s*(?:text\\s*=\\s*)?" + Regex.escape(argument))
            assertTrue(call.containsMatchIn(normalized), "Missing full-message copy entry point: $argument")
        }
        val footer = source.substringAfter("private fun PerfettoStatusBar(").substringBefore("private fun RowScope.PerfettoToolbarContent(")
        assertTrue(Regex("SelectionContainer \\{\\s*Row\\(").containsMatchIn(footer), "Retain footer text selection")
        assertTrue(source.contains(".horizontalScroll(rememberScrollState())"))
    }
}
