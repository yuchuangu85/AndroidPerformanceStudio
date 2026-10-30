package com.androidperformancestudio.desktop

import com.androidperformancestudio.desktop.SimpleperfUiSettings
import com.androidperformancestudio.memory.model.HprofEngine
import com.androidperformancestudio.presentation.FlameTooltipMode
import com.androidperformancestudio.presentation.SimpleperfEngine
import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnifiedSettingsStoreTest {
    @Test
    fun `application settings save immediately under stable namespaced keys`() {
        val values = mutableMapOf<String, String>()
        val store = ApplicationUiSettingsStore(values::get, values::set)
        val settings =
            ApplicationUiSettings(
                theme = ApplicationThemePreference.DARK,
                displayScale = ApplicationDisplayScale.PERCENT_125,
                themeColor = ApplicationThemeColor.ROYAL_PURPLE.color,
                language = ApplicationLanguagePreference.SIMPLIFIED_CHINESE,
                androidSdkPath = "D:/Android/Sdk",
                hprofEngine = HprofEngine.RUST,
            )

        assertTrue(store.save(settings))

        assertEquals("dark", values["application.theme"])
        assertEquals("125", values["application.displayScale"])
        assertEquals("royal_purple", values["application.themeColor"])
        assertEquals("simplified_chinese", values["application.language"])
        assertEquals("D:/Android/Sdk", values["application.androidSdkPath"])
        assertEquals("true", values["application.navigationRailCollapsed"])
        assertEquals("rust", values["application.hprofEngine"])
        assertEquals(settings, store.load())
    }

    @Test
    fun `hprof analysis defaults to Rust for fresh and existing profiles without a selection`() {
        assertEquals(HprofEngine.RUST, HprofEngine.DEFAULT)
        assertEquals(HprofEngine.RUST, HprofEngine.parse(null))
        assertEquals(HprofEngine.RUST, ApplicationUiSettings().hprofEngine)
        assertEquals(HprofEngine.RUST, ApplicationUiSettingsStore({ null }, { _, _ -> }).load().hprofEngine)

        val values = mutableMapOf("application.theme" to "dark", "application.language" to "english")
        val settings = ApplicationUiSettingsStore(values::get, values::set).load()

        assertEquals(HprofEngine.RUST, settings.hprofEngine)
        assertEquals(ApplicationThemePreference.DARK, settings.theme)
        assertEquals(ApplicationLanguagePreference.ENGLISH, settings.language)
        assertFalse(values.containsKey("application.hprofEngine"))
    }

    @Test
    fun `both hprof engines round trip under the stable key without changing other preferences`() {
        val values = mutableMapOf<String, String>()
        var flushCount = 0
        val store = ApplicationUiSettingsStore(values::get, values::set, flush = { flushCount += 1 })
        val original =
            ApplicationUiSettings(
                theme = ApplicationThemePreference.DARK,
                displayScale = ApplicationDisplayScale.PERCENT_125,
                androidSdkPath = "D:/Android/Sdk",
                navigationRailCollapsed = false,
            )

        listOf(HprofEngine.RUST to "rust", HprofEngine.LEGACY to "legacy").forEach { (engine, storedValue) ->
            val settings = original.copy(hprofEngine = engine)

            assertTrue(store.save(settings))
            assertEquals(storedValue, values["application.hprofEngine"])
            assertEquals(settings, ApplicationUiSettingsStore(values::get, values::set).load())
        }
        assertEquals(2, flushCount)
    }

    @Test
    fun `unrecognized hprof engine preferences default to Rust without overwriting storage`() {
        listOf("", "unknown", "future-engine").forEach { storedValue ->
            val values = mutableMapOf("application.hprofEngine" to storedValue)
            val store = ApplicationUiSettingsStore(values::get, values::set)

            assertEquals(HprofEngine.RUST, store.load().hprofEngine)
            assertEquals(storedValue, values["application.hprofEngine"])
        }
    }

    @Test
    fun `saved Legacy preference remains explicit after the default changes to Rust`() {
        val values = mutableMapOf("application.hprofEngine" to "legacy")
        val store = ApplicationUiSettingsStore(values::get, values::set)

        assertEquals(HprofEngine.LEGACY, store.load().hprofEngine)
        assertEquals("legacy", values["application.hprofEngine"])
    }

    @Test
    fun `hprof engine storage parsing tolerates capitalization and surrounding whitespace`() {
        listOf("RUST", "Rust", " rust ").forEach { storedValue ->
            val values = mutableMapOf("application.hprofEngine" to storedValue)
            assertEquals(HprofEngine.RUST, ApplicationUiSettingsStore(values::get, values::set).load().hprofEngine)
        }
        assertEquals(HprofEngine.LEGACY, HprofEngine.parse("LEGACY"))
    }

    @Test
    fun `the navigation rail is collapsed by default and remembers an expanded choice`() {
        val values = mutableMapOf<String, String>()
        val store = ApplicationUiSettingsStore(values::get, values::set)

        // A fresh profile has no stored value: the rail starts collapsed.
        assertTrue(store.load().navigationRailCollapsed)

        assertTrue(store.save(ApplicationUiSettings(navigationRailCollapsed = false)))
        assertEquals("false", values["application.navigationRailCollapsed"])
        assertFalse(store.load().navigationRailCollapsed)

        assertTrue(store.save(ApplicationUiSettings(navigationRailCollapsed = true)))
        assertTrue(store.load().navigationRailCollapsed)
    }

    @Test
    fun `display scale preferences preserve supported percentages and fall back safely`() {
        assertEquals(ApplicationDisplayScale.PERCENT_125, ApplicationDisplayScale.parse("125"))
        assertEquals(ApplicationDisplayScale.PERCENT_100, ApplicationDisplayScale.parse("unsupported"))
    }

    @Test
    fun `theme color preferences preserve the requested palette and fall back safely`() {
        assertEquals(Color(0xFFD4042D), ApplicationThemeColor.BANANA_RED.color)
        assertEquals(Color(0xFFDB7A0E), ApplicationThemeColor.WARM_SUN_ORANGE.color)
        assertEquals(Color(0xFF5A92E5), ApplicationThemeColor.CORNFLOWER_BLUE.color)
        assertEquals(Color(0xFF5E8034), ApplicationThemeColor.JADE_GREEN.color)
        assertEquals(Color(0xFFEB6D98), ApplicationThemeColor.MERLOT_PINK.color)
        assertEquals(Color(0xFF41B5C2), ApplicationThemeColor.AZURE.color)
        assertEquals(Color(0xFFFACA2E), ApplicationThemeColor.LEMON_YELLOW.color)
        assertEquals(Color(0xFF722169), ApplicationThemeColor.ROYAL_PURPLE.color)
        // Presets keep their legacy keys; custom colours parse from hex and fall back safely.
        assertEquals(ApplicationThemeColor.ROYAL_PURPLE.color, parseThemeColor("royal_purple"))
        assertEquals(DEFAULT_THEME_COLOR, parseThemeColor("missing"))
        assertEquals(Color(0xFF5A92E5), parseThemeColor("#5A92E5"))
        assertEquals(Color(0xFF5A92E5), parseThemeColor("5a92e5"))
        assertEquals(Color(0xFFFF0000), parseThemeColor("#F00"))
        assertEquals(Color(0x80FF0000), parseThemeColor("#80FF0000"))
        assertEquals("royal_purple", themeColorStorageValue(ApplicationThemeColor.ROYAL_PURPLE.color))
        assertEquals("#123456", themeColorStorageValue(Color(0xFF123456)))
    }

    @Test
    fun `custom theme colours round trip and legacy preset keys still load`() {
        val values = mutableMapOf<String, String>()
        val store = ApplicationUiSettingsStore(values::get, values::set)
        val custom = Color(0xFF12AB34)

        assertTrue(store.save(ApplicationUiSettings(themeColor = custom)))
        assertEquals("#12AB34", values["application.themeColor"])
        assertEquals(custom, store.load().themeColor)

        // Older profiles stored the preset key instead of a hex value.
        values["application.themeColor"] = "royal_purple"
        assertEquals(ApplicationThemeColor.ROYAL_PURPLE.color, store.load().themeColor)
    }

    @Test
    fun `simpleperf preferences persist independently from common theme and language`() {
        val values = mutableMapOf<String, String>()
        val store = SimpleperfPreferencesStore(values::get, values::set)
        val settings =
            SimpleperfUiSettings(
                flameTooltipMode = FlameTooltipMode.FIXED,
                simpleperfEngine = SimpleperfEngine.FIREFOX_PROFILER_LOCAL,
            )

        assertTrue(store.save(settings))

        assertEquals("FIXED", values["simpleperf.tooltipMode"])
        assertEquals("FIREFOX_PROFILER_LOCAL", values["simpleperf.engine"])
        assertEquals(settings.flameTooltipMode, store.load().flameTooltipMode)
        assertEquals(settings.simpleperfEngine, store.load().simpleperfEngine)
    }

    @Test
    fun `write failures are reported instead of crashing or silently succeeding`() {
        val applicationStore =
            ApplicationUiSettingsStore(
                readValue = { null },
                writeValue = { _, _ -> error("disk") },
            )
        val simpleperfStore =
            SimpleperfPreferencesStore(
                readValue = { null },
                writeValue = { _, _ -> error("disk") },
            )

        assertFalse(applicationStore.save(ApplicationUiSettings()))
        assertFalse(simpleperfStore.save(SimpleperfUiSettings()))
    }

    @Test
    fun `settings requests retain the requested destination`() {
        val request = SettingsRequest(SettingsPage.SIMPLEPERF, requestId = 7L)

        assertTrue(shouldOpenSettingsForRequest(request))
        assertEquals(SettingsPage.SIMPLEPERF, request.page)
        assertFalse(shouldOpenSettingsForRequest(null))
    }
}
