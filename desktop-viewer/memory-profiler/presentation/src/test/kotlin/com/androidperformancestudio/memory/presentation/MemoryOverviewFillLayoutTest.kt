package com.androidperformancestudio.memory.presentation

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.memory.model.HeapSummary
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MemoryOverviewFillLayoutTest {
    @Test
    fun `empty overview fills the workspace down to the viewport gap`() = checkFillLayouts(empty = true)

    @Test
    fun `populated overview fills the workspace down to the viewport gap`() = checkFillLayouts(empty = false)

    @Test
    fun `overview rows scroll to the last class without moving metrics title or headers`() {
        listOf(1000 to 700, 520 to 300).forEach { (width, height) ->
            listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
                runDesktopComposeUiTest(width = width, height = height) {
                    val state = fixture(empty = false)
                    val highlighted = mutableListOf<String>()
                    setContent {
                        ViewerTheme(darkTheme = false) {
                            MemoryProfilerScreen(
                                state,
                                MemoryProfilerActions(onHighlightClass = { highlighted += it }),
                                language,
                            )
                        }
                    }
                    val fixedTags =
                        listOf(
                            "memory-profiler-overview-metrics",
                            "memory-profiler-histogram-title",
                            "memory-profiler-histogram-header",
                        )
                    val fixedBounds = fixedTags.associateWith { onNodeWithTag(it).getUnclippedBoundsInRoot() }
                    val list = onNodeWithTag("memory-profiler-histogram-list")
                    val listBounds = list.getUnclippedBoundsInRoot()
                    val lastClass = state.classes.last().className

                    onNodeWithTag("memory-profiler-histogram-row-$lastClass").assertDoesNotExist()
                    list.performScrollToIndex(state.classes.lastIndex)
                    onNodeWithTag("memory-profiler-histogram-row-$lastClass").assertIsDisplayed().performClick()
                    assertEquals(listOf(lastClass), highlighted)
                    fixedBounds.forEach { (tag, bounds) ->
                        assertEquals(bounds, onNodeWithTag(tag).getUnclippedBoundsInRoot(), "$tag must remain fixed")
                        onNodeWithTag(tag).assertIsDisplayed()
                    }
                    assertEquals(listBounds, list.getUnclippedBoundsInRoot())
                    assertEquals(
                        listBounds.bottom,
                        onNodeWithTag("memory-profiler-class-histogram").getUnclippedBoundsInRoot().bottom,
                    )
                    assertFillLayout(empty = false)

                    list.performScrollToIndex(0)
                    onNodeWithTag("memory-profiler-histogram-row-${state.classes.first().className}")
                        .assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun `expanded evidence keeps overview histogram bounded inside the viewport`() =
        runDesktopComposeUiTest(width = 1000, height = 420) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    MemoryProfilerScreen(
                        fixture(empty = false).copy(snapshotSummary = HeapSnapshotSummary(id = "overview-layout")),
                        MemoryProfilerActions(),
                    )
                }
            }
            val title = onNodeWithTag("memory-profiler-histogram-title").getUnclippedBoundsInRoot()
            onNodeWithTag("memory-profiler-evidence-toggle").performClick()
            assertFillLayout(empty = false)
            assertTrue(onNodeWithTag("memory-profiler-histogram-title").getUnclippedBoundsInRoot().top > title.top)
        }

    private fun checkFillLayouts(empty: Boolean) {
        listOf(1440 to 860, 520 to 700, 1000 to 300, 520 to 300).forEach { (width, height) ->
            listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
                listOf(false, true).forEach { dark ->
                    runDesktopComposeUiTest(width = width, height = height) {
                        setContent {
                            ViewerTheme(darkTheme = dark) {
                                MemoryProfilerScreen(fixture(empty), MemoryProfilerActions(), language)
                            }
                        }
                        assertFillLayout(empty)
                    }
                }
            }
        }
    }

    private fun DesktopComposeUiTest.assertFillLayout(empty: Boolean) {
        val root = onRoot().getUnclippedBoundsInRoot()
        val histogram = onNodeWithTag("memory-profiler-class-histogram").getUnclippedBoundsInRoot()
        val metrics = onNodeWithTag("memory-profiler-overview-metrics").getUnclippedBoundsInRoot()
        val title = onNodeWithTag("memory-profiler-histogram-title").getUnclippedBoundsInRoot()
        val header = onNodeWithTag("memory-profiler-histogram-header").getUnclippedBoundsInRoot()
        onNodeWithTag("memory-profiler-status-bar").assertDoesNotExist()
        assertEquals(4.dp, root.bottom - histogram.bottom)
        assertEquals(4.dp, histogram.left - root.left)
        assertEquals(4.dp, root.right - histogram.right)
        assertEquals(4.dp, histogram.top - metrics.bottom)
        assertEquals(histogram.top, title.top)
        assertTrue(metrics.bottom > metrics.top)
        assertTrue(header.top >= title.bottom)
        assertTrue(header.bottom < histogram.bottom)
        onNodeWithTag("memory-profiler-overview-metrics").assertIsDisplayed()
        onNodeWithTag("memory-profiler-histogram-title").assertIsDisplayed()
        onNodeWithTag("memory-profiler-histogram-header").assertIsDisplayed()
        val contentTag = if (empty) "memory-profiler-histogram-empty" else "memory-profiler-histogram-list"
        onNodeWithTag(contentTag).assertIsDisplayed()
        val content = onNodeWithTag(contentTag).getUnclippedBoundsInRoot()
        assertTrue(content.top >= header.bottom)
        assertTrue(content.bottom <= histogram.bottom)
        if (!empty) assertEquals(histogram.bottom, content.bottom)
    }

    private fun fixture(empty: Boolean): MemoryProfilerState =
        MemoryProfilerState(
            viewMode = MemoryProfilerViewMode.Dashboard,
            summary = HeapSummary(objectCount = 12_880, shallowSize = 6L * 1024L * 1024L, classCount = 160),
            activityCount = 3,
            classes =
                if (empty) {
                    emptyList()
                } else {
                    List(160) { index ->
                        ClassStats("sample.Class$index", instanceCount = 160 - index, shallowSize = (index + 1) * 128L)
                    }
                },
        )
}
