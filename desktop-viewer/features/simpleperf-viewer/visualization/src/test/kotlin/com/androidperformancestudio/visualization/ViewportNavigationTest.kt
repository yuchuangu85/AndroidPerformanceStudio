package com.androidperformancestudio.visualization

import kotlin.test.Test
import kotlin.test.assertEquals

class ViewportNavigationTest {
    @Test
    fun `perfetto navigation zooms and pans time viewport within trace bounds`() {
        val bounds = TimeViewport(0, 1_000)
        val viewport = TimeViewport(200, 600)

        assertEquals(TimeViewport(300, 500), viewport.navigate(NavigationAction.ZOOM_IN, bounds))
        assertEquals(TimeViewport(100, 700), viewport.navigate(NavigationAction.ZOOM_OUT, bounds))
        assertEquals(TimeViewport(100, 500), viewport.navigate(NavigationAction.PAN_LEFT, bounds))
        assertEquals(TimeViewport(300, 700), viewport.navigate(NavigationAction.PAN_RIGHT, bounds))
    }

    @Test
    fun `timeline drag selection maps pixels to a stable nanosecond range`() {
        assertEquals(
            TimeViewport(300, 700),
            TimeViewport(100, 900).selection(startPixel = 75f, endPixel = 25f, widthPixels = 100f),
        )
    }

    @Test
    fun `weight viewport zoom uses pointer anchor and clamps to full flame weight`() {
        val bounds = WeightViewport(0, 1_000)
        val viewport = WeightViewport(200, 600)

        assertEquals(
            WeightViewport(350, 550),
            viewport.navigate(NavigationAction.ZOOM_IN, bounds, anchorFraction = 0.75),
        )
        assertEquals(
            WeightViewport(100, 500),
            viewport.navigate(NavigationAction.PAN_LEFT, bounds),
        )
    }
}
