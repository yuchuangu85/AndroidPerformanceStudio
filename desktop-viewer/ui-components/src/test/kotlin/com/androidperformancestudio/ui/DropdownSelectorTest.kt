package com.androidperformancestudio.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DropdownSelectorTest {
    @Test
    fun `selects regular and placeholder items`() =
        runDesktopComposeUiTest {
            var selected by mutableStateOf<String?>("Pixel 8")

            setContent {
                ViewerTheme(darkTheme = false) {
                    DropdownSelector(
                        items = listOf("Pixel 8", "Pixel 9"),
                        selectedItem = selected,
                        onItemSelected = { selected = it },
                        itemLabel = { it },
                        selectedItemLabel = { "Selected $it" },
                        placeholder = "Auto device",
                        selectorDescription = "Device selector",
                        onPlaceholderSelected = { selected = null },
                    )
                }
            }

            onNodeWithText("Selected Pixel 8").assertExists()
            onNodeWithTag("dropdown-selector-expand-icon", useUnmergedTree = true).assertExists()
            onNodeWithContentDescription("Device selector").performClick()
            onNodeWithText("Auto device").performClick()
            assertEquals(null, selected)

            onNodeWithContentDescription("Device selector").performClick()
            onNodeWithText("Pixel 9").performClick()
            assertEquals("Pixel 9", selected)
        }

    @Test
    fun `keeps unavailable items disabled and renders secondary text`() =
        runDesktopComposeUiTest {
            data class Option(
                val label: String,
                val available: Boolean,
            )

            val unavailable = Option("Offline device", false)
            setContent {
                ViewerTheme(darkTheme = false) {
                    DropdownSelector(
                        items = listOf(unavailable),
                        selectedItem = null,
                        onItemSelected = {},
                        itemLabel = Option::label,
                        placeholder = "Device",
                        selectorDescription = "Device selector",
                        itemSecondary = { "serial-1 · Offline" },
                        itemEnabled = Option::available,
                    )
                }
            }

            onNodeWithContentDescription("Device selector").performClick()
            onNodeWithText("Offline device").assertIsNotEnabled()
            onNodeWithText("serial-1 · Offline").assertExists()
        }
}
