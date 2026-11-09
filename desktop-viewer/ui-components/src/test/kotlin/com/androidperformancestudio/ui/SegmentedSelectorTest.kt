package com.androidperformancestudio.ui

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SegmentedSelectorTest {
    @Test
    fun `title typography grows with font scale without changing ordinary compact controls`() =
        runDesktopComposeUiTest {
            var fontScale by mutableStateOf(1f)
            setContent {
                ViewerTheme(darkTheme = false) {
                    Column {
                        SegmentedSelector(
                            listOf("A", "B"), "A", {}, { it },
                            modifier = Modifier.testTag("default-selector"),
                            style = SegmentedSelectorStyle.COMPACT_OUTLINED,
                        )
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                            SegmentedSelector(
                                listOf("Provider", "Agent"), "Provider", {}, { it },
                                modifier = Modifier.testTag("title-selector"),
                                style = SegmentedSelectorStyle.COMPACT_OUTLINED,
                                textStyle = ViewerTypography.pageTitle,
                            )
                        }
                    }
                }
            }
            onNodeWithTag("default-selector").assertHeightIsEqualTo(24.dp)
            onNodeWithTag("title-selector").assertHeightIsEqualTo(30.dp)
            runOnIdle { fontScale = 2f }
            onNodeWithTag("title-selector").assertHeightIsEqualTo(52.dp)
            onNodeWithTag("default-selector").assertHeightIsEqualTo(24.dp)
        }

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
    fun `equal width items fill the available row and remain selectable`() =
        runDesktopComposeUiTest(width = 340, height = 200) {
            var selected by mutableStateOf("Overview")
            setContent {
                ViewerTheme(darkTheme = false) {
                    SegmentedSelector(
                        items = listOf("Overview", "Layout", "Drawing", "Accessibility", "Evidence"),
                        selectedItem = selected,
                        onItemSelected = { selected = it },
                        itemLabel = { it },
                        modifier = Modifier.width(300.dp),
                        equalWidthItems = true,
                        style = SegmentedSelectorStyle.COMPACT_OUTLINED,
                    )
                }
            }

            val widths = (0..4).map {
                onNodeWithTag("segmented-selector-item-$it").fetchSemanticsNode().boundsInRoot.width
            }
            assertTrue(widths.max() - widths.min() <= 1f, "Expected equal segment widths, got $widths")
            onNodeWithTag("segmented-selector-item-4").performClick().assertIsSelected()
            assertEquals("Evidence", selected)
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
            assertEquals(colors.selectedRow, selected[selected.width / 2, 2])
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
