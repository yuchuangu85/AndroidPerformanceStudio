package com.androidperformancestudio.presentation

import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlameGraphHoverStateTest {
    @Test
    fun `hover is visible only for the layout token that produced its hit test`() {
        val firstLayout = Any()
        val nextLayout = Any()
        val hovered = FlameGraphHoverState().update(firstLayout, FlameCallNodeId(7))

        assertEquals(FlameCallNodeId(7), hovered.nodeIdFor(firstLayout))
        assertNull(hovered.nodeIdFor(nextLayout))
    }
}
