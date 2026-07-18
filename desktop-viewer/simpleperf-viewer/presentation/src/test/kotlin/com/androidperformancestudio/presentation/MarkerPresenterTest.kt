package com.androidperformancestudio.presentation

import com.androidperformancestudio.storage.MarkerProjectionRow
import com.androidperformancestudio.storage.ProfileMarkerId
import kotlin.test.Test
import kotlin.test.assertIs

class MarkerPresenterTest {
    @Test
    fun `point and interval markers use distinct geometry`() {
        val viewport = StackChartViewport(0, 100)

        assertIs<MarkerGlyph.Point>(MarkerPresenter.glyph(marker(interval = false), viewport, 500f))
        assertIs<MarkerGlyph.Interval>(MarkerPresenter.glyph(marker(interval = true), viewport, 500f))
    }
}

internal fun marker(
    id: Long = 7,
    interval: Boolean = false,
    start: Long = 30,
    end: Long = if (interval) 50 else 31,
): MarkerProjectionRow =
    MarkerProjectionRow(
        id = ProfileMarkerId(id),
        sourceId = "simpleperf",
        processId = 7421,
        threadId = 7440,
        threadName = "RenderThread",
        startNanos = start,
        endNanosExclusive = end,
        interval = interval,
        schema = "trace",
        name = if (interval) "draw" else "vsync",
        payloadJson = "{\"frame\":42}",
    )
