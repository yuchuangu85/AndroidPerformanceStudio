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
    fun `canvas preview corner radius is halved as whole dp values`() {
        assertEquals(12, canvasCornerRadiusDp(appOnly = true))
        assertEquals(2, canvasCornerRadiusDp(appOnly = false))
    }
}
