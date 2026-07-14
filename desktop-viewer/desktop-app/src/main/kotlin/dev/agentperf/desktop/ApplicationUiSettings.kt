package dev.agentperf.desktop

import java.util.Locale
import java.util.prefs.Preferences

internal enum class ApplicationThemePreference(val storageValue: String) {
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
        fun parse(value: String?): ApplicationThemePreference =
            entries.firstOrNull { it.storageValue == value?.lowercase() } ?: SYSTEM
    }
}

internal enum class ApplicationLanguage {
    SIMPLIFIED_CHINESE,
    ENGLISH,
}

internal enum class ApplicationLanguagePreference(val storageValue: String) {
    SYSTEM("system"),
    SIMPLIFIED_CHINESE("simplified_chinese"),
    ENGLISH("english"),
    ;

    fun resolve(locale: Locale): ApplicationLanguage =
        when (this) {
            SYSTEM ->
                if (locale.language.equals("zh", ignoreCase = true)) {
                    ApplicationLanguage.SIMPLIFIED_CHINESE
                } else {
                    ApplicationLanguage.ENGLISH
                }
            SIMPLIFIED_CHINESE -> ApplicationLanguage.SIMPLIFIED_CHINESE
            ENGLISH -> ApplicationLanguage.ENGLISH
        }

    companion object {
        fun parse(value: String?): ApplicationLanguagePreference =
            entries.firstOrNull { it.storageValue == value?.lowercase() } ?: SYSTEM
    }
}

internal data class ApplicationUiSettings(
    val theme: ApplicationThemePreference = ApplicationThemePreference.SYSTEM,
    val language: ApplicationLanguagePreference = ApplicationLanguagePreference.SYSTEM,
)

internal class ApplicationUiSettingsStore(
    private val readValue: (String) -> String?,
    private val writeValue: (String, String) -> Unit,
) {
    fun load(): ApplicationUiSettings =
        ApplicationUiSettings(
            theme = ApplicationThemePreference.parse(readValue(THEME_KEY)),
            language = ApplicationLanguagePreference.parse(readValue(LANGUAGE_KEY)),
        )

    fun save(settings: ApplicationUiSettings) {
        writeValue(THEME_KEY, settings.theme.storageValue)
        writeValue(LANGUAGE_KEY, settings.language.storageValue)
    }

    companion object {
        private const val THEME_KEY = "application.theme"
        private const val LANGUAGE_KEY = "application.language"

        fun desktop(): ApplicationUiSettingsStore {
            val preferences =
                runCatching {
                    Preferences.userNodeForPackage(ApplicationUiSettingsStore::class.java)
                }.getOrNull()
            return ApplicationUiSettingsStore(
                readValue = { key -> runCatching { preferences?.get(key, null) }.getOrNull() },
                writeValue = { key, value ->
                    runCatching { preferences?.put(key, value) }.getOrNull()
                },
            )
        }
    }
}
