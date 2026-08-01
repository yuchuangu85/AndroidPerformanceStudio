package com.androidperformancestudio.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.profileanalysis.CallStackTransform
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FrameImplementation
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.visualization.FirefoxFlameGraphStyle
import com.androidperformancestudio.visualization.FlameTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FirefoxFlameGraphChromeTest {
    @Test
    fun `frame tooltip exposes Firefox call node details and timing columns`() =
        runDesktopComposeUiTest(width = 600, height = 360) {
            setContent {
                MaterialTheme {
                    FirefoxFlameGraphTooltip(
                        facts =
                            FlameGraphTooltipFacts(
                                function = "renderFrame",
                                category = "Native",
                                implementation = FrameImplementation.NATIVE,
                                resource = "libui.so",
                                inclusiveWeight = 54_000_000,
                                selfWeight = 4_000_000,
                                sampleCount = 54,
                                selfSampleCount = 4,
                                categorySamples =
                                    listOf(
                                        FlameGraphTooltipCategorySamples("User", running = 12, self = 0),
                                        FlameGraphTooltipCategorySamples("Native", running = 32, self = 4),
                                        FlameGraphTooltipCategorySamples("JIT", running = 10, self = 0),
                                    ),
                                threadCount = 2,
                                percentage = 100.0,
                                previewRangeWeight = null,
                            ),
                        style = FirefoxFlameGraphStyle.resolve(FlameTheme.LIGHT),
                    )
                }
            }

            onNodeWithText("54.0ms (100%)").fetchSemanticsNode()
            onNodeWithText("renderFrame").fetchSemanticsNode()
            onNodeWithText("Stack Type:").fetchSemanticsNode()
            onNodeWithText("Category:").fetchSemanticsNode()
            onNodeWithText("Resource:").fetchSemanticsNode()
            onNodeWithText("libui.so").fetchSemanticsNode()
            onNodeWithText("Running").fetchSemanticsNode()
            onNodeWithText("Self").fetchSemanticsNode()
            onNodeWithText("Overall").fetchSemanticsNode()
            onNodeWithText("User").fetchSemanticsNode()
            assertEquals(3, onAllNodesWithText("Native").fetchSemanticsNodes().size)
            onNodeWithText("JIT").fetchSemanticsNode()
            onNodeWithText("54 samples").fetchSemanticsNode()
            assertEquals(2, onAllNodesWithText("4 samples").fetchSemanticsNodes().size)

            val runningMeter = onNodeWithTag("firefox-tooltip-overall-running-meter").fetchSemanticsNode().boundsInRoot
            val runningBar = onNodeWithTag("firefox-tooltip-overall-running-bar").fetchSemanticsNode().boundsInRoot
            val selfBar = onNodeWithTag("firefox-tooltip-overall-self-bar").fetchSemanticsNode().boundsInRoot
            assertEquals(runningMeter.width, runningBar.width, absoluteTolerance = 1f)
            assertEquals(runningMeter.width * 4f / 54f, selfBar.width, absoluteTolerance = 1f)
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
                                inclusiveWeight = 12_345_000,
                                selfWeight = 0,
                                sampleCount = 8,
                                selfSampleCount = 0,
                                categorySamples = emptyList(),
                                threadCount = 1,
                                percentage = 0.1234,
                                previewRangeWeight = null,
                            ),
                        style = FirefoxFlameGraphStyle.resolve(FlameTheme.DARK),
                    )
                }
            }

            onNodeWithText("12.3ms (0.1%)").fetchSemanticsNode()
            onNodeWithText("8 samples").fetchSemanticsNode()
            onNodeWithText("—").fetchSemanticsNode()
            onNodeWithText("Category:").assertDoesNotExist()
            onNodeWithText("Resource:").assertDoesNotExist()
            onNodeWithTag("firefox-tooltip-category-row").assertDoesNotExist()
            val tooltip = onNodeWithTag("firefox-flame-tooltip").fetchSemanticsNode().boundsInRoot
            assertTrue(tooltip.width <= 600f, "Firefox tooltip exceeded its 600px maximum: ${tooltip.width}")
        }

    @Test
    fun `frame tooltip localizes sample units and known categories`() =
        runDesktopComposeUiTest(width = 640, height = 320) {
            setContent {
                MaterialTheme {
                    SimpleperfLocalization(UiLanguage.SIMPLIFIED_CHINESE) {
                        FirefoxFlameGraphTooltip(
                            facts = localizedTooltipFacts(),
                            style = FirefoxFlameGraphStyle.resolve(FlameTheme.LIGHT),
                        )
                    }
                }
            }

            onNodeWithText("类别：").fetchSemanticsNode()
            onNodeWithText("渲染").fetchSemanticsNode()
            onNodeWithText("用户").fetchSemanticsNode()
            assertEquals(2, onAllNodesWithText("原生").fetchSemanticsNodes().size)
            onNodeWithText("54 个样本").fetchSemanticsNode()
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

private fun localizedTooltipFacts() =
    FlameGraphTooltipFacts(
        function = "renderFrame",
        category = "Rendering",
        implementation = FrameImplementation.NATIVE,
        resource = "libui.so",
        inclusiveWeight = 54_000_000,
        selfWeight = 4_000_000,
        sampleCount = 54,
        selfSampleCount = 4,
        categorySamples =
            listOf(
                FlameGraphTooltipCategorySamples("User", running = 12, self = 0),
                FlameGraphTooltipCategorySamples("Native", running = 42, self = 4),
            ),
        threadCount = 2,
        percentage = 100.0,
        previewRangeWeight = null,
    )
