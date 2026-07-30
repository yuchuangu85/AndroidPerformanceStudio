package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetailSectionExpansionStateTest {
    @Test
    fun `sections start expanded and toggle independently`() {
        val initial = DetailSectionExpansionState()
        val risksCollapsed = initial.toggle("RENDER RISKS")

        assertFalse(risksCollapsed.isExpanded("RENDER RISKS"))
        assertTrue(risksCollapsed.isExpanded("LAYOUT"))
        assertTrue(risksCollapsed.toggle("RENDER RISKS").isExpanded("RENDER RISKS"))
    }

    @Test
    fun `section headers match primary panel title height`() {
        assertEquals(PanelHeaderLayout.HEIGHT_DP, DetailSectionHeaderLayout.HEIGHT_DP)
    }
}
