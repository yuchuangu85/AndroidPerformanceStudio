package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class SimpleperfCopyableDialogsTest {
    @Test
    fun `AI consent details and failure dialogs expose copyable original messages`() {
        val source = source("SimpleperfMainPage.kt")
        assertCopyable(source, "localizedStringResource( Res.string.sp_ai_run_details")
        assertCopyable(source, "localizedStringResource(Res.string.sp_ai_failed_title")
        assertCopyable(source, "message,")
        assertTrue(source.contains("Text(localizedStringResource(Res.string.sp_ai_close,"))
        assertTrue(source.contains("Text(localizedStringResource(Res.string.sp_ai_analyze,"))
    }

    @Test
    fun `AI summary findings explanations recommendations and empty state can be copied`() {
        val source = source("SimpleperfAiInsightDrawer.kt")
        listOf(
            "insight.result.summary,",
            "finding.title,",
            "finding.explanation,",
            "finding.recommendation,",
            "localizedStringResource(Res.string.sp_ai_no_findings",
        ).forEach { assertCopyable(source, it) }
        assertTrue(source.contains("onOpenSourceCandidate(candidateId)"))
        assertTrue(source.contains("Text(localizedStringResource(Res.string.sp_ai_close,"))
    }

    private fun assertCopyable(source: String, argument: String) {
        val normalized = source.replace(Regex("\\s+"), " ")
        val call = Regex("\\bCopyableText\\(\\s*(?:text\\s*=\\s*)?" + Regex.escape(argument))
        assertTrue(call.containsMatchIn(normalized), "Missing copyable dialog message: $argument")
    }

    private fun source(file: String): String =
        Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop", file))
}
