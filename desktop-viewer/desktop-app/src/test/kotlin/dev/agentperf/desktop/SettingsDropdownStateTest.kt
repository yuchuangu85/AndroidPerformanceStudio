package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsDropdownStateTest {
    @Test
    fun `language dropdown toggles and collapses after dismissal`() {
        val expanded = SettingsDropdownState().toggle()

        assertTrue(expanded.expanded)
        assertFalse(expanded.dismiss().expanded)
    }
}
