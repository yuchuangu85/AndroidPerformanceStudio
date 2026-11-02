package com.androidperformancestudio.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlameGraphScrollTest {
    @Test
    fun `legacy row helper remains compatible for callers awaiting migration`() {
        assertEquals(-1, flameGraphScrollRowDelta(0.25f))
        assertEquals(2, flameGraphScrollRowDelta(-2f))
        assertNull(flameGraphScrollRowDelta(1f, controlPressed = true))
    }

    @Test
    fun `wheel down moves the flame graph content up`() {
        assertEquals(-2f, flameGraphScrollPixelDelta(2f))
        assertEquals(-0.25f, flameGraphScrollPixelDelta(0.25f))
    }

    @Test
    fun `wheel up moves the flame graph content down`() {
        assertEquals(2f, flameGraphScrollPixelDelta(-2f))
        assertEquals(0.25f, flameGraphScrollPixelDelta(-0.25f))
    }

    @Test
    fun `invalid wheel deltas do not scroll`() {
        assertNull(flameGraphScrollPixelDelta(0f))
        assertNull(flameGraphScrollPixelDelta(Float.NaN))
        assertNull(flameGraphScrollPixelDelta(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `ctrl and shift wheel deltas never move the flame viewport`() {
        listOf(-2f, 2f, 0.25f).forEach { delta ->
            assertNull(flameGraphScrollPixelDelta(delta, controlPressed = true))
            assertNull(flameGraphScrollPixelDelta(delta, shiftPressed = true))
            assertNull(flameGraphScrollPixelDelta(delta, controlPressed = true, shiftPressed = true))
        }
    }
}
