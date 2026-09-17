package com.androidperformancestudio.presentation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.ui.ViewerTheme
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SimpleperfThemeColorPropagationTest {
    @Test
    fun `CPU profile resolves the active application accent for action and activity roles`() =
        runDesktopComposeUiTest {
            val accent = Color(0xFFDB7A0E)
            var resolvedAccent = Color.Unspecified
            var tooltipMeter = Color.Unspecified
            var activity = Color.Unspecified

            setContent {
                ViewerTheme(darkTheme = false, accentColor = accent) {
                    val colors = currentSimpleperfViewerColors(darkTheme = false)
                    resolvedAccent = colors.accent
                    tooltipMeter = colors.flameTooltipMeter
                    activity = colors.firefoxActivity
                }
            }

            assertEquals(accent, resolvedAccent)
            assertEquals(accent, tooltipMeter)
            assertEquals(accent, activity)
        }

    @Test
    fun `CPU profile page palettes resolve from the active theme context`() {
        listOf("DeviceTargetPage.kt", "ReportPage.kt", "CaptureSettingsSection.kt").forEach { fileName ->
            val source = source(fileName)

            assertTrue(source.contains("currentSimpleperfViewerColors(darkTheme)"))
            assertFalse(source.contains("viewerColors(darkTheme)"))
        }

        val tooltip = source("FlameGraphTooltip.kt")
        assertTrue(tooltip.contains("themeColors.flameTooltipMeter"))
        assertTrue(tooltip.contains("themeColors.flameTooltipTrack"))
        assertFalse(tooltip.contains("viewerColors("))
    }
}

private fun source(fileName: String): String =
    Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/presentation/$fileName"))
