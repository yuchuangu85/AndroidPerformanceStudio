package com.androidperformancestudio.presentation

import com.androidperformancestudio.ui.UiLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class FlameGraphTooltipFormattingTest {
    @Test
    fun `percentages use Firefox two significant digit formatting`() {
        assertEquals("0%", firefoxTooltipPercent(0.0))
        assertEquals("0.1%", firefoxTooltipPercent(0.1234))
        assertEquals("1.2%", firefoxTooltipPercent(1.234))
        assertEquals("12%", firefoxTooltipPercent(12.34))
        assertEquals("92%", firefoxTooltipPercent(91.67))
        assertEquals("100%", firefoxTooltipPercent(100.0))
        assertEquals("0%", firefoxTooltipPercent(Double.NaN))
    }

    @Test
    fun `duration uses Firefox milliseconds followed by percentage`() {
        assertEquals("54.0ms (100%)", firefoxTooltipDuration(54_000_000, 100.0))
        assertEquals("1.2ms (12%)", firefoxTooltipDuration(1_234_567, 12.34))
    }

    @Test
    fun `sample counts use Firefox singular and plural units`() {
        assertEquals("1 sample", firefoxTooltipSamples(1, zeroAsDash = false))
        assertEquals("54 samples", firefoxTooltipSamples(54, zeroAsDash = false))
        assertEquals("—", firefoxTooltipSamples(0, zeroAsDash = true))
        assertEquals(
            "54 个样本",
            firefoxTooltipSamples(54, zeroAsDash = false, UiLanguage.SIMPLIFIED_CHINESE),
        )
    }

    @Test
    fun `known Firefox categories follow the selected language`() {
        assertEquals("用户", "User".localizedFirefoxCategory(UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("原生", "Native".localizedFirefoxCategory(UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("JIT", "JIT".localizedFirefoxCategory(UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("渲染", "Rendering".localizedFirefoxCategory(UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("Worker", "Worker".localizedFirefoxCategory(UiLanguage.SIMPLIFIED_CHINESE))
    }
}
