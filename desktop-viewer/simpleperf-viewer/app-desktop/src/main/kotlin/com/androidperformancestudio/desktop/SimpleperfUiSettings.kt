@file:Suppress("MaxLineLength")

package com.androidperformancestudio.desktop

import com.androidperformancestudio.presentation.FlameTooltipMode
import com.androidperformancestudio.presentation.SimpleperfEngine
import com.androidperformancestudio.ui.UiLanguage
import java.util.Locale

enum class SimpleperfThemePreference(
    val storageValue: String,
) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    fun resolveDark(systemDark: Boolean): Boolean =
        when (this) {
            SYSTEM -> systemDark
            LIGHT -> false
            DARK -> true
        }

    companion object {
        fun parse(value: String?): SimpleperfThemePreference =
            entries.firstOrNull {
                it.storageValue == value?.lowercase()
            } ?: SYSTEM
    }
}

enum class SimpleperfLanguagePreference(
    val storageValue: String,
) {
    SYSTEM("system"),
    SIMPLIFIED_CHINESE("simplified_chinese"),
    ENGLISH("english"),
    ;

    fun resolve(locale: Locale): UiLanguage =
        when (this) {
            SYSTEM -> UiLanguage.fromLocale(locale)
            SIMPLIFIED_CHINESE -> UiLanguage.SIMPLIFIED_CHINESE
            ENGLISH -> UiLanguage.ENGLISH
        }

    companion object {
        fun parse(value: String?): SimpleperfLanguagePreference = entries.firstOrNull { it.storageValue == value?.lowercase() } ?: SYSTEM
    }
}

data class SimpleperfUiSettings(
    val theme: SimpleperfThemePreference = SimpleperfThemePreference.SYSTEM,
    val language: SimpleperfLanguagePreference = SimpleperfLanguagePreference.SYSTEM,
    val flameTooltipMode: FlameTooltipMode = FlameTooltipMode.FOLLOW_MOUSE,
    val simpleperfEngine: SimpleperfEngine = SimpleperfEngine.LOCAL,
)
