package com.androidperformancestudio.ui.studio

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class StudioComponentsTest {
    @Test
    fun `studio tokens preserve the documented dense shell measurements`() {
        assertEquals(56, StudioTokens.topBarHeight.value.toInt())
        assertEquals(216, StudioTokens.navigationExpandedWidth.value.toInt())
        assertEquals(64, StudioTokens.navigationCollapsedWidth.value.toInt())
        assertEquals(28, StudioTokens.globalStatusBarHeight.value.toInt())
    }

    @Test
    fun `evidence badge exposes localized evidence text and semantics`() =
        runDesktopComposeUiTest {
            setContent {
                ViewerTheme(darkTheme = true) {
                    StudioEvidenceBadge(StudioEvidence.INFERRED, UiLanguage.ENGLISH)
                }
            }

            onNodeWithText("Inferred").assertIsDisplayed()
            onNodeWithContentDescription("Evidence: Inferred").assertIsDisplayed()
        }

    @Test
    fun `evidence badge supports simplified Chinese labels`() =
        runDesktopComposeUiTest {
            setContent {
                ViewerTheme(darkTheme = false) {
                    StudioEvidenceBadge(StudioEvidence.INFERRED, UiLanguage.SIMPLIFIED_CHINESE)
                }
            }

            onNodeWithText("推断").assertIsDisplayed()
            onNodeWithContentDescription("证据：推断").assertIsDisplayed()
        }

    @Test
    fun `metric and feedback components compose within the shared viewer theme`() =
        runDesktopComposeUiTest {
            val accent = Color(0xFF7B61FF)
            var observedAccent: Color? = null

            setContent {
                ViewerTheme(darkTheme = false, displayScale = 1.25f, accentColor = accent) {
                    observedAccent = LocalViewerColors.current.accent
                    StudioMetricCard(
                        label = "Captured frames",
                        value = "42",
                        supportingText = "Artifact-backed",
                    )
                    StudioErrorState(
                        title = "Import failed",
                        detail = "Choose a valid capture artifact and retry.",
                    )
                }
            }

            assertEquals(accent, observedAccent)
            onNodeWithContentDescription("Captured frames: 42").assertIsDisplayed()
            onNodeWithText("Import failed").assertIsDisplayed()
        }

    @Test
    fun `search field accepts input using the shared viewer theme`() =
        runDesktopComposeUiTest {
            setContent {
                ViewerTheme(darkTheme = true) {
                    var query by remember { mutableStateOf("") }
                    StudioSearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Search pages",
                        modifier = Modifier.testTag("studio-search"),
                    )
                }
            }

            onNodeWithTag("studio-search").performTextInput("CPU")
            onNodeWithText("CPU").assertIsDisplayed()
        }

    @Test
    fun `data table renders a panel title before its column headings and rows`() =
        runDesktopComposeUiTest {
            setContent {
                ViewerTheme(darkTheme = false) {
                    StudioDataTable(
                        columns = listOf(StudioTableColumn("Artifact"), StudioTableColumn("Feature")),
                        title = "Recent sessions",
                    ) {
                        androidx.compose.material3.Text("capture.hprof")
                    }
                }
            }

            onNodeWithText("Recent sessions").assertIsDisplayed()
            onNodeWithText("Artifact").assertIsDisplayed()
            onNodeWithText("Feature").assertIsDisplayed()
            onNodeWithText("capture.hprof").assertIsDisplayed()
        }
}
