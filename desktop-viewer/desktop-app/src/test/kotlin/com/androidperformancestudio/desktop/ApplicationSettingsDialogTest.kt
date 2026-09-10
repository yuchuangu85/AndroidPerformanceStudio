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
}
