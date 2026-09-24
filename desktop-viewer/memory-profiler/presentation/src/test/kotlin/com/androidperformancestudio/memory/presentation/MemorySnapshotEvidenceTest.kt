package com.androidperformancestudio.memory.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.memory.model.HeapCapability
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.ui.UiLanguage
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MemorySnapshotEvidenceTest {
    @Test
    fun `snapshot evidence uses only actual metadata and never exposes the source directory`() {
        val snapshot =
            HeapSnapshotSummary(
                id = "snapshot-7",
                sourceFile = Path.of("/private/app-data/heap.hprof"),
                fileSizeBytes = 4_096,
                sourceFileDigest = "0123456789abcdef",
                capturedAt = Instant.parse("2026-09-24T01:00:00Z"),
                loadedAt = Instant.parse("2026-09-24T01:02:03Z"),
                format = "ANDROID_HPROF",
                idSize = 4,
                classCount = 18,
                objectCount = 400,
                warningCount = 2,
                capabilities = setOf(HeapCapability.HEAP_GRAPH, HeapCapability.GC_ROOTS),
            )

        val lines = snapshot.evidenceLines(UiLanguage.ENGLISH)

        assertTrue(lines.any { it.contains("source heap.hprof") && it.contains("ID width 4 B") })
        assertTrue(lines.any { it.contains("Classes 18 · Objects 400 · Parse warnings 2") })
        assertTrue(
            lines.any { it.contains("Recorded 2026-09-24T01:00:00Z") && it.contains("loaded 2026-09-24T01:02:03Z") },
        )
        assertTrue(lines.any { it.contains("0123456789abcdef") })
        assertTrue(lines.any { it.contains("GC_ROOTS") && it.contains("HEAP_GRAPH") })
        assertFalse(lines.any { it.contains("/private/app-data") })
    }

    @Test
    fun `missing snapshot metadata is marked unavailable rather than inferred`() {
        val lines = HeapSnapshotSummary(id = "unknown").evidenceLines(UiLanguage.ENGLISH)

        assertTrue(lines.any { it.contains("source Unavailable · format Unavailable · ID width Unavailable") })
        assertTrue(lines.any { it.contains("Recorded Unavailable · loaded Unavailable · file size Unavailable") })
        assertTrue(lines.any { it.contains("Available capabilities: Unavailable") })
        assertFalse(lines.any { it.contains("1970-01-01") })
    }

    @Test
    fun `snapshot evidence remains visible without a capture artifact`() =
        runDesktopComposeUiTest(width = 1000, height = 700) {
            setContent {
                MemoryProfilerScreen(
                    state =
                        MemoryProfilerState(
                            snapshotSummary =
                                HeapSnapshotSummary(
                                    id = "snapshot-7",
                                    classCount = 18,
                                    objectCount = 400,
                                ),
                        ),
                    actions = MemoryProfilerActions(),
                )
            }

            onNodeWithText("Snapshot Evidence").assertExists()
            onNodeWithText("Classes 18 · Objects 400 · Parse warnings 0").assertExists()
        }
}
