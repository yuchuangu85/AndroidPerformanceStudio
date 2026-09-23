package com.androidperformancestudio.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class)
class SegmentedSelectorTest {
    @Test
    fun `two items switch their visible selection without repeating selected callbacks`() =
        runDesktopComposeUiTest {
            var selected by mutableStateOf("Home")
            var changes = 0
            setContent {
                ViewerTheme(darkTheme = false) {
                    SegmentedSelector(
                        items = listOf("Home", "Canvas"),
                        selectedItem = selected,
                        onItemSelected = {
                            selected = it
                            changes++
                        },
                        itemLabel = { it },
                    )
                }
            }

            onNodeWithTag("segmented-selector-item-0").assertIsSelected()
            onNodeWithTag("segmented-selector-item-1").assertIsNotSelected().performClick()
            onNodeWithTag("segmented-selector-item-0").assertIsNotSelected()
            onNodeWithTag("segmented-selector-item-1").assertIsSelected().performClick()
            assertEquals("Canvas", selected)
            assertEquals(1, changes)
        }

    @Test
    fun `selected item callback is opt in for action segments`() =
        runDesktopComposeUiTest {
            var selectedActions = 0
            setContent {
                ViewerTheme(darkTheme = false) {
                    SegmentedSelector(
                        items = listOf("Refresh", "Auto scan"),
                        selectedItem = "Refresh",
                        onItemSelected = {},
                        onSelectedItemClick = { selectedActions++ },
                        itemLabel = { it },
                        style = SegmentedSelectorStyle.COMPACT_OUTLINED,
                    )
                }
            }

            onNodeWithTag("segmented-selector-item-0").performClick()
            assertEquals(1, selectedActions)
        }

    @Test
    fun `three items support no initial choice and disabled alternatives`() =
        runDesktopComposeUiTest {
            var selected by mutableStateOf<String?>(null)
            setContent {
                ViewerTheme(darkTheme = true) {
                    SegmentedSelector(
                        items = listOf("A", "B", "C"),
                        selectedItem = selected,
                        onItemSelected = { selected = it },
                        itemLabel = { it },
                        itemEnabled = { it != "B" },
                    )
                }
            }

            onNodeWithTag("segmented-selector-item-0").assertIsNotSelected()
            onNodeWithTag("segmented-selector-item-1").assertIsNotEnabled()
            onNodeWithTag("segmented-selector-item-2").performClick().assertIsSelected()
            assertEquals("C", selected)
        }

    @Test
    fun `disabled selector cannot change the selected item`() =
        runDesktopComposeUiTest {
            var selected by mutableStateOf("A")
            setContent {
                ViewerTheme(darkTheme = false) {
                    SegmentedSelector(
                        items = listOf("A", "B"),
                        selectedItem = selected,
                        onItemSelected = { selected = it },
                        itemLabel = { it },
                        enabled = false,
                    )
                }
            }

            onNodeWithTag("segmented-selector-item-1").assertIsNotEnabled()
            onNodeWithTag("segmented-selector-item-0").assertIsSelected()
            assertEquals("A", selected)
        }

    @Test
    fun `more than three items remain reachable in a narrow viewport`() =
        runDesktopComposeUiTest(width = 340, height = 200) {
            var selected by mutableStateOf(0)
            setContent {
                ViewerTheme(darkTheme = false) {
                    SegmentedSelector(
                        items = (0..7).toList(),
                        selectedItem = selected,
                        onItemSelected = { selected = it },
                        itemLabel = { "Item $it" },
                        modifier = Modifier.width(140.dp),
                    )
                }
            }

            onNodeWithTag("segmented-selector-item-7").performScrollTo().performClick()
            onNodeWithTag("segmented-selector-item-7").assertIsSelected()
            assertEquals(7, selected)
        }

    @Test
    fun `compact outlined style matches existing action colors and dimensions`() =
        runDesktopComposeUiTest {
            setContent {
                ViewerTheme(darkTheme = true) {
                    SegmentedSelector(
                        items = listOf("Refresh", "Auto scan"),
                        selectedItem = "Refresh",
                        onItemSelected = {},
                        itemLabel = { it },
                        style = SegmentedSelectorStyle.COMPACT_OUTLINED,
                    )
                }
            }

            val selected = onNodeWithTag("segmented-selector-item-0").captureToImage().toPixelMap()
            val unselected = onNodeWithTag("segmented-selector-item-1").captureToImage().toPixelMap()
            val colors = viewerColors(darkTheme = true)
            assertEquals(colors.accent, selected[selected.width / 2, 2])
            assertEquals(colors.panel, unselected[unselected.width / 2, 2])
            assertEquals(
                ViewerDimensions.compactControlHeight.value.toInt(),
                onNodeWithTag("segmented-selector-item-0").captureToImage().height,
            )
        }

    @Test
    fun `selected segment paints a different pill surface`() =
        runDesktopComposeUiTest {
            setContent {
                ViewerTheme(darkTheme = true) {
                    SegmentedSelector(
                        items = listOf("Home", "Canvas"),
                        selectedItem = "Canvas",
                        onItemSelected = {},
                        itemLabel = { it },
                    )
                }
            }

            val unselected = onNodeWithTag("segmented-selector-item-0").captureToImage().toPixelMap()
            val selected = onNodeWithTag("segmented-selector-item-1").captureToImage().toPixelMap()
            assertNotEquals(unselected[unselected.width / 2, 3], selected[selected.width / 2, 3])
        }

    @Test
    fun `pill surface colors derive from the light and dark viewer themes`() {
        val light = viewerColors(darkTheme = false)
        val dark = viewerColors(darkTheme = true)
        assertNotEquals(light.segmentedTrack, light.segmentedSelected)
        assertNotEquals(dark.segmentedTrack, dark.segmentedSelected)
        assertNotEquals(light.segmentedSelected, dark.segmentedSelected)
    }
}
