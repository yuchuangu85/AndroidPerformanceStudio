package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DesktopViewerStartupTest {
    @Test
    fun `desktop starts without presenting fixture data as a live device`() {
        val store = createInitialInspectorStore()

        assertNull(store.state.snapshot)
        assertNull(store.state.screenshotPng)
    }

    @Test
    fun `full device canvas uses a small corner radius without changing app only mode`() {
        assertEquals(24, canvasCornerRadiusDp(appOnly = true))
        assertEquals(4, canvasCornerRadiusDp(appOnly = false))
    }
}
