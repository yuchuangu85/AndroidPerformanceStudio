package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApplicationSettingsDialogTest {
    @Test
    fun `application settings dialog owns language and theme controls`() {
        val dialog =
            Files.readString(
                Path.of("src/main/kotlin/dev/agentperf/desktop/ApplicationSettingsDialog.kt"),
            )

        assertTrue(dialog.contains("ApplicationLanguagePreference.entries"))
        assertTrue(dialog.contains("ApplicationThemePreference.entries"))
        assertTrue(dialog.contains("onSettingsChanged(settings.copy(language = it))"))
        assertTrue(dialog.contains("onSettingsChanged(settings.copy(theme = it))"))
    }

    @Test
    fun `dropdown menu stays within application settings content width`() {
        val dialog =
            Files.readString(
                Path.of("src/main/kotlin/dev/agentperf/desktop/ApplicationSettingsDialog.kt"),
            )

        assertTrue(
            ApplicationSettingsDialogStyle.DROPDOWN_WIDTH_DP <=
                ApplicationSettingsDialogStyle.CONTENT_WIDTH_DP,
        )
        assertTrue(
            dialog.contains(
                "modifier = Modifier.width(ApplicationSettingsDialogStyle.DROPDOWN_WIDTH_DP.dp)",
            ),
        )
    }
}
