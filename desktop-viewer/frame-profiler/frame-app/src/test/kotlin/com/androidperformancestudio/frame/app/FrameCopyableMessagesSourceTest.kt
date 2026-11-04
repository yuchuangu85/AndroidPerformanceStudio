package com.androidperformancestudio.frame.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameCopyableMessagesSourceTest {
    private val workspace =
        Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/frame/app/FrameProfilerMainPage.kt"))
    private val screen =
        Files.readString(
            Path.of("../presentation/src/main/kotlin/com/androidperformancestudio/frame/presentation/FrameProfilerScreen.kt"),
        )

    @Test
    fun `empty states warnings and investigation hints use the shared copyable message`() {
        val resources =
            listOf(
                "no_frames_match_filter",
                "select_a_debuggable_process_framemetrics_agent_is_preferred_and_gfxinf",
                "stage_investigation_hint",
                "copy_evidence_privacy_hint",
                "frame_screenshot_unavailable",
                "layout_preview_current_only",
                "no_jank_clusters_detected",
                "no_problem_frames",
                "frame_attribution_entry",
            )
        for (resource in resources) {
            val argument = "\\(\\s*(?:text\\s*=\\s*)?localizedStringResource\\(\\s*Res\\.string\\.$resource\\b"
            assertTrue(Regex("CopyableText$argument").containsMatchIn(screen), "$resource must remain copyable")
            assertFalse(Regex("\\bText$argument").containsMatchIn(screen), "$resource must not fall back to plain Text")
        }
        assertTrue(screen.contains("CopyableText(warning,"))
        assertTrue(screen.contains("CopyableText(it, color = MaterialTheme.colorScheme.error"))
        assertTrue(Regex("CopyableText\\(\\s*frame\\.problemEvidence\\(language\\)").containsMatchIn(screen))
    }

    @Test
    fun `footer keeps its selection container scrolling and the complete displayed status`() {
        val footer =
            workspace.substringAfter("private fun FrameProfilerStatusBar(").substringBefore("internal fun FrameOperationStatus")
        assertTrue(Regex("SelectionContainer\\s*\\{\\s*Row\\(").containsMatchIn(footer))
        assertTrue(footer.contains(".horizontalScroll(rememberScrollState())"))
        assertTrue(Regex("CopyableText\\(\\s*text = it,").containsMatchIn(footer))
        assertTrue(footer.contains("maxLines = 1"))
        assertTrue(workspace.contains("StringSelection(evidence)"))
        assertTrue(screen.contains("Text(localizedStringResource(Res.string.previous_frame, language))"))
        assertTrue(screen.contains("onClick = { copySucceeded = copy(frame.copyEvidenceText(language)) }"))
    }

    @Test
    fun `jank reason details opt into copying without changing ordinary detail values`() {
        val detail = screen.substringAfter("private fun FrameDetail(").substringBefore("private fun FrameLayoutPreview(")
        for (resource in listOf("surface_flinger_jank", "jank_stats_reasons", "jank_types")) {
            val reason = detail.substringAfter("Res.string.$resource,").substringBefore("\n                        )")
            assertTrue(reason.contains("copyable = true"), "$resource must opt into copying")
        }
        val row = screen.substringAfter("private fun DetailRow(").substringBefore("private fun ClusterList(")
        assertTrue(row.contains("copyable: Boolean = false"))
        assertTrue(Regex("if \\(copyable\\) \\{\\s*CopyableText\\(\\s*value,").containsMatchIn(row))
        assertTrue(Regex("else \\{\\s*Text\\(\\s*value,").containsMatchIn(row))
    }
}
