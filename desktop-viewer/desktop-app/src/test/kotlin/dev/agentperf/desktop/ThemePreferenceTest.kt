package dev.agentperf.desktop

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThemePreferenceTest {
    @Test
    fun `missing and invalid preferences default to system`() {
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStorage(null))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStorage(""))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStorage("unsupported"))
    }

    @Test
    fun `preferences resolve against current system theme`() {
        assertTrue(ThemePreference.SYSTEM.resolveDark(systemDark = true))
        assertFalse(ThemePreference.SYSTEM.resolveDark(systemDark = false))
        assertFalse(ThemePreference.LIGHT.resolveDark(systemDark = true))
        assertTrue(ThemePreference.DARK.resolveDark(systemDark = false))
    }

    @Test
    fun `store persists and restores explicit preference`() {
        var storedValue: String? = null
        val store = ThemePreferenceStore(
            readValue = { storedValue },
            writeValue = { storedValue = it },
        )

        assertEquals(ThemePreference.SYSTEM, store.load())

        store.save(ThemePreference.DARK)

        assertEquals("dark", storedValue)
        assertEquals(ThemePreference.DARK, store.load())
    }

    @Test
    fun `viewer palette follows resolved darkness`() {
        val light = ViewerPalettes.forDark(false)
        val dark = ViewerPalettes.forDark(true)

        assertFalse(light.isDark)
        assertTrue(dark.isDark)
        assertFalse(light.canvasBackground == dark.canvasBackground)
        assertFalse(light.primaryText == dark.primaryText)
    }

    @Test
    fun `visible bounds use the approved light cyan in both themes`() {
        listOf(ViewerPalettes.forDark(false), ViewerPalettes.forDark(true)).forEach { palette ->
            assertEquals(Color(0xFF7DD3FC), palette.visibleViewBounds)
        }
    }

    @Test
    fun `settings present system light and dark choices in order`() {
        val strings = ViewerStrings.forLanguage(ViewerLanguage.SIMPLIFIED_CHINESE)

        assertEquals(
            listOf("跟随系统", "亮色主题", "暗色主题"),
            ThemePreference.entries.map(strings::themePreferenceName),
        )
    }
}
