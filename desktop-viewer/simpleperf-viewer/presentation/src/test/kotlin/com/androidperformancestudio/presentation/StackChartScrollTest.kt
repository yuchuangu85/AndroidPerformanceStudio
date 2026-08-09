package com.androidperformancestudio.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

class StackChartScrollTest {
    @Test
    fun `wheel direction follows stack chart depth direction`() {
        assertEquals(2, stackChartScrollDepthDelta(2f))
        assertEquals(-2, stackChartScrollDepthDelta(-2f))
    }
}
