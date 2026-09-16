package com.androidperformancestudio.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnifiedUiComponentsSourceTest {
    private val sourceRoot = Path.of("src/main/kotlin/com/androidperformancestudio/ui")

    @Test
    fun `theme exposes one color model with one shared light and dark palette`() {
        val source = Files.readString(sourceRoot.resolve("ViewerTheme.kt"))

        assertTrue(source.contains("public data class ViewerColors("))
        assertTrue(source.contains("public fun viewerColors("))
        assertFalse(source.contains("ViewerThemeVariant"))
        assertFalse(source.contains("macOsLight"))
        assertFalse(source.contains("macOsDark"))
        assertFalse(Files.exists(sourceRoot.resolve("MacOsDeviceTargetStyle.kt")))
    }

    @Test
    fun `shared custom buttons derive visible action styling from the active accent`() {
        val textButton = Files.readString(sourceRoot.resolve("button/MacOSTextButton.kt"))
        val homeButton = Files.readString(sourceRoot.resolve("button/HomeButton.kt"))
        val settingsButton = Files.readString(sourceRoot.resolve("button/SettingButton.kt"))
        val choiceChip = Files.readString(sourceRoot.resolve("radiobutton/MacOSChoice.kt"))
        val selector = Files.readString(sourceRoot.resolve("DropdownSelector.kt"))
        val controls = Files.readString(sourceRoot.resolve("ProfilerMacOsControls.kt"))

        assertTrue(textButton.contains("val content = if (primary) style.accentText else style.accent"))
        assertTrue(textButton.contains("style.accent,"))
        assertTrue(homeButton.contains("val outline = accent"))
        assertTrue(settingsButton.contains("colors?.accent ?: LocalViewerColors.current.accent"))
        assertTrue(choiceChip.contains("val content = if (selected) style.accentText else style.accent"))
        assertTrue(selector.contains(".border(1.dp, colors.accent, shape)"))
        assertTrue(selector.contains("tint = colors.accent"))
        assertTrue(controls.contains(".border(1.dp, MaterialTheme.colorScheme.primary, shape)"))
        assertTrue(controls.contains("style.accent,"))
    }

    @Test
    fun `nested viewer themes and outlined fields inherit the active accent`() {
        val source = Files.readString(sourceRoot.resolve("ViewerTheme.kt"))

        assertTrue(source.contains("accentColor: Color = LocalViewerColors.current.accent"))
        assertTrue(source.contains("public fun viewerOutlinedTextFieldColors(): TextFieldColors"))
        assertTrue(source.contains("focusedBorderColor = accent"))
        assertTrue(source.contains("unfocusedBorderColor = accent"))
    }

    @Test
    fun `shared theme uses the macOS light and dark palettes`() {
        val light = viewerColors(darkTheme = false)
        val dark = viewerColors(darkTheme = true)

        assertEquals(Color(0xFFECECEC), light.workspace)
        assertEquals(Color(0xFFFAFAFB), light.toolbar)
        assertEquals(Color(0xFFB8B8BD), light.strongBorder)
        assertEquals(Color(0xFF1E1E1E), dark.workspace)
        assertEquals(Color(0xFF29292B), dark.toolbar)
        assertEquals(Color(0xFF636366), dark.strongBorder)
    }

    @Test
    fun `display scale changes density for all layout and text units`() {
        val scaled = scaledViewerDensity(Density(density = 2f, fontScale = 1.2f), displayScale = 1.25f)

        assertEquals(2.5f, scaled.density)
        assertEquals(1.2f, scaled.fontScale)
    }

    @Test
    fun `theme accepts a caller supplied accent and applies it to semantic colors`() {
        val accent = Color(0xFFFACA2E)
        val colors = viewerColors(darkTheme = false, accentColor = accent)
        val scheme = viewerMaterialColorScheme(darkTheme = false, accentColor = accent)

        assertEquals(accent, colors.accent)
        assertEquals(accent, colors.visibleViewBounds)
        assertEquals(accent, scheme.primary)
    }

    @Test
    fun `switch selection uses the shared theme accent rather than a fixed green`() {
        val source = Files.readString(sourceRoot.resolve("switch/MacOSSwitch.kt"))

        assertTrue(source.contains("val selectedTrackColor = checkedTrackColor ?: colors.accent"))
        assertTrue(source.contains("val unselectedTrackColor = uncheckedTrackColor ?: colors.switchTrackOff"))
        assertFalse(source.contains("Color(0xFF34C759)"))
    }

    @Test
    fun `home navigation has one public control`() {
        val source = Files.readString(sourceRoot.resolve("button/HomeButton.kt"))

        assertTrue(source.contains("fun HomeButton("))
        assertTrue(source.contains("colors?.accent ?: LocalViewerColors.current.accent"))
        assertFalse(source.contains("fun MacOSHomeButton("))
    }

    @Test
    fun `settings action has one public control`() {
        val source = Files.readString(sourceRoot.resolve("button/SettingButton.kt"))

        assertTrue(source.contains("public fun SettingsButton("))
        assertTrue(source.contains("contentDescription: String? = null"))
        assertTrue(source.contains("enabled: Boolean = true"))
        assertTrue(source.contains("colors?.accent ?: LocalViewerColors.current.accent"))
        assertFalse(Files.exists(sourceRoot.resolve("MacOsSettingsButton.kt")))
    }
}
