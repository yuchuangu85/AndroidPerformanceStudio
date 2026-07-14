package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommonSettingsBoundaryTest {
    @Test
    fun `embedded viewer accepts common settings and hides them from feature dialog`() {
        val viewer = Files.readString(
            Path.of("src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt"),
        )
        val dialog = Files.readString(
            Path.of("src/main/kotlin/dev/agentperf/desktop/ThemeSettingsDialog.kt"),
        )

        assertTrue(viewer.contains("commonThemePreference: String? = null"))
        assertTrue(viewer.contains("commonLanguagePreference: String? = null"))
        assertTrue(viewer.contains("showCommonPreferences = commonSettingsManagedExternally.not()"))
        assertTrue(dialog.contains("showCommonPreferences: Boolean = true"))
        assertTrue(dialog.contains("if (showCommonPreferences)"))
    }
}
