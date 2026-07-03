package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DesktopViewerStartupTest {
    @Test
    fun `desktop starts without presenting fixture data as a live device`() {
        val store = createInitialInspectorStore()

        assertNull(store.state.snapshot)
        assertNull(store.state.screenshotPng)
    }
}
