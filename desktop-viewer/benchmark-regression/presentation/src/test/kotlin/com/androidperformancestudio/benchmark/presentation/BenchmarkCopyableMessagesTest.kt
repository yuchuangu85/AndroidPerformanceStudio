package com.androidperformancestudio.benchmark.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class BenchmarkCopyableMessagesTest {
    @Test
    fun `report gate reasons inspector explanations and empty states are copyable`() {
        val reports = source("BenchmarkReportsScreen.kt")
        listOf("no_comparisons", "no_reports", "inspect_hint", "inspector_no_issues")
            .forEach { resource -> assertCopyable(reports, "text = localizedStringResource(Res.string.$resource, language)") }
        assertCopyable(reports, "text = reason.displayName(language)")
        assertCopyable(reports, "issue.format()")
        assertCopyable(reports, "text = reason,")
        val status = reports.substringAfter("private fun StatusLine(").substringBefore("private fun GateDecision.displayName(")
        assertCopyable(status, "text = status,")
        assertTrue(status.contains("maxLines = 1"))
        assertTrue(status.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(Regex("\\bText\\(\\s*text = comparison.metricName").containsMatchIn(reports))
        assertTrue(reports.contains("onClick = actions.onGenerateReport"))
    }

    @Test
    fun `comparison and trend empty states and trend warnings are copyable but chart axes stay plain`() {
        assertCopyable(source("BenchmarkComparisonTable.kt"), "text = localizedStringResource(Res.string.no_comparisons, language)")
        val trend = source("BenchmarkTrendPanel.kt")
        assertCopyable(trend, "text = localizedStringResource(Res.string.trend_empty, language)")
        val warning = trend.substringAfter("private fun TrendWarning(").substringBefore("private fun TrendChart(")
        assertCopyable(warning, "text = text,")
        assertTrue(warning.contains("maxLines = 2"))
        assertTrue(warning.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(trend.contains("            Text(formatAxis(max)"))
    }

    private fun source(name: String): String =
        Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/benchmark/presentation/$name"))

    private fun assertCopyable(source: String, argument: String) {
        assertTrue(Regex("CopyableText\\(\\s*" + Regex.escape(argument)).containsMatchIn(source), argument)
    }
}
