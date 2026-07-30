package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PanelVisibilityTest {
    @Test
    fun `all inspector panels are visible initially`() {
        assertEquals(
            PanelVisibility(showHierarchy = true, showFindings = true, showDetails = true),
            PanelVisibility(),
        )
    }

    @Test
    fun `each header button toggles only its matching panel`() {
        val initial = PanelVisibility()

        assertEquals(initial.copy(showHierarchy = false), initial.toggleHierarchy())
        assertEquals(initial.copy(showFindings = false), initial.toggleFindings())
        assertEquals(initial.copy(showDetails = false), initial.toggleDetails())
    }
}
