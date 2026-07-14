@file:Suppress("MaxLineLength")

package com.androidperformancestudio.desktop

import java.util.Locale
import java.util.prefs.Preferences

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

class SimpleperfUiSettingsStore(
    private val readValue: (String) -> String?,
    private val writeValue: (String, String) -> Unit,
) {
    fun load(): SimpleperfUiSettings =
        SimpleperfUiSettings(
            theme = SimpleperfThemePreference.parse(readValue(THEME_KEY)),
            language = SimpleperfLanguagePreference.parse(readValue(LANGUAGE_KEY)),
        )

    fun save(settings: SimpleperfUiSettings) {
        writeValue(THEME_KEY, settings.theme.storageValue)
        writeValue(LANGUAGE_KEY, settings.language.storageValue)
    }

    companion object {
        private const val THEME_KEY = "simpleperf.theme"
        private const val LANGUAGE_KEY = "simpleperf.language"

        fun desktop(): SimpleperfUiSettingsStore {
            val preferences =
                runCatching {
                    Preferences.userNodeForPackage(SimpleperfUiSettingsStore::class.java)
                }.getOrNull()
            return SimpleperfUiSettingsStore(
                readValue = { key -> runCatching { preferences?.get(key, null) }.getOrNull() },
                writeValue = { key, value ->
                    runCatching { preferences?.put(key, value) }.getOrNull()
                },
            )
        }
    }
}
