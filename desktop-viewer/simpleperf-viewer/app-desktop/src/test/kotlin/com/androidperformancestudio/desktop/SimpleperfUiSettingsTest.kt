package com.androidperformancestudio.desktop

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleperfUiSettingsTest {
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
            SimpleperfLanguage.SIMPLIFIED_CHINESE,
            SimpleperfLanguagePreference.SYSTEM.resolve(Locale.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            SimpleperfLanguage.ENGLISH,
            SimpleperfLanguagePreference.SYSTEM.resolve(Locale.ENGLISH),
        )
    }

    @Test
    fun `settings store persists both selections`() {
        val values = mutableMapOf<String, String>()
        val store =
            SimpleperfUiSettingsStore(
                readValue = values::get,
                writeValue = values::put,
            )
        val settings =
            SimpleperfUiSettings(
                theme = SimpleperfThemePreference.DARK,
                language = SimpleperfLanguagePreference.SIMPLIFIED_CHINESE,
            )

        store.save(settings)

        assertEquals(settings, store.load())
    }
}
