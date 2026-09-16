package com.androidperformancestudio.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CompactSearchFieldTest {
    @Test
    fun `search field edits and clears a compact query`() =
        runDesktopComposeUiTest {
            var query by mutableStateOf("")

            setContent {
                ViewerTheme(darkTheme = false) {
                    CompactSearchField(
                        value = query,
                        placeholder = "Search hierarchy",
                        onValueChange = { query = it },
                    )
                }
            }

            onNodeWithText("Search hierarchy").assertIsDisplayed()
            onNodeWithContentDescription("Search hierarchy").performTextInput("Button")
            assertEquals("Button", query)
            onNodeWithText("✕").performClick()
            assertEquals("", query)
        }

    @Test
    fun `compact navigation button dispatches its action`() =
        runDesktopComposeUiTest {
            var clicks = 0

            setContent {
                ViewerTheme(darkTheme = false) {
                    CompactSearchNavigationButton(
                        label = "▶",
                        contentDescription = "Next search match",
                        enabled = true,
                        onClick = { clicks += 1 },
                    )
                }
            }

            onNodeWithContentDescription("Next search match").performClick()
            assertEquals(1, clicks)
        }
}
