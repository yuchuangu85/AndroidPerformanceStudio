package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import com.androidperformancestudio.ui.ViewerTypography

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsDialogStyleTest {
    private val settingsContent =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorSettingsContent.kt"),
        )

    @Test
    fun `layout inspector settings sections use active viewer theme tokens`() {
        assertTrue(settingsContent.contains("val colors = LocalViewerColors.current"))
        assertTrue(settingsContent.contains("StudioCard("))
        assertTrue(settingsContent.contains("containerColor = colors.sectionBackground"))
        assertTrue(settingsContent.contains("colors.accent.copy(alpha = SETTINGS_SECTION_ACCENT_ALPHA)"))
        assertTrue(settingsContent.contains("contentSpacing = 7.dp"))
        assertTrue(settingsContent.contains("verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)"))
        assertFalse(settingsContent.contains("verticalScroll(rememberScrollState()).padding(end = 8.dp)"))
        val section = settingsContent.substringAfter("private fun SettingsSection(").substringBefore("private fun SettingsToggleRow(")
        assertFalse(section.contains(".background("))
        assertFalse(section.contains(".border("))
        assertFalse(section.contains(".padding("))
        assertTrue(settingsContent.contains("color = colors.accent"))
        assertTrue(settingsContent.contains("viewOptions.showSystemComposables"))
        assertTrue(settingsContent.contains("Res.string.system_composables_hint"))

        val legacyDialog =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/ThemeSettingsDialog.kt"),
            )
        assertTrue(legacyDialog.contains("viewDisplayOptions.showSystemComposables"))
        assertTrue(legacyDialog.contains("Res.string.system_composables_hint"))
    }

    @Test
    fun `both settings entry points keep the compiler report guide last`() {
        val legacyDialog =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/ThemeSettingsDialog.kt"),
            )
        listOf(settingsContent, legacyDialog).forEach { source ->
            assertTrue(source.contains("Res.string.compose_reports_title"))
            assertTrue(source.contains("ComposeCompilerReportGuide(language)"))
            assertTrue(source.indexOf("Res.string.capture_archive") < source.indexOf("Res.string.canvas_border_colors"))
            assertTrue(source.indexOf("Res.string.canvas_border_colors") < source.indexOf("Res.string.compose_reports_title"))
            assertTrue(source.indexOf("CanvasBorderColors().selected") < source.indexOf("ComposeCompilerReportGuide(language)"))
        }
    }

    @Test
    fun `settings typography uses the shared three level hierarchy`() {
        assertEquals(ViewerTypography.pageTitle.fontSize, ViewerTypography.sectionTitle.fontSize)
        assertTrue(ViewerTypography.sectionTitle.fontSize > ViewerTypography.bodyCompact.fontSize)
        assertTrue(ViewerTypography.bodyCompact.fontSize > ViewerTypography.secondary.fontSize)
    }

    @Test
    fun `settings menu groups are separated by visible dividers`() {
        assertEquals(3, SettingsDialogStyle.SECTION_SEPARATOR_COUNT)
        assertEquals(1, SettingsDialogStyle.SEPARATOR_HEIGHT_DP)
        assertTrue(SettingsDialogStyle.SEPARATOR_VERTICAL_PADDING_DP >= 10)
    }
}
