package com.androidperformancestudio.memory.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.memory.model.HeapCapability
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.ui.UiLanguage
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MemoryProfilerSnapshotTabsTest {
    private val first =
        HeapSnapshotSummary(
            id = "snapshot-7",
            sourceFile = Path.of("/private/app-data/heap.hprof"),
            loadedAt = Instant.parse("2026-09-24T01:02:03Z"),
            classCount = 18,
            objectCount = 400,
            warningCount = 2,
            capabilities = setOf(HeapCapability.HEAP_GRAPH, HeapCapability.GC_ROOTS),
        )

    @Test
    fun `tab evidence contains actual source time counts and named capabilities without exposing directories`() {
        val evidence = first.tabEvidence(UiLanguage.ENGLISH)

        assertEquals("snapshot-7", evidence.name)
        assertEquals("Source: heap.hprof", evidence.source)
        assertEquals("Loaded: 2026-09-24T01:02:03Z", evidence.loaded)
        assertEquals("18 classes · 400 objects · 2 warnings", evidence.counts)
        assertEquals("Capabilities: GC_ROOTS, HEAP_GRAPH", evidence.capabilities)
        assertFalse(evidence.accessibilityLabel.contains("/private/app-data"))
        assertFalse(evidence.closeLabel.contains("/private/app-data"))
        assertTrue(first.tabEvidence(UiLanguage.SIMPLIFIED_CHINESE).counts.contains("18 类"))
        assertEquals("Source: Unavailable", HeapSnapshotSummary(id = "unknown").tabEvidence(UiLanguage.ENGLISH).source)
    }

    @Test
    fun `multi-line snapshot cards expose fields selection and independent close action`() =
        runDesktopComposeUiTest(width = 1000, height = 720) {
            val selected = mutableListOf<String>()
            val closed = mutableListOf<String>()
            val second = HeapSnapshotSummary(id = "snapshot-8")
            setContent {
                MemoryProfilerSnapshotTabs(
                    state =
                        MemoryProfilerState(
                            snapshotSummaries = listOf(first, second),
                            activeSnapshotId = first.id,
                        ),
                    onSelectSnapshot = { selected += it },
                    onCloseSnapshot = { closed += it },
                    language = UiLanguage.ENGLISH,
                )
            }

            onNodeWithText("Source: heap.hprof", useUnmergedTree = true).assertExists()
            onNodeWithText("Loaded: 2026-09-24T01:02:03Z", useUnmergedTree = true).assertExists()
            onNodeWithText("18 classes · 400 objects · 2 warnings", useUnmergedTree = true).assertExists()
            onNodeWithText("Capabilities: GC_ROOTS, HEAP_GRAPH", useUnmergedTree = true).assertExists()
            onNodeWithContentDescription(first.tabEvidence(UiLanguage.ENGLISH).accessibilityLabel).assertIsSelected()
            onNodeWithContentDescription(second.tabEvidence(UiLanguage.ENGLISH).accessibilityLabel).performClick()
            onNodeWithContentDescription("Close snapshot snapshot-7").performClick()

            assertEquals(listOf("snapshot-8"), selected)
            assertEquals(listOf("snapshot-7"), closed)
        }
}
