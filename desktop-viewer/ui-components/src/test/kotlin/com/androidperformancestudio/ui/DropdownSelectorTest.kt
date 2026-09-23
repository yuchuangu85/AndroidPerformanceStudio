package com.androidperformancestudio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DropdownSelectorTest {
    @Test
    fun `nested viewer theme inherits the enclosing selected accent`() =
        runDesktopComposeUiTest {
            val accent = Color(0xFFD4042D)
            var nestedPrimary = Color.Unspecified

            setContent {
                ViewerTheme(darkTheme = false, accentColor = accent) {
                    ViewerTheme(darkTheme = false) {
                        nestedPrimary = MaterialTheme.colorScheme.primary
                    }
                }
            }

            assertEquals(accent, nestedPrimary)
        }

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
    fun `searchable dropdown filters without changing selection and clears query on reopen`() =
        runDesktopComposeUiTest {
            var selected by mutableStateOf<String?>(null)
            setContent {
                ViewerTheme(darkTheme = false) {
                    DropdownSelector(
                        items = listOf("com.example.Camera", "com.sample.Maps", "org.example.Music"),
                        selectedItem = selected,
                        onItemSelected = { selected = it },
                        itemLabel = { it },
                        placeholder = "App",
                        selectorDescription = "App selector",
                        searchable = true,
                    )
                }
            }

            onNodeWithContentDescription("App selector").performClick()
            onNodeWithTag("dropdown-selector-search").assertIsFocused()
            onNodeWithTag("dropdown-selector-search").performTextInput("MAP")
            onNodeWithText("com.sample.Maps").assertExists()
            onNodeWithText("com.example.Camera").assertDoesNotExist()
            assertEquals(null, selected)
            onNodeWithText("com.sample.Maps").performClick()
            assertEquals("com.sample.Maps", selected)

            onNodeWithContentDescription("App selector").performClick()
            onNodeWithText("com.example.Camera").assertExists()
            onNodeWithText("org.example.Music").assertExists()
            onNodeWithTag("dropdown-selector-search").performTextInput("absent")
            onNodeWithText("No matching options").assertExists()
            onNodeWithText("com.example.Camera").assertDoesNotExist()
            assertEquals("com.sample.Maps", selected)
        }

    @Test
    fun `search matches alternate text and keeps disabled results disabled`() =
        runDesktopComposeUiTest {
            data class Option(val label: String, val packageName: String, val available: Boolean)
            val options =
                listOf(
                    Option("Camera", "com.example.camera", false),
                    Option("Maps", "com.example.maps", true),
                )
            setContent {
                ViewerTheme(darkTheme = false) {
                    DropdownSelector(
                        items = options,
                        selectedItem = null,
                        onItemSelected = {},
                        itemLabel = Option::label,
                        itemSearchText = { "${it.label} ${it.packageName}" },
                        itemEnabled = Option::available,
                        placeholder = "App",
                        selectorDescription = "App selector",
                        searchable = true,
                    )
                }
            }

            onNodeWithContentDescription("App selector").performClick()
            onNodeWithTag("dropdown-selector-search").performTextInput("CAMERA")
            onNodeWithText("Camera").assertIsNotEnabled()
            onNodeWithText("Maps").assertDoesNotExist()
        }

    @Test
    fun `filter handles case whitespace and preserves source order`() {
        val items = listOf("com.example.Camera", "com.sample.Maps", "org.example.Music")
        assertEquals(listOf("com.example.Camera", "org.example.Music"), filterDropdownItems(items, "  ExAmPlE ") { it })
        assertTrue(filterDropdownItems(items, "", String::toString) === items)
        assertFalse(filterDropdownItems(items, "missing") { it }.isNotEmpty())
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
