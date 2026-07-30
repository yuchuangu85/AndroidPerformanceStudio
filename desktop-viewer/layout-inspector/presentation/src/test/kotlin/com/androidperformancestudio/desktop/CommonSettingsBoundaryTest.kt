package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommonSettingsBoundaryTest {
    @Test
    fun `viewer accepts common settings without exposing them in feature dialog`() {
        val viewer = Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"),
        )
        val dialog = Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/ThemeSettingsDialog.kt"),
        )

        assertTrue(viewer.contains("commonThemePreference: String? = null"))
        assertTrue(viewer.contains("commonLanguagePreference: String? = null"))
        assertTrue(dialog.contains("viewDisplayOptions: ViewDisplayOptions"))
        assertTrue(dialog.contains("archiveLimits: CaptureArchiveLimits"))
        assertTrue(dialog.contains("canvasBorderColors: CanvasBorderColors"))
        assertTrue(dialog.contains("Res.string.layout_inspector_settings"))
        assertFalse(dialog.contains("selectedThemePreference"))
        assertFalse(dialog.contains("selectedLanguagePreference"))
    }
}
