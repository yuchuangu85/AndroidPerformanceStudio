package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsRequestTest {
    @Test
    fun `positive native request opens the shared settings dialog`() {
        assertTrue(shouldOpenSettingsForRequest(1L))
        assertFalse(shouldOpenSettingsForRequest(0L))
    }
}
