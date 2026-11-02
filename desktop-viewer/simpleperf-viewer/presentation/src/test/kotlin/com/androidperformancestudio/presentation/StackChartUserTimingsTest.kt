package com.androidperformancestudio.presentation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.profileanalysis.StackChartBlockId
import com.androidperformancestudio.storage.MarkerProjectionRow
import com.androidperformancestudio.storage.PanelProjection
import com.androidperformancestudio.storage.ProfileMarkerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StackChartUserTimingsTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `user timing rows precede CPU rows and marker clicks never select a fake call node`() =
        runDesktopComposeUiTest(width = 500, height = 120) {
            val snapshot = (stackChartReportState().lastReadyReport!!.stackChart as PanelProjection.Ready).value
            var selected: StackChartBlockId? = StackChartBlockId("block-1")
            var hovered: ProfileMarkerId? = null
            setContent {
                StackChartCanvas(
                    snapshot,
                    StackChartViewport(0, 10_001),
                    selected,
                    onSelect = { selected = it },
                    userTimings = stackChartUserTimingRows(listOf(timingMarker(1, 0, 5_000))),
                    onHoverUserTiming = { marker, _ -> hovered = marker?.id },
                )
            }
            val timing = onNodeWithTag("stack-user-timing-1").fetchSemanticsNode().boundsInRoot
            val cpu = onNodeWithTag("stack-block-block-1").fetchSemanticsNode().boundsInRoot
            assertEquals(16f, cpu.top - timing.top)
            onNodeWithTag("stack-chart-canvas").performMouseInput { moveTo(Offset(200f, 8f)) }
            waitForIdle()
            assertEquals(ProfileMarkerId(1), hovered)
            onNodeWithTag("stack-chart-canvas").performMouseInput { click(Offset(200f, 8f)) }
            mainClock.advanceTimeBy(500)
            waitForIdle()
            assertEquals(StackChartBlockId("block-1"), selected)
        }

    @Test
    fun `only explicit user timings appear and overlapping measures get separate rows`() {
        val rows =
            stackChartUserTimingRows(
                listOf(
                    timingMarker(1, 0, 20),
                    timingMarker(2, 10, 30),
                    timingMarker(3, 20, 40),
                    timingMarker(4, 0, 20).copy(schema = "trace_event"),
                    timingMarker(5, 0, 1).copy(name = "A", interval = false),
                ),
            )
        assertEquals(listOf("A", "measure", "measure"), rows.map { it.name })
        assertEquals(listOf(1L, 3L), rows[1].markers.map { it.id.value })
        assertEquals(listOf(2L), rows[2].markers.map { it.id.value })
        assertEquals(ProfileMarkerId(3), rows[1].markerAt(20)?.id)
        assertNull(rows[1].markerAt(40))
    }

    @Test
    fun `sub pixel wheel and drag movement accumulates instead of rounding to rows`() {
        assertEquals(0.75f, stackChartScrollPixels(10, 100, 16f, 0.75f))
        assertEquals(76f, stackChartScrollPixels(10, 100, 16f, 200f))
        val rows = stackChartUserTimingRows((0 until 10_000).map { timingMarker(it.toLong(), it.toLong(), it + 1L) })
        val layout = visibleStackChartUserTimings(rows, StackChartViewport(0, 10_000), 500f, 16f, 16f, 0f)
        assertTrue(layout.size <= 501)
    }
}

internal fun timingMarker(
    id: Long,
    start: Long,
    end: Long,
): MarkerProjectionRow =
    MarkerProjectionRow(
        id = ProfileMarkerId(id),
        sourceId = "source",
        processId = 1,
        threadId = 2,
        threadName = "main",
        startNanos = start,
        endNanosExclusive = end,
        interval = true,
        schema = "UserTiming",
        name = "measure",
        payloadJson = "{}",
    )
