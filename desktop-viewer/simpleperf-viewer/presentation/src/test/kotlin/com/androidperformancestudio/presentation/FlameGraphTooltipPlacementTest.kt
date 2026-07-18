package com.androidperformancestudio.presentation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

class FlameGraphTooltipPlacementTest {
    @Test
    fun `Firefox tooltip follows the mouse with eleven pixel offset`() {
        assertEquals(
            IntOffset(111, 91),
            firefoxTooltipOffset(Offset(100f, 80f), IntSize(120, 60), IntSize(500, 300)),
        )
    }

    @Test
    fun `Firefox tooltip flips before the pointer near viewport edges`() {
        assertEquals(
            IntOffset(259, 189),
            firefoxTooltipOffset(Offset(390f, 260f), IntSize(120, 60), IntSize(500, 300)),
        )
    }

    @Test
    fun `Firefox tooltip uses the visual margin when neither side fits`() {
        assertEquals(
            IntOffset(8, 8),
            firefoxTooltipOffset(Offset(50f, 30f), IntSize(480, 280), IntSize(500, 300)),
        )
    }
}
