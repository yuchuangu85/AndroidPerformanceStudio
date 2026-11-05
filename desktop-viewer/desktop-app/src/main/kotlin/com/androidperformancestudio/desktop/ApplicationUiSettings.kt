package com.androidperformancestudio.desktop

import com.androidperformancestudio.desktop.SimpleperfUiSettings
import com.androidperformancestudio.presentation.FlameTooltipMode
import com.androidperformancestudio.presentation.SimpleperfEngine
import com.androidperformancestudio.ui.UiLanguage
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.androidperformancestudio.ui.ViewerAccentPalette
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

internal enum class ApplicationDisplayScale(
    val storageValue: String,
    val multiplier: Float,
) {
    PERCENT_80("80", 0.80f),
    PERCENT_90("90", 0.90f),
    PERCENT_100("100", 1.00f),
    PERCENT_110("110", 1.10f),
    PERCENT_125("125", 1.25f),
    PERCENT_150("150", 1.50f),
    ;

    companion object {
        fun parse(value: String?): ApplicationDisplayScale =
            entries.firstOrNull { it.storageValue == value } ?: PERCENT_100
    }
}

internal enum class ApplicationThemeColor(
    val storageValue: String,
    val color: Color,
) {
    BANANA_RED("banana_red", ViewerAccentPalette.bananaRed),
    WARM_SUN_ORANGE("warm_sun_orange", ViewerAccentPalette.warmSunOrange),
    CORNFLOWER_BLUE("cornflower_blue", ViewerAccentPalette.cornflowerBlue),
    JADE_GREEN("jade_green", ViewerAccentPalette.jadeGreen),
    MERLOT_PINK("merlot_pink", ViewerAccentPalette.merlotPink),
    AZURE("azure", ViewerAccentPalette.azure),
    LEMON_YELLOW("lemon_yellow", ViewerAccentPalette.lemonYellow),
    ROYAL_PURPLE("royal_purple", ViewerAccentPalette.royalPurple),
    ;

    companion object {
        /** The preset matching [color], or null when the colour is custom. */
        fun presetFor(color: Color): ApplicationThemeColor? = entries.firstOrNull { it.color == color }

        fun presetForStorageValue(value: String?): ApplicationThemeColor? =
            entries.firstOrNull { it.storageValue == value?.trim()?.lowercase() }
    }
}

/** Accent used until the user picks a preset or a custom colour. */
internal val DEFAULT_THEME_COLOR: Color = ApplicationThemeColor.CORNFLOWER_BLUE.color

private val HEX_COLOR_PATTERN = Regex("^#?([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

/**
 * Resolves a persisted accent colour. Preset keys ("royal_purple") stay supported next to custom
 * hex values ("#5A92E5" / "5a92e5"); anything unreadable falls back to [DEFAULT_THEME_COLOR].
 */
internal fun parseThemeColor(value: String?): Color {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return DEFAULT_THEME_COLOR
    ApplicationThemeColor.presetForStorageValue(raw)?.let { return it.color }
    return parseHexColor(raw) ?: DEFAULT_THEME_COLOR
}

/** Presets keep their stable keys; any other colour persists as `#RRGGBB` (or `#AARRGGBB`). */
internal fun themeColorStorageValue(color: Color): String =
    ApplicationThemeColor.presetFor(color)?.storageValue ?: color.toHexString()

/** Parses `#RGB`, `#RRGGBB` or `#AARRGGBB` (the `#` is optional); null when not a colour. */
internal fun parseHexColor(text: String): Color? {
    val hex = HEX_COLOR_PATTERN.matchEntire(text.trim())?.groupValues?.get(1) ?: return null
    val full = if (hex.length == 3) hex.map { digit -> "$digit$digit" }.joinToString("") else hex
    val argb = if (full.length == 6) "FF$full" else full
    return argb.toLongOrNull(16)?.let { Color(it) }
}

/** Formats the colour as `#RRGGBB`, or `#AARRGGBB` when it is not fully opaque. */
internal fun Color.toHexString(): String {
    val argb = toArgb()
    val alpha = (argb ushr 24) and 0xFF
    return if (alpha == 0xFF) {
        "#%06X".format(argb and 0xFFFFFF)
    } else {
        "#%08X".format(argb)
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

internal enum class CpuAnalysisEngine(val storageValue: String) {
    LEGACY("legacy"),
    RUST("rust"),
    ;

    companion object {
        val DEFAULT: CpuAnalysisEngine = RUST

        fun parse(value: String?): CpuAnalysisEngine =
            entries.firstOrNull { it.storageValue.equals(value?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

internal enum class HarImportEngine(val storageValue: String) {
    LEGACY("legacy"),
    RUST("rust"),
    ;

    companion object {
        val DEFAULT: HarImportEngine = RUST

        fun parse(value: String?): HarImportEngine =
            entries.firstOrNull { it.storageValue.equals(value?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

internal data class ApplicationUiSettings(
    val theme: ApplicationThemePreference = ApplicationThemePreference.SYSTEM,
    val displayScale: ApplicationDisplayScale = ApplicationDisplayScale.PERCENT_100,
    val themeColor: Color = DEFAULT_THEME_COLOR,
    val language: ApplicationLanguagePreference = ApplicationLanguagePreference.SYSTEM,
    val androidSdkPath: String? = null,
    /** The navigation rail starts collapsed and remembers the user's choice. */
    val navigationRailCollapsed: Boolean = true,
    val cpuAnalysisEngine: CpuAnalysisEngine = CpuAnalysisEngine.DEFAULT,
    val harImportEngine: HarImportEngine = HarImportEngine.DEFAULT,
    val aiSettingsCategory: AiSettingsCategory = AiSettingsCategory.PROVIDER,
)

internal class ApplicationUiSettingsStore(
    private val readValue: (String) -> String?,
    private val writeValue: (String, String) -> Unit,
    private val flush: () -> Unit = {},
) {
    fun load(): ApplicationUiSettings =
        ApplicationUiSettings(
            theme = ApplicationThemePreference.parse(readValue(THEME_KEY)),
            displayScale = ApplicationDisplayScale.parse(readValue(DISPLAY_SCALE_KEY)),
            themeColor = parseThemeColor(readValue(THEME_COLOR_KEY)),
            language = ApplicationLanguagePreference.parse(readValue(LANGUAGE_KEY)),
            androidSdkPath = readValue(ANDROID_SDK_PATH_KEY)?.trim()?.takeIf(String::isNotEmpty),
            navigationRailCollapsed =
                readValue(NAVIGATION_RAIL_COLLAPSED_KEY)?.toBooleanStrictOrNull() ?: true,
            cpuAnalysisEngine = CpuAnalysisEngine.parse(readValue(CPU_ANALYSIS_ENGINE_KEY)),
            harImportEngine = HarImportEngine.parse(readValue(HAR_IMPORT_ENGINE_KEY)),
            aiSettingsCategory = AiSettingsCategory.parse(readValue(AI_SETTINGS_CATEGORY_KEY)),
        )

    fun save(settings: ApplicationUiSettings): Boolean =
        runCatching {
            writeValue(THEME_KEY, settings.theme.storageValue)
            writeValue(DISPLAY_SCALE_KEY, settings.displayScale.storageValue)
            writeValue(THEME_COLOR_KEY, themeColorStorageValue(settings.themeColor))
            writeValue(LANGUAGE_KEY, settings.language.storageValue)
            writeValue(ANDROID_SDK_PATH_KEY, settings.androidSdkPath.orEmpty())
            writeValue(NAVIGATION_RAIL_COLLAPSED_KEY, settings.navigationRailCollapsed.toString())
            writeValue(CPU_ANALYSIS_ENGINE_KEY, settings.cpuAnalysisEngine.storageValue)
            writeValue(HAR_IMPORT_ENGINE_KEY, settings.harImportEngine.storageValue)
            writeValue(AI_SETTINGS_CATEGORY_KEY, settings.aiSettingsCategory.name)
            flush()
        }.isSuccess

    companion object {
        private const val THEME_KEY = "application.theme"
        private const val DISPLAY_SCALE_KEY = "application.displayScale"
        private const val THEME_COLOR_KEY = "application.themeColor"
        private const val LANGUAGE_KEY = "application.language"
        private const val ANDROID_SDK_PATH_KEY = "application.androidSdkPath"
        private const val NAVIGATION_RAIL_COLLAPSED_KEY = "application.navigationRailCollapsed"
        private const val CPU_ANALYSIS_ENGINE_KEY = "application.cpuAnalysisEngine"
        private const val HAR_IMPORT_ENGINE_KEY = "application.harImportEngine"
        private const val AI_SETTINGS_CATEGORY_KEY = "application.aiSettingsCategory"

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
