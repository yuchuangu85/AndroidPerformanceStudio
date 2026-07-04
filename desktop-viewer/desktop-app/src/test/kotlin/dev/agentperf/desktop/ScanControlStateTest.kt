package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScanControlStateTest {
    @Test
    fun `manual refresh is visible only while automatic scanning is off`() {
        assertTrue(ScanControlState(autoScanEnabled = false).showManualRefresh)
        assertFalse(ScanControlState(autoScanEnabled = true).showManualRefresh)
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
