package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApplicationSettingsDialogTest {
    private val unifiedDialog =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppSettingsPage.kt"),
        )

    @Test
    fun `unified settings shows an About page with the runtime version`() {
        assertTrue(unifiedDialog.contains("SettingsPage.ABOUT"))
        assertTrue(unifiedDialog.contains("AboutSettingsContent("))
        assertTrue(unifiedDialog.contains("ApplicationVersion.current()"))
    }

    @Test
    fun `unified settings owns AI configuration immediately above About`() {
        assertTrue(SettingsPage.entries.indexOf(SettingsPage.AI) < SettingsPage.entries.indexOf(SettingsPage.ABOUT))
        assertTrue(unifiedDialog.contains("SettingsPage.AI ->"))
        assertTrue(unifiedDialog.contains("AiSettingsContent("))
        assertTrue(unifiedDialog.contains("runtime.providerHasCredential(connection)"))
        assertTrue(unifiedDialog.contains("runtime.saveAndApplyProviderConnection("))
        assertTrue(unifiedDialog.contains("settingsStore.update { it.copy(providerId = connection.providerId"))
        assertTrue(unifiedDialog.contains("Res.string.source_ai_key_required"))
        assertTrue(
            unifiedDialog.indexOf("label = SettingsPage.AI.label(language)") <
                unifiedDialog.indexOf("label = SettingsPage.ABOUT.label(language)"),
        )

        val sourceWorkspaces =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/SourceWorkspacesPage.kt"),
            )
        assertTrue(sourceWorkspaces.contains("onClick = onOpenAiSettings"))
        assertFalse(sourceWorkspaces.contains("AiCredentialDialog"))

        val mainPage =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppMainPage.kt"),
            )
        assertTrue(mainPage.contains("onOpenAiSettings = { openSettings(SettingsPage.AI) }"))
    }
    @Test
    fun `general settings applies the selected display percentage through application settings`() {
        assertTrue(unifiedDialog.contains("Res.string.display_size"))
        assertTrue(unifiedDialog.contains("ApplicationDisplayScale.entries"))
        assertTrue(unifiedDialog.contains("onSettingsChanged(settings.copy(displayScale = it))"))
    }

    @Test
    fun `general settings keeps theme color directly below theme selection`() {
        // The dialog only edits the setting; the preference key stays in the store.
        assertFalse(unifiedDialog.contains("application.themeColor"))
        assertTrue(unifiedDialog.contains("ThemeColorSettingsContent("))
        assertTrue(unifiedDialog.contains("ApplicationThemeColor.entries"))
        assertTrue(unifiedDialog.contains("selected = preset.color == selectedColor"))
        assertTrue(unifiedDialog.contains("onClick = { onSelect(preset.color) }"))
        assertTrue(unifiedDialog.contains("onSettingsChanged(settings.copy(themeColor = color))"))
        assertTrue(
            unifiedDialog.indexOf("label = localizedStringResource(Res.string.theme, language)") <
                unifiedDialog.indexOf("ThemeColorSettingsContent("),
        )
    }

    @Test
    fun `theme colour swatches stay compact`() {
        assertTrue(unifiedDialog.contains("THEME_SWATCH_COLUMNS = 2"))
        assertTrue(unifiedDialog.contains("height(38.dp)"))
        assertTrue(unifiedDialog.contains("size(18.dp)"))
        assertTrue(unifiedDialog.contains("themeColorPreferenceLabel(preset, language)"))
    }

    @Test
    fun `theme colour offers a picker, a hex field and a default reset`() {
        assertTrue(unifiedDialog.contains("ThemeColorPicker("))
        assertTrue(unifiedDialog.contains("parseHexColor(next)?.let(onColorChange)"))
        assertTrue(unifiedDialog.contains("Res.string.theme_color_custom"))
        assertTrue(unifiedDialog.contains("Res.string.theme_color_default"))
        assertTrue(unifiedDialog.contains("settings.copy(themeColor = DEFAULT_THEME_COLOR)"))
        // Presets sit in two columns beside the picker; the default reset is a real button.
        assertTrue(unifiedDialog.contains("ThemeColorPresets("))
        assertTrue(unifiedDialog.contains("CustomThemeColorColumn("))
        assertTrue(unifiedDialog.contains("ApplicationThemeColor.entries.chunked(THEME_SWATCH_COLUMNS)"))
        assertTrue(unifiedDialog.contains("Button(onClick = onResetDefault)"))
        // Each colour bar is sized from half of the free width, and the picker follows the bars
        // directly: only the leftover width trails it.
        assertTrue(unifiedDialog.contains("THEME_PRESET_WIDTH_FRACTION = 0.5f"))
        assertTrue(
            unifiedDialog.indexOf("CustomThemeColorColumn(") <
                unifiedDialog.indexOf("Modifier.weight(1f - THEME_PRESET_WIDTH_FRACTION)"),
        )
    }

    @Test
    fun `custom colour picker exposes a saturation value plane and a hue rail`() {
        val picker =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/ThemeColorPicker.kt"),
            )

        assertTrue(picker.contains("internal fun ThemeColorPicker("))
        assertTrue(picker.contains("listOf(Color.White, Color.hsv("))
        assertTrue(picker.contains("Brush.verticalGradient(listOf(Color.Transparent, Color.Black))"))
        assertTrue(picker.contains("Brush.horizontalGradient(HUE_GRADIENT)"))
        assertTrue(picker.contains("detectDragGestures("))
    }

    @Test
    fun `settings sidebar shows an icon for every page`() {
        val drawables = Path.of("../ui-components/src/main/composeResources/drawable")
        val expected =
            listOf(
                "ic_settings_general",
                "ic_settings_configuration",
                "ic_nav_layout_inspector",
                "ic_nav_cpu_profiler",
                "ic_settings_ai",
                "ic_settings_about",
            )

        expected.forEach { name ->
            assertTrue(Files.exists(drawables.resolve("$name.svg")), "missing settings icon: $name")
            assertTrue(
                Files.readString(drawables.resolve("$name.svg")).startsWith("<svg"),
                "settings icon must be an SVG vector: $name",
            )
        }
        assertTrue(unifiedDialog.contains("private val SettingsPage.settingsIconResource"))
        assertTrue(unifiedDialog.contains("leadingIcon = page.settingsIconResource"))
        assertTrue(unifiedDialog.contains("leadingIcon = SettingsPage.ABOUT.settingsIconResource"))
        SettingsPage.entries.forEach { page ->
            assertTrue(
                unifiedDialog.contains("SettingsPage.${page.name} -> UiComponentsRes.drawable."),
                "no sidebar icon mapped for $page",
            )
        }
    }

    @Test
    fun `settings page headings use the active viewer accent`() {
        assertTrue(unifiedDialog.contains("private fun SettingsPageTitle("))
        assertTrue(unifiedDialog.contains("color = LocalViewerColors.current.accent"))
        assertTrue(Regex("SettingsPageTitle\\(").findAll(unifiedDialog).count() >= 7)
    }

    @Test
    fun `settings sub-navigation rows stay compact`() {
        // Rows are not painted across the whole sidebar while unselected, and the leading icon
        // uses one shared square slot rather than a bare spacer.
        assertTrue(unifiedDialog.contains("UNIFIED_SETTINGS_SIDEBAR_WIDTH_DP = 176"))
        assertTrue(unifiedDialog.contains("else Color.Transparent"))
        assertFalse(unifiedDialog.contains("Spacer(Modifier.width(16.dp))"))
        assertFalse(unifiedDialog.contains("settingsLabel"))
    }

    @Test
    fun `general settings can show or hide the sidebar titles`() {
        // Checked expands the rail (labels visible); unchecked is the collapsed icon-only rail.
        assertTrue(unifiedDialog.contains("Res.string.show_sidebar_titles"))
        assertTrue(unifiedDialog.contains("checked = !settings.navigationRailCollapsed"))
        assertTrue(unifiedDialog.contains("navigationRailCollapsed = !showSidebarTitles"))
        assertTrue(unifiedDialog.contains("MacOSSwitch(checked = checked, onCheckedChange = onCheckedChange)"))

        // The label is styled as a section label: accent colour, flush with the "Theme colour"
        // heading, while the switch keeps the row's horizontal inset.
        val toggleRow =
            unifiedDialog
                .substringAfter("private fun SettingsToggleRow(")
                .substringBefore("private fun GeneralSettingsContent(")
        assertTrue(toggleRow.contains("color = colors.accent"))
        assertTrue(toggleRow.contains("padding(end = 8.dp)"))
        assertFalse(toggleRow.contains("padding(horizontal = 8.dp)"))
    }

    @Test
    fun `configuration page owns the Android SDK path setting`() {
        assertTrue(SettingsPage.entries.indexOf(SettingsPage.CONFIGURATION) > SettingsPage.entries.indexOf(SettingsPage.GENERAL))
        assertTrue(unifiedDialog.contains("SettingsPage.CONFIGURATION ->"))
        assertTrue(unifiedDialog.contains("ConfigurationSettingsContent("))
        assertTrue(unifiedDialog.contains("AndroidSdkPathSetting("))
        assertTrue(
            unifiedDialog.indexOf("SettingsPage.CONFIGURATION ->") <
                unifiedDialog.indexOf("AndroidSdkPathSetting("),
        )
    }

    @Test
    fun `trace analyzer receives the configured Android SDK path`() {
        val mainPage =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppMainPage.kt"),
            )
        assertTrue(mainPage.contains("androidSdkPath = applicationSettings.androidSdkPath?.let"))
        assertTrue(mainPage.contains("PerfettoMainPage("))
    }

    @Test
    fun `settings window applies the selected display scale in its own composition`() {
        assertTrue(unifiedDialog.contains("viewerThemeContext: ViewerThemeContext"))
        assertTrue(unifiedDialog.contains("ProvideViewerThemeContext(viewerThemeContext)"))
        assertFalse(unifiedDialog.contains("ViewerTheme("))
        assertTrue(unifiedDialog.contains("viewerOutlinedTextFieldColors()"))

        val mainPage =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppMainPage.kt"),
            )
        assertTrue(mainPage.contains("displayScale = applicationSettings.displayScale.multiplier"))
        assertTrue(mainPage.contains("rememberViewerThemeContext()"))
        assertTrue(mainPage.contains("viewerThemeContext = viewerThemeContext"))
    }

}
