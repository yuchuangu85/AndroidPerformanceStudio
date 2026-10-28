package com.androidperformancestudio.memory.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MemoryUnifiedAnalysisCardTest {
    @Test
    fun `combined analysis card has equal outer spacing and owns any narrow scrollbar`() {
        val sizes = listOf(1280, 900, 500).flatMap { width -> listOf(false, true).map { loaded -> width to loaded } }
        val appearances =
            listOf(
                UiLanguage.ENGLISH to false,
                UiLanguage.ENGLISH to true,
                UiLanguage.SIMPLIFIED_CHINESE to false,
                UiLanguage.SIMPLIFIED_CHINESE to true,
            )
        val cases = sizes.flatMap { size -> appearances.map { appearance -> size to appearance } }
        cases.forEach { (size, appearance) ->
            val (width, loaded) = size
            val (language, dark) = appearance
            runDesktopComposeUiTest(width = width, height = 720) {
                setContent {
                    ViewerTheme(darkTheme = dark) {
                        MemoryProfilerScreen(fixture(loaded), MemoryProfilerActions(), language)
                    }
                }
                val card = onNodeWithTag("memory-class-analysis-card").fetchSemanticsNode().boundsInRoot
                val previousTag = if (loaded) "memory-distribution-card" else "memory-snapshot-summary"
                val previous = onNodeWithTag(previousTag).fetchSemanticsNode().boundsInRoot
                val footer = onNodeWithTag("memory-profiler-status-bar").fetchSemanticsNode().boundsInRoot
                assertEquals(4f, card.top - previous.bottom, 0.6f)
                assertEquals(4f, footer.top - card.bottom, 0.6f)
                assertEquals(4f, card.left, 0.6f)
                assertEquals(width - 4f, card.right, 0.6f)
                val inCard = hasAnyAncestor(hasTestTag("memory-class-analysis-card"))
                listOf("memory-classes-card", "memory-instances-card", "memory-class-instance-divider")
                    .forEach { tag -> onNode(hasTestTag(tag) and inCard).assertExists() }
                if (width < 650) {
                    onNode(hasTestTag("memory-analysis-horizontal-scrollbar") and inCard).assertExists()
                    onNodeWithTag("memory-instance-table").performScrollTo().assertIsDisplayed()
                    assertEquals(card, onNodeWithTag("memory-class-analysis-card").fetchSemanticsNode().boundsInRoot)
                } else {
                    onNodeWithTag("memory-analysis-horizontal-scrollbar").assertDoesNotExist()
                    val instances = onNodeWithTag("memory-instances-card").fetchSemanticsNode().boundsInRoot
                    assertEquals(card.bottom, instances.bottom, 0.6f)
                }
            }
        }
    }

    private fun fixture(loaded: Boolean): MemoryProfilerState {
        if (!loaded) return MemoryProfilerState()
        val snapshot = HeapSnapshotSummary("unified-card")
        val classes = listOf(ClassStats("sample.Target", instanceCount = 2, shallowSize = 64))
        return MemoryProfilerState(
            activeSnapshotId = snapshot.id,
            snapshotSummary = snapshot,
            classes = classes,
            heapBaseClasses = classes,
        )
    }
}
