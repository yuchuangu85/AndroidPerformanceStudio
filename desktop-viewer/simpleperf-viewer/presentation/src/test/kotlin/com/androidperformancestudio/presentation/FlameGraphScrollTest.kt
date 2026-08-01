package com.androidperformancestudio.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlameGraphScrollTest {
    @Test
    fun `wheel down moves the flame graph content up`() {
        assertEquals(-2, flameGraphScrollRowDelta(2f))
        assertEquals(-1, flameGraphScrollRowDelta(0.25f))
    }

    @Test
    fun `wheel up moves the flame graph content down`() {
        assertEquals(2, flameGraphScrollRowDelta(-2f))
        assertEquals(1, flameGraphScrollRowDelta(-0.25f))
    }

    @Test
    fun `invalid wheel deltas do not scroll`() {
        assertNull(flameGraphScrollRowDelta(0f))
        assertNull(flameGraphScrollRowDelta(Float.NaN))
        assertNull(flameGraphScrollRowDelta(Float.POSITIVE_INFINITY))
    }
}
