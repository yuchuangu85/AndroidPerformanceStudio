package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdvancedMenuTest {
    @Test
    fun `advanced menu exposes one localized export child item`() {
        val strings = ViewerStrings.forLanguage(ViewerLanguage.SIMPLIFIED_CHINESE)

        val model = AdvancedMenuModel(strings)

        assertEquals("高级", model.title)
        assertEquals("导出 Visible Window Views…", model.exportLabel)
        assertTrue(model.exportEnabled)
    }

    @Test
    fun `advanced export is disabled while an export is running`() {
        val strings = ViewerStrings.forLanguage(ViewerLanguage.ENGLISH)

        val model = AdvancedMenuModel(strings, exportInProgress = true)

        assertFalse(model.exportEnabled)
    }

    @Test
    fun `directory chooser cancellation returns no destination`() {
        val chooser = ExportDirectoryChooser { null }

        assertNull(chooser.chooseDirectory("Choose export directory"))
    }
}
