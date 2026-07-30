package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PanelHeaderLayoutTest {
    @Test
    fun `panel title bars are one third shorter than the previous height`() {
        assertEquals(29f, PanelHeaderLayout.HEIGHT_DP)
    }
}
