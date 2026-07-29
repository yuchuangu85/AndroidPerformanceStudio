package dev.agentperf.desktop

import com.androidperformancestudio.desktop.SimpleperfUiSettings
import com.androidperformancestudio.presentation.FlameTooltipMode
import com.androidperformancestudio.presentation.SimpleperfEngine
import com.androidperformancestudio.ui.UiLanguage
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

internal enum class ApplicationLanguagePreference(val storageValue: String) {
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
        fun parse(value: String?): ApplicationLanguagePreference =
            entries.firstOrNull { it.storageValue == value?.lowercase() } ?: SYSTEM
    }
}

internal data class ApplicationUiSettings(
    val theme: ApplicationThemePreference = ApplicationThemePreference.SYSTEM,
    val language: ApplicationLanguagePreference = ApplicationLanguagePreference.SYSTEM,
    val androidSdkPath: String? = null,
)

internal class ApplicationUiSettingsStore(
    private val readValue: (String) -> String?,
    private val writeValue: (String, String) -> Unit,
    private val flush: () -> Unit = {},
) {
    fun load(): ApplicationUiSettings =
        ApplicationUiSettings(
            theme = ApplicationThemePreference.parse(readValue(THEME_KEY)),
            language = ApplicationLanguagePreference.parse(readValue(LANGUAGE_KEY)),
            androidSdkPath = readValue(ANDROID_SDK_PATH_KEY)?.trim()?.takeIf(String::isNotEmpty),
        )

    fun save(settings: ApplicationUiSettings): Boolean =
        runCatching {
            writeValue(THEME_KEY, settings.theme.storageValue)
            writeValue(LANGUAGE_KEY, settings.language.storageValue)
            writeValue(ANDROID_SDK_PATH_KEY, settings.androidSdkPath.orEmpty())
            flush()
        }.isSuccess

    companion object {
        private const val THEME_KEY = "application.theme"
        private const val LANGUAGE_KEY = "application.language"
        private const val ANDROID_SDK_PATH_KEY = "application.androidSdkPath"

        fun desktop(): ApplicationUiSettingsStore {
            val preferences =
                runCatching {
                    Preferences.userNodeForPackage(ApplicationUiSettingsStore::class.java)
                }.getOrNull()
            return ApplicationUiSettingsStore(
                readValue = { key -> runCatching { preferences?.get(key, null) }.getOrNull() },
                writeValue = { key, value ->
                    checkNotNull(preferences) { "Application preferences are unavailable" }
                    preferences.put(key, value)
                },
                flush = { checkNotNull(preferences).flush() },
            )
        }
    }
}

internal class SimpleperfPreferencesStore(
    private val readValue: (String) -> String?,
    private val writeValue: (String, String) -> Unit,
    private val flush: () -> Unit = {},
) {
    fun load(): SimpleperfUiSettings =
        SimpleperfUiSettings(
            flameTooltipMode =
                FlameTooltipMode.entries.firstOrNull { it.name == readValue(TOOLTIP_MODE_KEY) }
                    ?: FlameTooltipMode.FOLLOW_MOUSE,
            simpleperfEngine =
                SimpleperfEngine.entries.firstOrNull { it.name == readValue(ENGINE_KEY) }
                    ?: SimpleperfEngine.LOCAL,
        )

    fun save(settings: SimpleperfUiSettings): Boolean =
        runCatching {
            writeValue(TOOLTIP_MODE_KEY, settings.flameTooltipMode.name)
            writeValue(ENGINE_KEY, settings.simpleperfEngine.name)
            flush()
        }.isSuccess

    companion object {
        private const val TOOLTIP_MODE_KEY = "simpleperf.tooltipMode"
        private const val ENGINE_KEY = "simpleperf.engine"

        fun desktop(): SimpleperfPreferencesStore {
            val preferences = runCatching {
                Preferences.userNodeForPackage(SimpleperfPreferencesStore::class.java)
            }.getOrNull()
            return SimpleperfPreferencesStore(
                readValue = { key -> runCatching { preferences?.get(key, null) }.getOrNull() },
                writeValue = { key, value ->
                    checkNotNull(preferences) { "Simpleperf preferences are unavailable" }
                    preferences.put(key, value)
                },
                flush = { checkNotNull(preferences).flush() },
            )
        }
    }
}
