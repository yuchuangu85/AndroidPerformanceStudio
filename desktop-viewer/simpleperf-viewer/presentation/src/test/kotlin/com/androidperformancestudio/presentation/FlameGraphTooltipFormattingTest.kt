package com.androidperformancestudio.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

class FlameGraphTooltipFormattingTest {
    @Test
    fun `percentages use Firefox two significant digit formatting`() {
        assertEquals("0%", firefoxTooltipPercent(0.0))
        assertEquals("0.1%", firefoxTooltipPercent(0.1234))
        assertEquals("1.2%", firefoxTooltipPercent(1.234))
        assertEquals("12%", firefoxTooltipPercent(12.34))
        assertEquals("92%", firefoxTooltipPercent(91.67))
        assertEquals("100%", firefoxTooltipPercent(100.0))
        assertEquals("0%", firefoxTooltipPercent(Double.NaN))
    }
}
