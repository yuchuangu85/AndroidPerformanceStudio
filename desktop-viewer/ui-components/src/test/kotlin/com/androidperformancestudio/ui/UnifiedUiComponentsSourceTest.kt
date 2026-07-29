package com.androidperformancestudio.ui

import androidx.compose.ui.graphics.Color
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UnifiedUiComponentsSourceTest {
    private val sourceRoot = Path.of("src/main/kotlin/com/androidperformancestudio/ui")

    @Test
    fun `theme exposes one color model with standard and macOS variants`() {
        val source = Files.readString(sourceRoot.resolve("ViewerTheme.kt"))

        assertTrue(source.contains("public data class ViewerColors("))
        assertTrue(source.contains("public enum class ViewerThemeVariant"))
        assertTrue(source.contains("MAC_OS"))
        assertTrue(source.contains("public fun viewerColors("))
        assertFalse(Files.exists(sourceRoot.resolve("MacOsDeviceTargetStyle.kt")))
    }

    @Test
    fun `macOS theme variant preserves the former light and dark palettes`() {
        val light = viewerColors(darkTheme = false, variant = ViewerThemeVariant.MAC_OS)
        val dark = viewerColors(darkTheme = true, variant = ViewerThemeVariant.MAC_OS)

        assertEquals(Color(0xFFF5F5F7), light.workspace)
        assertEquals(Color(0xFFFAFAFB), light.toolbar)
        assertEquals(Color(0xFFB8B8BD), light.strongBorder)
        assertEquals(Color(0xFF1E1E20), dark.workspace)
        assertEquals(Color(0xFF29292B), dark.toolbar)
        assertEquals(Color(0xFF636366), dark.strongBorder)
        assertNotEquals(viewerColors(darkTheme = false), light)
    }

    @Test
    fun `home navigation has one public control`() {
        val source = Files.readString(sourceRoot.resolve("ProfilerHomeButton.kt"))

        assertTrue(source.contains("public fun ProfilerHomeButton("))
        assertFalse(source.contains("fun MacOSHomeButton("))
    }

    @Test
    fun `settings action has one public control`() {
        val source = Files.readString(sourceRoot.resolve("SettingButton.kt"))

        assertTrue(source.contains("public fun SettingsButton("))
        assertTrue(source.contains("contentDescription: String? = null"))
        assertTrue(source.contains("enabled: Boolean = true"))
        assertFalse(Files.exists(sourceRoot.resolve("MacOsSettingsButton.kt")))
    }
}
