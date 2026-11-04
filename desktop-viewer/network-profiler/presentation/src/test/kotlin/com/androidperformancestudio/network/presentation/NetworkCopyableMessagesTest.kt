package com.androidperformancestudio.network.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class NetworkCopyableMessagesTest {
    @Test
    fun `coverage limitations request empty states and failure reasons are copyable`() {
        val screen = source("NetworkProfilerScreen.kt")
        listOf(
            "har_timing_notice",
            "no_requests",
            "select_a_call_to_inspect_phase_evidence",
            "timing_confidence_legend",
        ).forEach { resource -> assertCopyable(screen, "localizedStringResource(Res.string.$resource, language)") }
        assertCopyable(screen, "listOfNotNull(failure.type, failure.message).joinToString(\": \")")
        assertCopyable(screen, "emptyLabel")
        assertCopyable(screen, "unavailable")
        val coverage = screen.substringAfter("private fun EvidenceCoverage(").substringBefore("private fun RequestTable(")
        listOf("coverage_summary", "not_covered").forEach { resource ->
            assertTrue(
                Regex("CopyableText\\(\\s*localizedStringResource\\(\\s*Res.string.$resource").containsMatchIn(coverage),
            )
        }
        assertTrue(coverage.contains("maxLines = 2"))
        assertTrue(coverage.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(screen.contains("                Text(call.method, fontWeight = FontWeight.SemiBold"))
        assertTrue(screen.contains("if (durationNs == null) {\n            CopyableText("))
        assertTrue(Regex("\\bText\\(\\s*formatDurationNs\\(durationNs\\)").containsMatchIn(screen))
        assertTrue(screen.contains("onSelectCall = actions.selectCall"))
    }

    @Test
    fun `activity and domain empty states and incomplete evidence warnings are copyable`() {
        val panels = source("NetworkWorkspacePanels.kt")
        listOf("network_activity_unavailable", "network_activity_omitted", "network_partial_bytes", "no_requests")
            .forEach { resource ->
                assertTrue(
                    Regex("CopyableText\\(\\s*localizedStringResource\\(Res.string.$resource,").containsMatchIn(panels),
                    resource,
                )
            }
        assertCopyable(panels, "notice")
        assertTrue(panels.contains("Canvas("))
        assertTrue(panels.contains("                        Text(value, Modifier.weight(if (index == 0) 2f else 1f)"))
        assertTrue(panels.contains("onOutcomeChange(value)"))
    }

    private fun source(name: String): String =
        Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/network/presentation/$name"))

    private fun assertCopyable(source: String, argument: String) {
        assertTrue(Regex("CopyableText\\(\\s*" + Regex.escape(argument)).containsMatchIn(source), argument)
    }
}
