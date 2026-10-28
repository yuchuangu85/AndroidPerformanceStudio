package com.androidperformancestudio.desktop

import com.androidperformancestudio.ui.UiLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ApplicationLanguageLabelsTest {
    @Test
    fun `language options use their own language names regardless of the active interface language`() {
        UiLanguage.entries.forEach { language ->
            assertEquals(
                "English",
                languagePreferenceLabel(ApplicationLanguagePreference.ENGLISH, language),
            )
            assertEquals(
                "\u7b80\u4f53\u4e2d\u6587",
                languagePreferenceLabel(ApplicationLanguagePreference.SIMPLIFIED_CHINESE, language),
            )
        }
    }

    @Test
    fun `system option continues to follow the active interface language`() {
        assertEquals(
            "System",
            languagePreferenceLabel(ApplicationLanguagePreference.SYSTEM, UiLanguage.ENGLISH),
        )
        assertEquals(
            "\u8ddf\u968f\u7cfb\u7edf",
            languagePreferenceLabel(ApplicationLanguagePreference.SYSTEM, UiLanguage.SIMPLIFIED_CHINESE),
        )
    }
}
