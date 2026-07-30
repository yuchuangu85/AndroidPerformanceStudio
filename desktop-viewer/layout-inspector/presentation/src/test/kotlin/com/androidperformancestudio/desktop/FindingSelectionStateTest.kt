package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FindingSelectionStateTest {
    @Test
    fun `starts without a selected finding`() {
        val state = FindingSelectionState()

        assertFalse(state.isSelected("finding-a"))
    }

    @Test
    fun `selecting a finding replaces the previous selection`() {
        val state = FindingSelectionState()
            .select("finding-a")
            .select("finding-b")

        assertFalse(state.isSelected("finding-a"))
        assertTrue(state.isSelected("finding-b"))
    }
}
