package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposeCompatibilityFallbackTest {
    @Test
    fun `full Compose failure transitions to compatible automatic inspection without changing native capture`() {
        val recovery = composeFallbackRecovery()

        assertTrue(recovery.autoScanEnabled)
        assertFalse(recovery.fullComposeEnabled)
        assertFalse(recovery.recompositionActive)
    }
}
