package dev.agentperf.desktop

import com.androidperformancestudio.desktop.SimpleperfUiSettings
import com.androidperformancestudio.presentation.FlameTooltipMode
import com.androidperformancestudio.presentation.SimpleperfEngine
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
                language = ApplicationLanguagePreference.SIMPLIFIED_CHINESE,
                androidSdkPath = "D:/Android/Sdk",
            )

        assertTrue(store.save(settings))

        assertEquals("dark", values["application.theme"])
        assertEquals("simplified_chinese", values["application.language"])
        assertEquals("D:/Android/Sdk", values["application.androidSdkPath"])
        assertEquals(settings, store.load())
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
