package com.androidperformancestudio.memory.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryCopyableMessagesSourceTest {
    @Test
    fun `memory failure reasons warnings and empty states use the shared copyable message`() {
        val resourcesByFile =
            mapOf(
                "MemoryProfilerScreen.kt" to
                    listOf(
                        "snapshot_limitation",
                        "snapshot_artifact_warning",
                        "mapping_loaded_note",
                        "import_or_dump_an_hprof_file_to_show_class_histogram",
                    ),
                "MemorySampledArtAllocationPage.kt" to
                    listOf(
                        "art_sampled_evidence_boundary",
                        "art_sampled_analysis_unavailable",
                        "art_sampled_analysis_failed",
                        "art_sampled_no_samples",
                        "art_sampled_recording",
                        "art_sampled_trace_unavailable",
                    ),
                "MemoryProfilerBitmapDumpPage.kt" to
                    listOf("bitmap_dump_loading", "bitmap_dump_no_session", "bitmap_image_unavailable"),
                "MemoryProfilerNativeHeapPage.kt" to
                    listOf("native_heap_capture_loading", "no_native_heap_trace", "native_heap_no_allocations"),
                "MemoryLeakInvestigationPanes.kt" to
                    listOf("leak_no_matching_findings", "leak_no_path", "leak_object_unavailable", "leak_suspect_reference"),
                "MemoryInvestigationWorkspace.kt" to listOf("memory_no_snapshot"),
                "MemorySnapshotComparison.kt" to listOf("memory_compare_empty", "memory_compare_limit", "memory_no_growth"),
                "MemoryLeakLiveControls.kt" to listOf("no_live_events", "live_event_summary"),
                "MemoryProfilerLeakCanaryPage.kt" to listOf("leak_offline_analysis", "leak_analysis_status"),
            )
        for ((file, resources) in resourcesByFile) {
            val source = presentationSource(file)
            assertTrue(source.contains("import com.androidperformancestudio.ui.CopyableText"), file)
            for (resource in resources) {
                val argument = "\\(\\s*(?:text\\s*=\\s*)?localizedStringResource\\(\\s*Res\\.string\\.$resource\\b"
                assertTrue(Regex("CopyableText$argument").containsMatchIn(source), "$file: $resource must remain copyable")
                assertFalse(Regex("\\bText$argument").containsMatchIn(source), "$file: $resource must not use plain Text")
            }
        }
        val leaks = presentationSource("MemoryLeakInvestigationPanes.kt")
        assertTrue(Regex("CopyableText\\(\\s*listOfNotNull\\(status, element\\.leakingStatusReason").containsMatchIn(leaks))
        assertTrue(leaks.contains("CopyableText(leak.shortDescription,"))
    }

    @Test
    fun `footer keeps its selection container layout and full displayed message without changing retry`() {
        val screen = presentationSource("MemoryProfilerScreen.kt")
        val footer =
            screen.substringAfter("private fun MemoryProfilerStatusBar(").substringBefore("private fun MemoryProfilerStatusMessage(")
        assertTrue(Regex("SelectionContainer\\s*\\{\\s*Row\\(").containsMatchIn(footer))
        assertTrue(footer.contains(".horizontalScroll(rememberScrollState())"))
        assertTrue(footer.contains("message = error.detail"))
        assertTrue(footer.contains("onClick = actions.onRetry"))
        assertTrue(footer.contains("onClick = actions.onCancelOperation"))
        val message = screen.substringAfter("private fun MemoryProfilerStatusMessage(").substringBefore("@Composable")
        assertTrue(Regex("CopyableText\\(\\s*text = message,").containsMatchIn(message))
        assertTrue(message.contains("maxLines = 1"))
    }

    @Test
    fun `dynamic inspector empty pane and live failure messages preserve their full display input`() {
        val inspector = presentationSource("MemoryInspectorTable.kt").substringAfter("private fun InspectorMessage(")
        assertTrue(Regex("CopyableText\\(\\s*localizedStringResource\\(key, language\\)").containsMatchIn(inspector))
        val emptyPane = presentationSource("MemoryProfilerClassListView.kt").substringAfter("private fun EmptyPaneHint(")
        assertTrue(emptyPane.contains("CopyableText(text,"))
        val live = presentationSource("MemoryLeakLiveControls.kt").substringAfter("session.message?.takeIf")
        assertTrue(Regex("CopyableText\\(\\s*it,").containsMatchIn(live))
        assertTrue(live.contains("overflow = TextOverflow.Ellipsis"))
        val feedback = presentationSource("MemoryProfilerLeakCanaryPage.kt").substringAfter("private fun LeakAnalysisFeedback(")
        assertTrue(Regex("CopyableText\\(\\s*text,").containsMatchIn(feedback))
        assertTrue(feedback.contains("report.message.orEmpty()"))
        val dominators = presentationSource("MemoryDominatorWorkspace.kt").substringAfter("private fun RemoteDominatorChildPage(")
        assertTrue(Regex("CopyableText\\(\\s*localizedStringResource\\(Res.string.unavailable,").containsMatchIn(dominators))
    }

    private fun presentationSource(file: String): String =
        Files.readString(Path.of("../presentation/src/main/kotlin/com/androidperformancestudio/memory/presentation", file))
}
