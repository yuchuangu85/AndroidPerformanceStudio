package com.androidperformancestudio.visualization

import kotlin.test.Test
import kotlin.test.assertEquals

class FlameHorizontalNavigationTest {
    @Test
    fun `wasd actions zoom and pan a normalized horizontal viewport`() {
        val zoomed = FlameHorizontalViewport().navigate(NavigationAction.ZOOM_IN)

        assertEquals(FlameHorizontalViewport(0.25, 0.75), zoomed)
        assertEquals(FlameHorizontalViewport(0.125, 0.625), zoomed.navigate(NavigationAction.PAN_LEFT))
        assertEquals(FlameHorizontalViewport(0.375, 0.875), zoomed.navigate(NavigationAction.PAN_RIGHT))
        assertEquals(FlameHorizontalViewport(), zoomed.navigate(NavigationAction.ZOOM_OUT))
    }

    @Test
    fun `horizontal navigation clamps at profile edges`() {
        val left = FlameHorizontalViewport(0.0, 0.25)
        val right = FlameHorizontalViewport(0.75, 1.0)

        assertEquals(left, left.navigate(NavigationAction.PAN_LEFT))
        assertEquals(right, right.navigate(NavigationAction.PAN_RIGHT))
        assertEquals(FlameHorizontalViewport(), FlameHorizontalViewport().navigate(NavigationAction.ZOOM_OUT))
    }
}
