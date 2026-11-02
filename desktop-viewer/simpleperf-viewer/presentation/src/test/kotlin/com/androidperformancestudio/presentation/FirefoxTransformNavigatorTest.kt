package com.androidperformancestudio.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.profileanalysis.CallStackTransform
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.visualization.FirefoxFlameGraphStyle
import com.androidperformancestudio.visualization.FlameTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FirefoxTransformNavigatorTest {
    @Test
    fun `root and earlier crumbs remove later transforms while leaf is inert`() =
        runDesktopComposeUiTest(width = 700, height = 80) {
            val retainedCounts = mutableListOf<Int>()
            setContent {
                MaterialTheme {
                    FirefoxTransformNavigator(
                        transforms = transforms,
                        style = FirefoxFlameGraphStyle.resolve(FlameTheme.LIGHT),
                        onUndo = { error("Must use one truncate event") },
                        onClear = { error("Must use one truncate event") },
                        onRemoveAfter = { retainedCounts += it },
                    )
                }
            }

            onNodeWithTag("transform-crumb-0").performClick()
            onNodeWithText("Focus function").performClick()
            onNodeWithTag("transform-crumb-2").assert(hasClickAction().not())
            assertEquals(listOf(0, 1), retainedCounts)
            onNodeWithText("Undo").assertDoesNotExist()
            onNodeWithText("Clear").assertDoesNotExist()
        }

    @Test
    fun `empty navigator still shows an inert complete root like Firefox`() =
        runDesktopComposeUiTest(width = 400, height = 80) {
            setContent {
                MaterialTheme {
                    FirefoxTransformNavigator(emptyList(), FirefoxFlameGraphStyle.resolve(FlameTheme.LIGHT), {}, {})
                }
            }
            onNodeWithTag("transform-crumb-0").assertExists().assert(hasClickAction().not())
            assertEquals(25f, onNodeWithTag("transform-navigator").fetchSemanticsNode().boundsInRoot.height)
        }

    @Test
    fun `many long crumbs truncate within the viewport without growing the panel`() =
        runDesktopComposeUiTest(width = 320, height = 80) {
            setContent {
                MaterialTheme {
                    FirefoxTransformNavigator(
                        List(20) {
                            CallStackTransform.CollapseResource("/system/" + "very-long-library-name".repeat(10) + it)
                        },
                        FirefoxFlameGraphStyle.resolve(FlameTheme.DARK),
                        {},
                        {},
                        onRemoveAfter = {},
                    )
                }
            }
            val navigator = onNodeWithTag("transform-navigator").fetchSemanticsNode().boundsInRoot
            val leaf = onNodeWithTag("transform-crumb-20").fetchSemanticsNode().boundsInRoot
            assertTrue(navigator.width <= 320f)
            assertTrue(leaf.right <= navigator.right)
        }

    private val transforms =
        listOf(
            CallStackTransform.FocusFunction(FlameFunctionId(1)),
            CallStackTransform.CollapseResource("/system/lib/libui.so"),
        )
}
