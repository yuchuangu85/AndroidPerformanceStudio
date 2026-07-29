package dev.agentperf.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.androidperformancestudio.ui.viewerColors
import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `both palettes distinguish deep and light property stripes`() {
        val light = viewerColors(false)
        val dark = viewerColors(true)

        assertNotEquals(light.detailRowDeep, light.detailRowLight)
        assertNotEquals(dark.detailRowDeep, dark.detailRowLight)
    }

    @Test
    fun `stripe backgrounds stay visible without excessive contrast`() {
        val light = viewerColors(false)
        val dark = viewerColors(true)
        val lightGap = light.detailRowLight.luminance() - light.detailRowDeep.luminance()
        val darkGap = dark.detailRowLight.luminance() - dark.detailRowDeep.luminance()

        assertTrue(lightGap in 0.10f..0.14f)
        assertTrue(darkGap in 0.01f..0.018f)
    }

    @Test
    fun `section headers stand apart from every property stripe`() {
        listOf(viewerColors(false), viewerColors(true)).forEach { palette ->
            listOf(palette.sectionBackground, palette.riskSectionBackground).forEach { header ->
                assertTrue(colorDistance(header, palette.detailRowDeep) >= 0.12f)
                assertTrue(colorDistance(header, palette.detailRowLight) >= 0.12f)
            }
        }
    }

    private fun colorDistance(first: Color, second: Color): Float =
        sqrt(
            (first.red - second.red) * (first.red - second.red) +
                (first.green - second.green) * (first.green - second.green) +
                (first.blue - second.blue) * (first.blue - second.blue),
        )
}
