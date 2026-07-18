package com.androidperformancestudio.presentation

import com.androidperformancestudio.profileanalysis.StackChartBlock
import com.androidperformancestudio.profileanalysis.StackChartBlockId
import kotlin.test.Test
import kotlin.test.assertEquals

class StackChartPresenterTest {
    @Test
    fun `maps time and depth into the visible viewport`() {
        val rect =
            StackChartPresenter.blockRect(
                block = block("mapped", start = 20, end = 40, depth = 2),
                viewport = StackChartViewport(0, 100),
                width = 500f,
            )

        assertEquals(100f, rect.left)
        assertEquals(200f, rect.right)
        assertEquals(32f, rect.top)
        assertEquals(48f, rect.bottom)
    }

    @Test
    fun `hit test returns the topmost matching block`() {
        val blocks =
            listOf(
                block("covered", start = 10, end = 40, depth = 2),
                block("selected", start = 20, end = 30, depth = 2),
            )

        assertEquals(StackChartBlockId("selected"), StackChartPresenter.hitTest(blocks, 25, 2))
    }
}

internal fun block(
    id: String,
    start: Long,
    end: Long,
    depth: Int,
    frameId: Long = 1,
): StackChartBlock =
    StackChartBlock(
        id = StackChartBlockId(id),
        sampleId = 1,
        startNanos = start,
        endNanosExclusive = end,
        depth = depth,
        frameId = frameId,
        threadKey = "thread:1",
        weight = 1,
    )
