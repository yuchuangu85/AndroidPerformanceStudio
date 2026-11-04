package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MemoryClassTableBottomSpacingTest {
    @Test
    fun `wide class table reaches its height divider with no unused scrollbar gutter`() {
        UiLanguage.entries.forEach { language ->
            listOf(false, true).forEach { dark ->
                runDesktopComposeUiTest(width = 1200, height = 720) {
                    var loaded by mutableStateOf(true)
                    setContent {
                        ViewerTheme(darkTheme = dark) {
                            MemoryClassesPane(
                                fixture(loaded),
                                MemoryProfilerActions(),
                                language,
                                Modifier.fillMaxSize(),
                            )
                        }
                    }
                    listOf(true, false).forEach { hasRows ->
                        runOnIdle { loaded = hasRows }
                        val classes = onNodeWithTag("memory-classes-card").getUnclippedBoundsInRoot()
                        val table = onNodeWithTag("memory-classifier-table").getUnclippedBoundsInRoot()
                        val divider = onNodeWithTag("memory-class-instance-divider").getUnclippedBoundsInRoot()
                        assertEquals(classes.bottom, table.bottom)
                        assertEquals(divider.top, table.bottom)
                        onNodeWithTag("memory-classifier-horizontal-scrollbar").assertDoesNotExist()
                    }
                }
            }
        }
    }

    @Test
    fun `class scrollbar reserves only its actual height and disappears after growing the viewport`() {
        UiLanguage.entries.forEach { language ->
            listOf(false, true).forEach { dark ->
                runDesktopComposeUiTest(width = 1200, height = 720) {
                    var width by mutableStateOf(1200.dp)
                    setContent {
                        ViewerTheme(darkTheme = dark) {
                            MemoryClassesPane(fixture(true), MemoryProfilerActions(), language, Modifier.width(width))
                        }
                    }
                    onNodeWithTag("memory-classifier-horizontal-scrollbar").assertDoesNotExist()
                    runOnIdle { width = 650.dp }
                    val scrollbar = onNodeWithTag("memory-classifier-horizontal-scrollbar").assertIsDisplayed()
                    val classes = onNodeWithTag("memory-classes-card").getUnclippedBoundsInRoot()
                    val table = onNodeWithTag("memory-classifier-table").getUnclippedBoundsInRoot()
                    val bar = scrollbar.getUnclippedBoundsInRoot()
                    assertEquals(classes.bottom, bar.bottom)
                    assertEquals(bar.top, table.bottom)
                    val header = onNodeWithTag("memory-classifier-header-TOTAL_COUNT")
                    val before = header.getUnclippedBoundsInRoot().left
                    onNodeWithTag("memory-classifier-table").performTouchInput { swipeLeft() }
                    assertTrue(header.getUnclippedBoundsInRoot().left < before)

                    runOnIdle { width = 1200.dp }
                    onNodeWithTag("memory-classifier-horizontal-scrollbar").assertDoesNotExist()
                    assertEquals(
                        onNodeWithTag("memory-classes-card").getUnclippedBoundsInRoot().bottom,
                        onNodeWithTag("memory-classifier-table").getUnclippedBoundsInRoot().bottom,
                    )
                }
            }
        }
    }

    private fun fixture(loaded: Boolean): MemoryProfilerState =
        MemoryProfilerState(
            classifierRows =
                if (loaded) {
                    listOf(MemoryClassifierRow("first", "Target", "sample.Target", totalCount = 1, shallowSize = 32))
                } else {
                    emptyList()
                },
        )
}
