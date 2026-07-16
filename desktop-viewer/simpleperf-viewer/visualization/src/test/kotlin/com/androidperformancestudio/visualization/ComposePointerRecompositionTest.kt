package com.androidperformancestudio.visualization

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ComposePointerRecompositionTest {
    @Test
    fun `timeline drag survives preview recomposition and commits exactly once`() =
        runDesktopComposeUiTest(width = 320, height = 120) {
            val previews = mutableListOf<TimeViewport>()
            val commits = mutableListOf<TimeViewport>()
            var cancellations = 0
            var recompositions by mutableIntStateOf(0)

            setContent {
                recompositions
                TimelineCanvas(
                    frame = TimelineFrame(listOf(TimelineColumn(1))),
                    viewport = TimeViewport(0, 1_000),
                    onRangePreview = { range ->
                        previews += range
                        recompositions++
                    },
                    onRangeCommit = commits::add,
                    onRangeCancel = { cancellations++ },
                    modifier = Modifier.size(300.dp, 80.dp).testTag(TIMELINE_TAG),
                )
            }

            onNodeWithTag(TIMELINE_TAG).performMouseInput {
                moveTo(Offset(20f, 40f))
                press()
            }
            waitForIdle()
            onNodeWithTag(TIMELINE_TAG).performMouseInput { moveTo(Offset(100f, 40f)) }
            waitForIdle()
            onNodeWithTag(TIMELINE_TAG).performMouseInput { moveTo(Offset(220f, 40f)) }
            waitForIdle()
            onNodeWithTag(TIMELINE_TAG).performMouseInput { release() }
            waitForIdle()

            assertTrue(previews.size >= 2, "the active drag must publish multiple previews")
            assertEquals(1, commits.size)
            assertEquals(0, cancellations)
            assertEquals(previews.last(), commits.single())
        }

    @Test
    fun `timeline cancellation clears exactly once after recomposition`() =
        runDesktopComposeUiTest(width = 320, height = 120) {
            var previews = 0
            var cancellations = 0
            var recompositions by mutableIntStateOf(0)
            var viewport by mutableStateOf(TimeViewport(0, 1_000))

            setContent {
                recompositions
                TimelineCanvas(
                    frame = TimelineFrame(listOf(TimelineColumn(1))),
                    viewport = viewport,
                    onRangePreview = {
                        previews++
                        recompositions++
                    },
                    onRangeCommit = { error("cancelled drag must not commit") },
                    onRangeCancel = { cancellations++ },
                    modifier = Modifier.size(300.dp, 80.dp).testTag(TIMELINE_TAG),
                )
            }

            onNodeWithTag(TIMELINE_TAG).performMouseInput {
                moveTo(Offset(20f, 40f))
                press()
            }
            waitForIdle()
            onNodeWithTag(TIMELINE_TAG).performMouseInput { moveTo(Offset(180f, 40f)) }
            waitForIdle()
            runOnUiThread { viewport = TimeViewport(100, 900) }
            waitForIdle()

            assertTrue(previews > 0)
            assertEquals(1, cancellations)
        }

    @Test
    fun `flame tap and double tap survive callback recomposition`() =
        runDesktopComposeUiTest(width = 240, height = 100) {
            val intents = mutableListOf<FlameGraphIntent>()
            var recompositions by mutableIntStateOf(0)
            val nodeId = FlameCallNodeId(7)
            val layout = VisibleFlameLayout(listOf(VisibleFlameNode(0, nodeId, 0f, 0f, 200f, 80f)), 0..0)

            setContent {
                recompositions
                FlameGraphCanvas(
                    layout = layout,
                    selectedNodeId = null,
                    onIntent = { intent ->
                        intents += intent
                        recompositions++
                    },
                    modifier = Modifier.size(220.dp, 80.dp).testTag(FLAME_TAG),
                )
            }

            onNodeWithTag(FLAME_TAG).performMouseInput { moveTo(Offset(50f, 40f)) }
            waitForIdle()
            onNodeWithTag(FLAME_TAG).performMouseInput {
                press()
                release()
            }
            mainClock.advanceTimeBy(400)
            waitForIdle()
            assertTrue(intents.any { it == FlameGraphIntent.Select(nodeId) })
            onNodeWithTag(FLAME_TAG).performMouseInput { doubleClick(Offset(50f, 40f)) }
            waitForIdle()

            assertIs<FlameGraphIntent.OpenDetails>(intents.last { it !is FlameGraphIntent.Hover })
        }

    private companion object {
        const val TIMELINE_TAG = "timeline-under-test"
        const val FLAME_TAG = "flame-under-test"
    }
}
