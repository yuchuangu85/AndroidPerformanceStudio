package com.androidperformancestudio.ui

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class UiLanguageTest {
    @Test
    fun `supported locales resolve to their UI language`() {
        assertEquals(UiLanguage.ENGLISH, UiLanguage.fromLocale(Locale.ENGLISH))
        assertEquals(UiLanguage.SIMPLIFIED_CHINESE, UiLanguage.fromLocale(Locale.SIMPLIFIED_CHINESE))
        assertEquals(UiLanguage.SIMPLIFIED_CHINESE, UiLanguage.fromLocale(Locale.TRADITIONAL_CHINESE))
    }

    @Test
    fun `unsupported locales fall back to English until resources are added`() {
        assertEquals(UiLanguage.ENGLISH, UiLanguage.fromLocale(Locale.FRENCH))
    }
}
