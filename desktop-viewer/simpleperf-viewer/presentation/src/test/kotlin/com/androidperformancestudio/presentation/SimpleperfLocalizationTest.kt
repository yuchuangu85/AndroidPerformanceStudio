package com.androidperformancestudio.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SimpleperfLocalizationTest {
    @Test
    fun `Chinese localization covers primary workflow labels`() {
        assertEquals(
            "设备与目标",
            translateSimpleperfText("Device & Target", SimpleperfLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "继续采集",
            translateSimpleperfText("Continue to Capture", SimpleperfLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "获取数据",
            translateSimpleperfText("Get data", SimpleperfLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "丢失样本：12",
            translateSimpleperfText("Lost samples: 12", SimpleperfLanguage.SIMPLIFIED_CHINESE),
        )
    }

    @Test
    fun `English localization keeps source text`() {
        assertEquals(
            "Device & Target",
            translateSimpleperfText("Device & Target", SimpleperfLanguage.ENGLISH),
        )
    }

    @Test
    fun `Chinese localization composes flame tooltip labels from structured keys`() {
        assertEquals(
            "类别：Rendering",
            translateSimpleperfText("Category: Rendering", SimpleperfLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "包含 12 · 独占 3",
            translateSimpleperfText("Inclusive 12 · Self 3", SimpleperfLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "样本 4 · 25.00%",
            translateSimpleperfText("Samples 4 · 25.00%", SimpleperfLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "预览范围权重：7",
            translateSimpleperfText("Preview range weight: 7", SimpleperfLanguage.SIMPLIFIED_CHINESE),
        )
    }

    @Test
    fun `workspace pages do not expose language or theme controls`() {
        val homeScreen =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/presentation/HomeScreen.kt"),
            )

        assertFalse(homeScreen.contains("SimpleperfSettingsBar"))
        assertFalse(homeScreen.contains("onThemePreferenceChanged"))
        assertFalse(homeScreen.contains("onLanguagePreferenceChanged"))
    }
}
