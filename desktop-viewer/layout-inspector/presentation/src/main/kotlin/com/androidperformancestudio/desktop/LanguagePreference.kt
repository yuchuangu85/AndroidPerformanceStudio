package com.androidperformancestudio.desktop

import com.androidperformancestudio.ui.UiLanguage
import java.util.Locale

internal enum class LanguagePreference(
    val storageValue: String,
) {
    SYSTEM("system"),
    SIMPLIFIED_CHINESE("simplified_chinese"),
    ENGLISH("english"),
    ;

    fun resolve(locale: Locale): UiLanguage = when (this) {
        SYSTEM -> UiLanguage.fromLocale(locale)
        SIMPLIFIED_CHINESE -> UiLanguage.SIMPLIFIED_CHINESE
        ENGLISH -> UiLanguage.ENGLISH
    }

    companion object {
        fun fromStorage(value: String?): LanguagePreference =
            entries.firstOrNull { it.storageValue == value?.lowercase() } ?: SYSTEM
    }
}
