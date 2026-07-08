package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsDialogStyleTest {
    @Test
    fun `settings typography creates clear hierarchy`() {
        assertTrue(SettingsDialogStyle.TITLE_FONT_SIZE_SP >= SettingsDialogStyle.SECTION_TITLE_FONT_SIZE_SP + 5)
        assertTrue(SettingsDialogStyle.SECTION_TITLE_FONT_SIZE_SP >= SettingsDialogStyle.CONTENT_FONT_SIZE_SP + 2)
    }

    @Test
    fun `settings menu groups are separated by visible dividers`() {
        assertEquals(3, SettingsDialogStyle.SECTION_SEPARATOR_COUNT)
        assertEquals(1, SettingsDialogStyle.SEPARATOR_HEIGHT_DP)
        assertTrue(SettingsDialogStyle.SEPARATOR_VERTICAL_PADDING_DP >= 10)
    }
}
