package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScanControlStateTest {
    @Test
    fun `automatic and manual scan controls are mutually exclusive`() {
        val automatic = ScanControlState(autoScanEnabled = true)
        val manual = ScanControlState(autoScanEnabled = false)

        assertEquals(ScanMode.AUTOMATIC, automatic.selectedMode)
        assertTrue(automatic.autoScanSelected)
        assertFalse(automatic.manualRefreshSelected)
        assertEquals(ScanMode.MANUAL, manual.selectedMode)
        assertFalse(manual.autoScanSelected)
        assertTrue(manual.manualRefreshSelected)
    }

    @Test
    fun `manual refresh is disabled while one shot capture is running`() {
        assertTrue(
            ScanControlState(
                autoScanEnabled = false,
                manualRefreshInProgress = false,
            ).manualRefreshEnabled,
        )
        assertFalse(
            ScanControlState(
                autoScanEnabled = false,
                manualRefreshInProgress = true,
            ).manualRefreshEnabled,
        )
    }
}
