package com.androidperformancestudio.ui

import java.util.Locale

/** Language selected for application UI resources. */
public enum class UiLanguage(
    public val locale: Locale,
) {
    ENGLISH(Locale.ENGLISH),
    SIMPLIFIED_CHINESE(Locale.SIMPLIFIED_CHINESE),
    ;

    public companion object {
        /** Resolves a supported UI language, falling back to English for unsupported locales. */
        public fun fromLocale(locale: Locale): UiLanguage =
            when {
                locale.language.equals(Locale.CHINESE.language, ignoreCase = true) -> SIMPLIFIED_CHINESE
                else -> ENGLISH
            }
    }
}
