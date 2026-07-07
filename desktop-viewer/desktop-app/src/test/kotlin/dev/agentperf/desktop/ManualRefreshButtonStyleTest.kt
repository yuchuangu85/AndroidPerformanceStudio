package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManualRefreshButtonStyleTest {
    @Test
    fun `manual refresh button uses compact macOS toolbar styling`() {
        assertEquals(28, ManualRefreshButtonStyle.WIDTH_DP)
        assertEquals(20, ManualRefreshButtonStyle.HEIGHT_DP)
        assertEquals(7, ManualRefreshButtonStyle.CORNER_RADIUS_DP)
        assertEquals(13, ManualRefreshButtonStyle.ICON_SIZE_DP)
        assertTrue(ManualRefreshButtonStyle.BORDER_ALPHA < ManualRefreshButtonStyle.BACKGROUND_ALPHA)
    }
}
