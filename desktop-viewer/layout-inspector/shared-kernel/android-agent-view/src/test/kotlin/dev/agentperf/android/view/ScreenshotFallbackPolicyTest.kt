package com.androidperformancestudio.android.view

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScreenshotFallbackPolicyTest {
    @Test
    fun `non-successful PixelCopy results fall back to View drawing`() {
        assertFalse(ScreenshotFallbackPolicy.shouldDrawFallback(pixelCopyResult = 0))
        assertTrue(ScreenshotFallbackPolicy.shouldDrawFallback(pixelCopyResult = 3))
    }
}
