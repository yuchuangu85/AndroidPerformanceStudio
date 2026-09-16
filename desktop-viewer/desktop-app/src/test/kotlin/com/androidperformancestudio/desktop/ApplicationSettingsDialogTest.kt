package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApplicationSettingsDialogTest {
    private val unifiedDialog =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppSettingsDialog.kt"),
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
        assertTrue(unifiedDialog.contains("!runtime.credential(OPENAI_API_KEY).isNullOrBlank()"))
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
        assertFalse(unifiedDialog.contains("THEME_COLOR"))
        assertTrue(unifiedDialog.contains("ThemeColorSettingsContent("))
        assertTrue(unifiedDialog.contains("ApplicationThemeColor.entries"))
        assertTrue(unifiedDialog.contains("onSettingsChanged(settings.copy(themeColor = color))"))
        assertTrue(
            unifiedDialog.indexOf("label = localizedStringResource(Res.string.theme, language)") <
                unifiedDialog.indexOf("ThemeColorSettingsContent("),
        )
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
    fun `settings window applies the selected display scale in its own composition`() {
        assertTrue(unifiedDialog.contains("displayScale: Float"))
        assertTrue(unifiedDialog.contains("ViewerTheme("))
        assertTrue(unifiedDialog.contains("displayScale = displayScale"))
        assertTrue(unifiedDialog.contains("accentColor = applicationSettings.themeColor.color"))
        assertTrue(unifiedDialog.contains("viewerOutlinedTextFieldColors()"))

        val mainPage =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppMainPage.kt"),
            )
        assertTrue(mainPage.contains("displayScale = applicationSettings.displayScale.multiplier"))
    }

}
