package com.androidperformancestudio.memory.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.model.HeapCapability
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
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
    fun `snapshot strip is compact in both locales and themes with scrollable independent actions`() {
        listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
            listOf(false, true).forEach { dark ->
                runDesktopComposeUiTest(width = 360, height = 100) {
                    val selected = mutableListOf<String>()
                    val closed = mutableListOf<String>()
                    val longName = first.copy(id = "snapshot-with-a-long-capture-name-that-remains-on-one-line.hprof")
                    val second = HeapSnapshotSummary(id = "snapshot-8")
                    setContent {
                        ViewerTheme(darkTheme = dark) {
                            MemoryProfilerSnapshotTabs(
                                MemoryProfilerState(
                                    snapshotSummaries = listOf(longName, second),
                                    activeSnapshotId = longName.id,
                                ),
                                onSelectSnapshot = { selected += it },
                                onCloseSnapshot = { closed += it },
                                language = language,
                            )
                        }
                    }
                    onNodeWithTag("memory-snapshot-tabs").assertHeightIsEqualTo(32.dp)
                    val firstTab = onNodeWithTag("memory-snapshot-tab-${longName.id}")
                    firstTab
                        .performScrollTo()
                        .assertIsDisplayed()
                        .assertHeightIsEqualTo(28.dp)
                        .assertIsSelected()
                    val secondTab = onNodeWithTag("memory-snapshot-tab-${second.id}")
                    secondTab
                        .performScrollTo()
                        .assertIsDisplayed()
                        .assertHeightIsEqualTo(28.dp)
                        .performClick()
                    firstTab.performScrollTo()
                    onNodeWithContentDescription(longName.tabEvidence(language).closeLabel)
                        .assertHeightIsEqualTo(28.dp)
                        .performClick()
                    assertEquals(listOf(second.id), selected)
                    assertEquals(listOf(longName.id), closed)
                }
            }
        }
    }

    @Test
    fun `compact snapshot tabs expose evidence semantics selection and independent close action`() =
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

            onNodeWithText("Source: heap.hprof", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithText("Loaded: 2026-09-24T01:02:03Z", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithContentDescription(first.tabEvidence(UiLanguage.ENGLISH).accessibilityLabel).assertIsSelected()
            onNodeWithContentDescription(second.tabEvidence(UiLanguage.ENGLISH).accessibilityLabel).performClick()
            onNodeWithContentDescription("Close snapshot snapshot-7").performClick()

            assertEquals(listOf("snapshot-8"), selected)
            assertEquals(listOf("snapshot-7"), closed)
        }
}
