package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MemoryHeapTableLayoutTest {
    @Test
    fun `instance labels show class and original decimal and hexadecimal identity`() {
        assertEquals("Target@42 (0x2a)", instanceTableLabel(42, "sample.Target"))
        assertEquals("Target[]@43 (0x2b)", instanceTableLabel(43, "sample.Target[]"))
        assertEquals("Outer\$Inner@44 (0x2c)", instanceTableLabel(44, "sample.Outer\$Inner"))
        assertEquals("45 (0x2d)", instanceTableLabel(45, null))
        assertEquals("Target (class)@46 (0x2e)", instanceTableLabel(46, "java.lang.Class", "sample.Target"))
    }

    @Test
    fun `dense tables retain aligned columns runtime names selection and continuous dividers`() {
        listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
            listOf(false, true).forEach { dark ->
                runDesktopComposeUiTest(width = 1200, height = 720) {
                    var selected: Long? = null
                    var selectedClassifier: String? = null
                    var sorted: MemoryClassifierColumn? = null
                    var dividerColor = Color.Unspecified
                    val state = fixture()
                    setContent {
                        ViewerTheme(darkTheme = dark) {
                            dividerColor = MaterialTheme.colorScheme.outlineVariant
                            MemoryClassesPane(
                                state,
                                MemoryProfilerActions(
                                    onSelectInstance = { selected = it },
                                    onSelectClassifier = { selectedClassifier = it.id },
                                    onClassifierSort = { sorted = it },
                                ),
                                language,
                                Modifier.fillMaxSize(),
                            )
                        }
                    }
                    onNodeWithTag("memory-classifier-row-first").assertHeightIsEqualTo(20.dp).performClick()
                    assertEquals("first", selectedClassifier)
                    onNodeWithTag("memory-classifier-row-second").assertHeightIsEqualTo(20.dp)
                    onNodeWithTag("memory-instance-row-42").assertHeightIsEqualTo(20.dp).performClick()
                    assertEquals(42L, selected)
                    onNodeWithTag("memory-instance-name-42", useUnmergedTree = true)
                        .assertTextEquals("Target@42 (0x2a)")
                    onNodeWithTag("memory-instance-name-43", useUnmergedTree = true)
                        .assertTextEquals("Other[]@43 (0x2b)")
                    onNodeWithTag("memory-instance-name-44", useUnmergedTree = true)
                        .assertTextEquals("Target (class)@44 (0x2c)")
                    onNodeWithTag("memory-instance-name-45", useUnmergedTree = true)
                        .assertTextEquals("Other (class)@45 (0x2d)")
                    onNodeWithTag("memory-classifier-header-TOTAL_COUNT").performClick()
                    assertEquals(MemoryClassifierColumn.TOTAL_COUNT, sorted)

                    val pixels = onRoot().captureToImage().toPixelMap()
                    listOf("memory-classifier-table", "memory-instance-table").forEach { tag ->
                        val bounds = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                        val lastWidth = if (tag == "memory-classifier-table") 104f else 120f
                        val x = (bounds.right - lastWidth).roundToInt()
                        val y = (bounds.bottom - 30f).roundToInt()
                        assertTrue(
                            (x - 1..x + 1).any { column -> colorDistance(pixels[column, y], dividerColor) < 0.04f },
                            "$tag must have a visible divider through its empty body in $language dark=$dark",
                        )
                        assertTrue(colorDistance(pixels[x + 4, y], dividerColor) > 0.04f)
                    }
                }
            }
        }
    }

    private fun fixture(): MemoryProfilerState =
        MemoryProfilerState(
            selectedClassName = null,
            selectedClassifierLabel = "sample",
            classifierRows =
                listOf(
                    MemoryClassifierRow("first", "Target", "sample.Target", totalCount = 1, shallowSize = 32),
                    MemoryClassifierRow(
                        "second",
                        "Other[]",
                        "sample.Other[]",
                        totalCount = 1,
                        shallowSize = 64,
                        depth = 2,
                    ),
                ),
            selectedClassInstances =
                listOf(
                    MemoryInstanceRow(42, 1, 32, 48, 1, true, className = "sample.Target"),
                    MemoryInstanceRow(43, 2, 64, 64, 2, true, className = "sample.Other[]"),
                    MemoryInstanceRow(
                        44,
                        3,
                        32,
                        null,
                        null,
                        false,
                        className = "java.lang.Class",
                        representedClassName = "sample.Target",
                    ),
                    MemoryInstanceRow(
                        45,
                        4,
                        32,
                        null,
                        null,
                        false,
                        className = "java.lang.Class",
                        representedClassName = "sample.Other",
                    ),
                ),
        )

    private fun colorDistance(
        left: Color,
        right: Color,
    ): Float = abs(left.red - right.red) + abs(left.green - right.green) + abs(left.blue - right.blue)
}
