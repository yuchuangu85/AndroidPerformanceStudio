package com.androidperformancestudio.desktop

import com.androidperformancestudio.ui.ViewerTypography

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsDialogStyleTest {
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
