@file:Suppress("MaxLineLength")

package com.androidperformancestudio.desktop

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

enum class SimpleperfLanguage {
    SIMPLIFIED_CHINESE,
    ENGLISH,
}

enum class SimpleperfLanguagePreference(
    val storageValue: String,
) {
    SYSTEM("system"),
    SIMPLIFIED_CHINESE("simplified_chinese"),
    ENGLISH("english"),
    ;

    fun resolve(locale: Locale): SimpleperfLanguage =
        when (this) {
            SYSTEM ->
                if (locale.language.equals("zh", ignoreCase = true)) {
                    SimpleperfLanguage.SIMPLIFIED_CHINESE
                } else {
                    SimpleperfLanguage.ENGLISH
                }
            SIMPLIFIED_CHINESE -> SimpleperfLanguage.SIMPLIFIED_CHINESE
            ENGLISH -> SimpleperfLanguage.ENGLISH
        }

    companion object {
        fun parse(value: String?): SimpleperfLanguagePreference = entries.firstOrNull { it.storageValue == value?.lowercase() } ?: SYSTEM
    }
}

data class SimpleperfUiSettings(
    val theme: SimpleperfThemePreference = SimpleperfThemePreference.SYSTEM,
    val language: SimpleperfLanguagePreference = SimpleperfLanguagePreference.SYSTEM,
)
