package dev.agentperf.desktop

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

        assertEquals(Color.White, light.detailRowDeep)
        assertEquals(Color.White, light.detailRowLight)
        assertEquals(Color(0xFF1C1C1E), dark.detailRowDeep)
        assertEquals(Color(0xFF2C2C2E), dark.detailRowLight)
    }

    @Test
    fun `section headers remain distinct from property rows`() {
        listOf(viewerColors(false), viewerColors(true)).forEach { palette ->
            listOf(palette.sectionBackground, palette.riskSectionBackground).forEach { header ->
                assertNotEquals(header, palette.detailRowDeep)
                assertNotEquals(header, palette.detailRowLight)
            }
        }
    }
}
