package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import com.androidperformancestudio.ui.ViewerTypography

import org.junit.jupiter.api.Assertions.assertEquals
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
        assertTrue(settingsContent.contains(".background(colors.sectionBackground, shape)"))
        assertTrue(settingsContent.contains("colors.accent.copy(alpha = SETTINGS_SECTION_ACCENT_ALPHA)"))
        assertTrue(settingsContent.contains("color = colors.accent"))
    }

    @Test
    fun `settings typography creates clear hierarchy`() {
        assertTrue(ViewerTypography.pageTitle.fontSize > ViewerTypography.sectionTitle.fontSize)
        assertTrue(ViewerTypography.sectionTitle.fontSize > ViewerTypography.bodyCompact.fontSize)
    }

    @Test
    fun `settings menu groups are separated by visible dividers`() {
        assertEquals(2, SettingsDialogStyle.SECTION_SEPARATOR_COUNT)
        assertEquals(1, SettingsDialogStyle.SEPARATOR_HEIGHT_DP)
        assertTrue(SettingsDialogStyle.SEPARATOR_VERTICAL_PADDING_DP >= 10)
    }
}
