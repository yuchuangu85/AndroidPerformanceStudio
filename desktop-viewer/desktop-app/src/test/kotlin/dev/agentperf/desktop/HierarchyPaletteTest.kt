package dev.agentperf.desktop

import androidx.compose.ui.graphics.luminance
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HierarchyPaletteTest {
    @Test
    fun `light theme clearly distinguishes hidden hierarchy rows`() {
        val palette = ViewerPalettes.forDark(false)

        assertTrue(
            palette.hiddenRowText.luminance() - palette.rowText.luminance() >= 0.30f,
        )
    }
}
