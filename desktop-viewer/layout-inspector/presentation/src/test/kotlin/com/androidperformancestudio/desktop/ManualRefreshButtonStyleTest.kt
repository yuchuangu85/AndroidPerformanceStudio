package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManualRefreshButtonStyleTest {
    @Test
    fun `manual refresh button uses labeled toolbar styling`() {
        assertEquals(56, ManualRefreshButtonStyle.WIDTH_DP)
        assertEquals(22, ManualRefreshButtonStyle.HEIGHT_DP)
        assertEquals(7, ManualRefreshButtonStyle.CORNER_RADIUS_DP)
        assertTrue(ManualRefreshButtonStyle.BORDER_ALPHA < ManualRefreshButtonStyle.BACKGROUND_ALPHA)
        assertTrue(ManualRefreshButtonStyle.BACKGROUND_ALPHA >= 0.86f)
        assertTrue(ManualRefreshButtonStyle.TEXT_ALPHA >= 0.95f)
    }
}
