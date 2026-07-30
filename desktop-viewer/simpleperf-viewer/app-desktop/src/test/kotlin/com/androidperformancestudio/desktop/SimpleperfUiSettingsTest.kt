package com.androidperformancestudio.desktop

import com.androidperformancestudio.presentation.FlameTooltipMode
import com.androidperformancestudio.ui.UiLanguage
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleperfUiSettingsTest {
    @Test
    fun `Firefox compatible tooltip placement follows the mouse by default`() {
        assertEquals(FlameTooltipMode.FOLLOW_MOUSE, SimpleperfUiSettings().flameTooltipMode)
    }

    @Test
    fun `theme preference resolves system light and dark modes`() {
        assertTrue(SimpleperfThemePreference.SYSTEM.resolveDark(systemDark = true))
        assertFalse(SimpleperfThemePreference.SYSTEM.resolveDark(systemDark = false))
        assertFalse(SimpleperfThemePreference.LIGHT.resolveDark(systemDark = true))
        assertTrue(SimpleperfThemePreference.DARK.resolveDark(systemDark = false))
    }

    @Test
    fun `language preference resolves system Chinese and English`() {
        assertEquals(
            UiLanguage.SIMPLIFIED_CHINESE,
            SimpleperfLanguagePreference.SYSTEM.resolve(Locale.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            UiLanguage.ENGLISH,
            SimpleperfLanguagePreference.SYSTEM.resolve(Locale.ENGLISH),
        )
    }
}
