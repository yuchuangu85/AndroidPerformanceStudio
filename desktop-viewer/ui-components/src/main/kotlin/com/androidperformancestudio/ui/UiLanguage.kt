package com.androidperformancestudio.ui

import java.util.Locale

/** Language selected for application UI resources. */
enum class UiLanguage(
    val locale: Locale,
) {
    ENGLISH(Locale.ENGLISH),
    SIMPLIFIED_CHINESE(Locale.SIMPLIFIED_CHINESE),
    ;

    companion object {
        /** Resolves a supported UI language, falling back to English for unsupported locales. */
        fun fromLocale(locale: Locale): UiLanguage =
            when {
                locale.language.equals(Locale.CHINESE.language, ignoreCase = true) -> SIMPLIFIED_CHINESE
                else -> ENGLISH
            }
    }
}
