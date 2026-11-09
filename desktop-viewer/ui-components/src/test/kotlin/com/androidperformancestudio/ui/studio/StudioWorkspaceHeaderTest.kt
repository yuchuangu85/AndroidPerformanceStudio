@file:Suppress("MagicNumber")

package com.androidperformancestudio.ui.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StudioWorkspaceHeaderTest {
    @Test
    fun `wide heading keeps Startup height and supporting text`() =
        runDesktopComposeUiTest(width = 1000, height = 400) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    StudioWorkspaceHeader(
                        title = "Frame Profiler",
                        subtitle = "Frame timing and evidence",
                        supportingText = "72 frames",
                        modifier = Modifier.testTag("header"),
                        titleModifier = Modifier.testTag("title"),
                    )
                }
            }

            onNodeWithTag("title").assertIsDisplayed()
            onNodeWithText("72 frames").assertIsDisplayed()
            assertEquals(94f, onNodeWithTag("header").fetchSemanticsNode().boundsInRoot.height, 1f)
        }

    @Test
    fun `narrow heading omits only nonessential supporting text`() =
        runDesktopComposeUiTest(width = 480, height = 300) {
            setContent {
                ViewerTheme(darkTheme = false) {
                    StudioWorkspaceHeader("Memory Profiler", "Heap evidence", "Supporting context")
                }
            }

            onNodeWithText("Memory Profiler").assertIsDisplayed()
            onNodeWithText("Heap evidence").assertIsDisplayed()
            onNodeWithText("Supporting context").assertDoesNotExist()
        }

    @Test
    fun `English heading grows rather than clipping at enlarged font scale`() =
        assertLargeFontHeader("CPU Analysis / Evidence Workspace", "Recorded timing, source and device evidence.")

    @Test
    fun `Chinese heading grows rather than clipping at enlarged font scale`() =
        assertLargeFontHeader("应用性能分析与证据工作区", "查看真实采集数据和设备状态，核对每条分析证据。")

    @Test
    fun `header uses a uniform background without accent decoration`() =
        runDesktopComposeUiTest(width = 800, height = 300) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    StudioWorkspaceHeader("GPU Analysis Hub", modifier = Modifier.testTag("header"))
                }
            }

            val pixels = onNodeWithTag("header").captureToImage().toPixelMap()
            assertEquals(pixels[pixels.width - 10, 10], pixels[pixels.width - 10, pixels.height - 10])
        }

    private fun assertLargeFontHeader(
        title: String,
        subtitle: String,
    ) = runDesktopComposeUiTest(width = 400, height = 300) {
        setContent {
            ViewerTheme(darkTheme = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                    Box(Modifier.width(360.dp)) {
                        StudioWorkspaceHeader(title, subtitle, modifier = Modifier.testTag("header"))
                    }
                }
            }
        }

        val titleNode = onNodeWithText(title)
        val subtitleNode = onNodeWithText(subtitle)
        listOf(titleNode, subtitleNode).forEach { node ->
            node.assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse(
                layout.hasVisualOverflow,
                "${layout.layoutInput.text.text}: ${layout.lineCount} lines in ${layout.size}; " +
                    "width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, paragraphHeight=${layout.multiParagraph.height}",
            )
        }
        val titleBounds = titleNode.fetchSemanticsNode().boundsInRoot
        val subtitleBounds = subtitleNode.fetchSemanticsNode().boundsInRoot
        assertTrue(subtitleBounds.top >= titleBounds.bottom)
        assertTrue(onNodeWithTag("header").fetchSemanticsNode().boundsInRoot.height > 94f)
    }
}
