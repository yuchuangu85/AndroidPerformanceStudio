package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PreviewZoomStateTest {
    @Test
    fun `zoom controls step between bounded preview scales`() {
        assertEquals(1.25f, PreviewZoomState.zoomIn(1f))
        assertEquals(0.75f, PreviewZoomState.zoomOut(1f))
        assertEquals(0.5f, PreviewZoomState.zoomOut(0.5f))
        assertEquals(2.5f, PreviewZoomState.zoomIn(2.5f))
    }

    @Test
    fun `zoom labels are rounded percentages`() {
        assertEquals("100%", PreviewZoomState.label(1f))
        assertEquals("125%", PreviewZoomState.label(1.25f))
        assertEquals("75%", PreviewZoomState.label(0.75f))
    }
}
