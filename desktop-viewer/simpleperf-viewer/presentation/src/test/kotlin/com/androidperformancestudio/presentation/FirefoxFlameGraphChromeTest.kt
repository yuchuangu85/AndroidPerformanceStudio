package com.androidperformancestudio.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.CallStackTransform
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FrameImplementation
import com.androidperformancestudio.profileanalysis.ImplementationFilter
import com.androidperformancestudio.visualization.FirefoxFlameGraphStyle
import com.androidperformancestudio.visualization.FlameTheme
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FirefoxFlameGraphChromeTest {
    @Test
    fun `frame tooltip exposes Firefox call node details and timing columns`() =
        runDesktopComposeUiTest(width = 500, height = 260) {
            setContent {
                MaterialTheme {
                    FirefoxFlameGraphTooltip(
                        facts =
                            FlameGraphTooltipFacts(
                                function = "renderFrame",
                                category = "Native",
                                implementation = FrameImplementation.NATIVE,
                                resource = "libui.so",
                                inclusiveWeight = 2_200,
                                selfWeight = 320,
                                sampleCount = 22,
                                threadCount = 2,
                                percentage = 91.67,
                                previewRangeWeight = null,
                            ),
                        style = FirefoxFlameGraphStyle.resolve(FlameTheme.LIGHT),
                    )
                }
            }

            onNodeWithText("92%").fetchSemanticsNode()
            onNodeWithText("renderFrame").fetchSemanticsNode()
            onNodeWithText("Stack Type:").fetchSemanticsNode()
            onNodeWithText("Category:").fetchSemanticsNode()
            onNodeWithText("Resource:").fetchSemanticsNode()
            onNodeWithText("libui.so").fetchSemanticsNode()
            onNodeWithText("Running").fetchSemanticsNode()
            onNodeWithText("Self").fetchSemanticsNode()
            onNodeWithText("Overall").fetchSemanticsNode()
            assertEquals(2, onAllNodesWithText("2,200").fetchSemanticsNodes().size)
            assertEquals(2, onAllNodesWithText("320").fetchSemanticsNodes().size)

            val runningMeter = onNodeWithTag("firefox-tooltip-overall-running-meter").fetchSemanticsNode().boundsInRoot
            val runningBar = onNodeWithTag("firefox-tooltip-overall-running-bar").fetchSemanticsNode().boundsInRoot
            val selfBar = onNodeWithTag("firefox-tooltip-overall-self-bar").fetchSemanticsNode().boundsInRoot
            assertEquals(runningMeter.width, runningBar.width, absoluteTolerance = 1f)
            assertEquals(runningMeter.width * 320f / 2_200f, selfBar.width, absoluteTolerance = 1f)
            assertEquals(10f, onNodeWithTag("firefox-tooltip-category-swatch").fetchSemanticsNode().boundsInRoot.width)
        }

    @Test
    fun `frame tooltip follows Firefox formatting and hides unavailable fields`() =
        runDesktopComposeUiTest(width = 640, height = 240) {
            setContent {
                MaterialTheme {
                    FirefoxFlameGraphTooltip(
                        facts =
                            FlameGraphTooltipFacts(
                                function = "idle",
                                category = null,
                                implementation = FrameImplementation.UNKNOWN,
                                resource = null,
                                inclusiveWeight = 12_345,
                                selfWeight = 0,
                                sampleCount = 8,
                                threadCount = 1,
                                percentage = 0.1234,
                                previewRangeWeight = null,
                            ),
                        style = FirefoxFlameGraphStyle.resolve(FlameTheme.DARK),
                    )
                }
            }

            onNodeWithText("0.1%").fetchSemanticsNode()
            onNodeWithText("12,345").fetchSemanticsNode()
            onNodeWithText("—").fetchSemanticsNode()
            onNodeWithText("Category:").assertDoesNotExist()
            onNodeWithText("Resource:").assertDoesNotExist()
            onNodeWithTag("firefox-tooltip-category-row").assertDoesNotExist()
            val tooltip = onNodeWithTag("firefox-flame-tooltip").fetchSemanticsNode().boundsInRoot
            assertTrue(tooltip.width <= 600f, "Firefox tooltip exceeded its 600px maximum: ${tooltip.width}")
        }

    @Test
    fun `compact toolbar dispatches existing direction implementation and transform actions`() =
        runDesktopComposeUiTest(width = 1_000, height = 120) {
            var direction: CallStackDirection? = null
            var implementation: ImplementationFilter? = null
            var undoCount = 0
            var clearCount = 0

            setContent {
                MaterialTheme {
                    FirefoxFlameGraphToolbar(
                        sessionIdentity = Path.of("fixture"),
                        authoritativeSearch = "",
                        implementation = ImplementationFilter.ALL,
                        direction = CallStackDirection.FORWARD,
                        style = FirefoxFlameGraphStyle.resolve(FlameTheme.LIGHT),
                        hasTransforms = true,
                        onSearch = {},
                        onImplementation = { implementation = it },
                        onDirection = { direction = it },
                        onUndo = { undoCount++ },
                        onClear = { clearCount++ },
                    )
                }
            }

            onNodeWithText("Forward").assertIsSelected()
            assertEquals(24f, onNodeWithText("Forward").fetchSemanticsNode().boundsInRoot.height)
            onNodeWithText("Inverted").performClick()
            onNodeWithText("Managed").performClick()
            onNodeWithText("Undo").performClick()
            onNodeWithText("Clear").performClick()

            assertEquals(CallStackDirection.INVERTED, direction)
            assertEquals(ImplementationFilter.MANAGED, implementation)
            assertEquals(1, undoCount)
            assertEquals(1, clearCount)
        }

    @Test
    fun `transform navigator exposes segments and trailing recovery actions`() =
        runDesktopComposeUiTest(width = 700, height = 80) {
            var undoCount = 0
            var clearCount = 0

            setContent {
                MaterialTheme {
                    FirefoxTransformNavigator(
                        transforms =
                            listOf(
                                CallStackTransform.FocusFunction(FlameFunctionId(1)),
                                CallStackTransform.CollapseResource("/system/lib/libui.so"),
                            ),
                        style = FirefoxFlameGraphStyle.resolve(FlameTheme.DARK),
                        onUndo = { undoCount++ },
                        onClear = { clearCount++ },
                    )
                }
            }

            onNodeWithText("Focus function").fetchSemanticsNode()
            onNodeWithText("Collapse libui.so").fetchSemanticsNode()
            onNodeWithText("Undo").performClick()
            onNodeWithText("Clear").performClick()

            assertEquals(1, undoCount)
            assertEquals(1, clearCount)
        }
}
