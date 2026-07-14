package com.androidperformancestudio.visualization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PerfettoNavigationBindingsTest {
    @Test
    fun `WASD matches Perfetto timeline navigation`() {
        assertEquals(NavigationAction.ZOOM_IN, PerfettoNavigationBindings.actionForKey('w'))
        assertEquals(NavigationAction.ZOOM_OUT, PerfettoNavigationBindings.actionForKey('S'))
        assertEquals(NavigationAction.PAN_LEFT, PerfettoNavigationBindings.actionForKey('a'))
        assertEquals(NavigationAction.PAN_RIGHT, PerfettoNavigationBindings.actionForKey('D'))
    }

    @Test
    fun `unrelated keys are not consumed`() {
        assertNull(PerfettoNavigationBindings.actionForKey('x'))
    }

    @Test
    fun `modified mouse wheel matches Perfetto zoom behavior`() {
        assertEquals(
            NavigationAction.ZOOM_IN,
            PerfettoNavigationBindings.actionForScroll(verticalDelta = -1f, controlPressed = true),
        )
        assertEquals(
            NavigationAction.ZOOM_OUT,
            PerfettoNavigationBindings.actionForScroll(verticalDelta = 1f, controlPressed = true),
        )
        assertNull(PerfettoNavigationBindings.actionForScroll(verticalDelta = 1f, controlPressed = false))
        assertNull(PerfettoNavigationBindings.actionForScroll(verticalDelta = 0f, controlPressed = true))
    }
}
