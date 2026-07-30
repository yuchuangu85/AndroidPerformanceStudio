package com.androidperformancestudio.desktop

import androidx.compose.ui.graphics.Color
import com.androidperformancestudio.ui.viewerColors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HierarchyPaletteTest {
    @Test
    fun `light theme uses the macOS hierarchy text colors`() {
        val palette = viewerColors(false)

        assertEquals(Color(0xFF1D1D1F), palette.rowText)
        assertEquals(Color(0xFF6E6E73), palette.hiddenRowText)
    }
}
