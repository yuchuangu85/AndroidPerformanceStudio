package com.androidperformancestudio.desktop

import androidx.compose.ui.graphics.Color
import com.androidperformancestudio.ui.viewerColors
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetailRowStripeTest {
    @Test
    fun `even property rows use the deeper stripe`() {
        assertTrue(DetailRowStripe.usesDeepBackground(0))
        assertFalse(DetailRowStripe.usesDeepBackground(1))
        assertTrue(DetailRowStripe.usesDeepBackground(2))
        assertFalse(DetailRowStripe.usesDeepBackground(3))
        assertTrue(DetailRowStripe.usesDeepBackground(4))
    }

    @Test
    fun `property stripes use the unified macOS palette`() {
        val light = viewerColors(false)
        val dark = viewerColors(true)

        assertEquals(Color(0xFFFAFAFB), light.detailRowDeep)
        assertEquals(Color(0xFFFAFAFB), light.detailRowLight)
        assertEquals(Color(0xFF29292B), dark.detailRowDeep)
        assertEquals(Color(0xFF29292B), dark.detailRowLight)
    }

    @Test
    fun `risk section headers remain distinct from unified property rows`() {
        listOf(viewerColors(false), viewerColors(true)).forEach { palette ->
            assertEquals(palette.detailRowDeep, palette.sectionBackground)
            assertEquals(palette.detailRowLight, palette.sectionBackground)
            assertNotEquals(palette.riskSectionBackground, palette.detailRowDeep)
            assertNotEquals(palette.riskSectionBackground, palette.detailRowLight)
        }
    }
}
