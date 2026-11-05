package com.androidperformancestudio.desktop

import androidx.compose.ui.graphics.Color
import com.androidperformancestudio.desktop.SimpleperfUiSettings
import com.androidperformancestudio.presentation.FlameTooltipMode
import com.androidperformancestudio.presentation.SimpleperfEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnifiedSettingsStoreTest {
    @Test
    fun `AI settings category defaults safely without repairing absent or invalid stored values`() {
        assertEquals(AiSettingsCategory.PROVIDER, ApplicationUiSettings().aiSettingsCategory)
        val empty = mutableMapOf<String, String>()
        assertEquals(
            AiSettingsCategory.PROVIDER,
            ApplicationUiSettingsStore(empty::get, empty::set).load().aiSettingsCategory,
        )
        assertTrue(empty.isEmpty())

        listOf("", " ", "unknown", "future-category", "AGENTS", "connection\u0000").forEach { raw ->
            val values = mutableMapOf("application.aiSettingsCategory" to raw, "application.theme" to "dark")
            val original = values.toMap()
            val loaded = ApplicationUiSettingsStore(values::get, values::set).load()

            assertEquals(AiSettingsCategory.PROVIDER, loaded.aiSettingsCategory)
            assertEquals(ApplicationThemePreference.DARK, loaded.theme)
            assertEquals(original, values)
        }
    }

    @Test
    fun `all four AI settings categories round trip through fresh application stores under the exact key`() {
        assertEquals(
            setOf("PROVIDER", "AGENT", "SKILL", "POLICY"),
            AiSettingsCategory.entries.map { it.name }.toSet(),
        )
        AiSettingsCategory.entries.forEach { category ->
            val values = mutableMapOf<String, String>()
            val writes = mutableListOf<String>()
            var flushes = 0
            val store =
                ApplicationUiSettingsStore(
                    readValue = values::get,
                    writeValue = { key, value ->
                        values[key] = value
                        writes += key
                    },
                    flush = { flushes++ },
                )
            val settings = ApplicationUiSettings(aiSettingsCategory = category)

            assertTrue(store.save(settings))
            assertEquals(category.name, values["application.aiSettingsCategory"])
            assertEquals(settings, ApplicationUiSettingsStore(values::get, values::set).load())
            assertEquals(1, flushes)
            assertTrue(writes.all { it.startsWith("application.") })

            values["application.aiSettingsCategory"] = " ${category.name.lowercase()} "
            assertEquals(category, ApplicationUiSettingsStore(values::get, values::set).load().aiSettingsCategory)
        }
    }

    @Test
    fun `legacy connection and provider categories both open the new provider page without rewriting preferences`() {
        listOf("CONNECTION", " connection ", "PROVIDER", " provider ").forEach { legacy ->
            val values = mutableMapOf("application.aiSettingsCategory" to legacy)
            val original = values.toMap()
            val loaded = ApplicationUiSettingsStore(values::get, values::set).load()
            assertEquals(AiSettingsCategory.PROVIDER, loaded.aiSettingsCategory)
            assertEquals(original, values)
        }
    }

    @Test
    fun `saving other application preferences preserves the selected AI category and leaves provider credentials untouched`() {
        val independentValues =
            mapOf(
                "providerId" to "claude",
                "model" to "fixture-selected-model",
                "agentId" to "aps.report",
                "openai:api-key" to "fixture-opaque-credential-reference",
                "claude:auth-token" to "fixture-other-opaque-reference",
                "simpleperf.engine" to "LOCAL",
            )
        val values = independentValues.toMutableMap()
        val store = ApplicationUiSettingsStore(values::get, values::set)
        assertTrue(store.save(store.load().copy(aiSettingsCategory = AiSettingsCategory.SKILL)))

        val reloaded = ApplicationUiSettingsStore(values::get, values::set)
        val changed =
            reloaded.load().copy(
                theme = ApplicationThemePreference.DARK,
                displayScale = ApplicationDisplayScale.PERCENT_125,
                themeColor = ApplicationThemeColor.ROYAL_PURPLE.color,
                language = ApplicationLanguagePreference.SIMPLIFIED_CHINESE,
                androidSdkPath = "D:/Android/Sdk",
                navigationRailCollapsed = false,
            )
        assertTrue(reloaded.save(changed))

        assertEquals(AiSettingsCategory.SKILL.name, values["application.aiSettingsCategory"])
        assertEquals(changed, ApplicationUiSettingsStore(values::get, values::set).load())
        assertEquals(independentValues, values.filterKeys { it in independentValues })
    }

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
            )

        assertTrue(store.save(settings))

        assertEquals("dark", values["application.theme"])
        assertEquals("125", values["application.displayScale"])
        assertEquals("royal_purple", values["application.themeColor"])
        assertEquals("simplified_chinese", values["application.language"])
        assertEquals("D:/Android/Sdk", values["application.androidSdkPath"])
        assertEquals("true", values["application.navigationRailCollapsed"])
        assertEquals(settings, store.load())
    }

    @Test
    fun `new analysis engines default to Rust implementations for fresh profiles`() {
        assertEquals(CpuAnalysisEngine.RUST, CpuAnalysisEngine.DEFAULT)
        assertEquals(HarImportEngine.RUST, HarImportEngine.DEFAULT)
        assertEquals(CpuAnalysisEngine.RUST, ApplicationUiSettings().cpuAnalysisEngine)
        assertEquals(HarImportEngine.RUST, ApplicationUiSettings().harImportEngine)

        val values = mutableMapOf<String, String>()
        val loaded = ApplicationUiSettingsStore(values::get, values::set).load()

        assertEquals(CpuAnalysisEngine.RUST, loaded.cpuAnalysisEngine)
        assertEquals(HarImportEngine.RUST, loaded.harImportEngine)
        assertFalse(values.containsKey("application.cpuAnalysisEngine"))
        assertFalse(values.containsKey("application.harImportEngine"))
    }

    @Test
    fun `new analysis engine preferences parse supported values and reject invalid values without rewriting`() {
        listOf("RUST", "Rust", " rust ").forEach { storedValue ->
            val values = mutableMapOf("application.cpuAnalysisEngine" to storedValue)
            assertEquals(
                CpuAnalysisEngine.RUST,
                ApplicationUiSettingsStore(values::get, values::set).load().cpuAnalysisEngine,
            )
        }
        listOf("RUST", "Rust", " rust ").forEach { storedValue ->
            val values = mutableMapOf("application.harImportEngine" to storedValue)
            assertEquals(
                HarImportEngine.RUST,
                ApplicationUiSettingsStore(values::get, values::set).load().harImportEngine,
            )
        }

        listOf("", "unknown", "future-engine").forEach { storedValue ->
            val values = mutableMapOf(
                "application.cpuAnalysisEngine" to storedValue,
                "application.harImportEngine" to storedValue,
            )
            val original = values.toMap()
            val loaded = ApplicationUiSettingsStore(values::get, values::set).load()

            assertEquals(CpuAnalysisEngine.RUST, loaded.cpuAnalysisEngine)
            assertEquals(HarImportEngine.RUST, loaded.harImportEngine)
            assertEquals(original, values)
        }
    }

    @Test
    fun `new analysis engine preferences round trip under independent application keys`() {
        val values = mutableMapOf<String, String>()
        val store = ApplicationUiSettingsStore(values::get, values::set)
        val settings =
            ApplicationUiSettings(
                cpuAnalysisEngine = CpuAnalysisEngine.LEGACY,
                harImportEngine = HarImportEngine.LEGACY,
            )

        assertTrue(store.save(settings))

        assertEquals("legacy", values["application.cpuAnalysisEngine"])
        assertEquals("legacy", values["application.harImportEngine"])
        assertEquals(settings, ApplicationUiSettingsStore(values::get, values::set).load())
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
