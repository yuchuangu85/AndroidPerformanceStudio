package com.androidperformancestudio.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
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

            onNodeWithText("91.67%").fetchSemanticsNode()
            onNodeWithText("renderFrame").fetchSemanticsNode()
            onNodeWithText("Stack Type: Native").fetchSemanticsNode()
            onNodeWithText("Category: Native").fetchSemanticsNode()
            onNodeWithText("Resource: libui.so").fetchSemanticsNode()
            onNodeWithText("Running").fetchSemanticsNode()
            onNodeWithText("Overall").fetchSemanticsNode()
            assertEquals(2, onAllNodesWithText("2,200").fetchSemanticsNodes().size)
            assertEquals(2, onAllNodesWithText("320").fetchSemanticsNodes().size)
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
