package com.androidperformancestudio.memory.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MemoryHistogramDensityTest {
    @Test
    fun `overview and class list share row height and keep all text inside rows at normal and enlarged font sizes`() {
        UiLanguage.entries.forEach { language ->
            listOf(false, true).forEach { dark ->
                listOf(1f, 2f).forEach { fontScale ->
                    runDesktopComposeUiTest(width = 1280, height = 800) {
                        var mode by mutableStateOf(MemoryProfilerViewMode.Dashboard)
                        val selected = mutableListOf<String>()
                        val classes =
                            listOf(
                                ClassStats("sample.First", 2, 64, 128),
                                ClassStats("sample.Second", 1, 32, 64),
                            )
                        setContent {
                            ViewerTheme(darkTheme = dark) {
                                val density = LocalDensity.current
                                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                                    MemoryProfilerScreen(
                                        MemoryProfilerState(
                                            viewMode = mode,
                                            classes = classes,
                                            heapBaseClasses = classes,
                                        ),
                                        MemoryProfilerActions(onHighlightClass = { selected += it }),
                                        language,
                                    )
                                }
                            }
                        }
                        val heights = histogramHeights(classes, fontScale)
                        onNodeWithTag("memory-profiler-histogram-row-sample.First").performClick()
                        assertEquals(listOf("sample.First"), selected)
                        runOnIdle { mode = MemoryProfilerViewMode.ClassList }
                        classes.forEach { stats ->
                            val row =
                                onNodeWithTag("memory-classifier-row-class:${stats.className}")
                                    .assertIsDisplayed()
                                    .getUnclippedBoundsInRoot()
                            assertEquals(heights.getValue(stats.className), row.bottom - row.top)
                        }
                    }
                }
            }
        }
    }

    private fun DesktopComposeUiTest.histogramHeights(
        classes: List<ClassStats>,
        fontScale: Float,
    ): Map<String, Dp> =
        classes.associate { stats ->
            val tag = "memory-profiler-histogram-row-${stats.className}"
            val row = onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertEquals(16f * fontScale + 4f, (row.bottom - row.top).value, 0.6f)
            val textNodes =
                onAllNodes(
                    hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
            assertEquals(4, textNodes.size)
            textNodes.forEach { text ->
                assertTrue(text.boundsInRoot.top >= row.top.value)
                assertTrue(text.boundsInRoot.bottom <= row.bottom.value)
            }
            stats.className to row.bottom - row.top
        }
}
