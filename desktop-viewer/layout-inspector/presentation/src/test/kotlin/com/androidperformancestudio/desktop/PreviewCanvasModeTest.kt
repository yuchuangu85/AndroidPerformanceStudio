package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PreviewCanvasModeTest {
    @Test
    fun `layout source without screenshot renders layout-only canvas instead of waiting`() {
        assertEquals(
            PreviewCanvasMode.LAYOUT_ONLY,
            previewCanvasMode(hasSource = true, hasScreenshot = false),
        )
    }

    @Test
    fun `missing layout source still waits for a frame`() {
        assertEquals(
            PreviewCanvasMode.WAITING,
            previewCanvasMode(hasSource = false, hasScreenshot = false),
        )
    }

    @Test
    fun `layout source with screenshot renders screenshot canvas`() {
        assertEquals(
            PreviewCanvasMode.SCREENSHOT,
            previewCanvasMode(hasSource = true, hasScreenshot = true),
        )
    }
}
