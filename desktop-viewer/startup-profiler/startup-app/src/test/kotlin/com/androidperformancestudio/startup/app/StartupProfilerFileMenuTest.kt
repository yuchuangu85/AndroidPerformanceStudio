package com.androidperformancestudio.startup.app

import com.androidperformancestudio.ui.UiLanguage
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupProfilerFileMenuTest {
    @Test
    fun `english file menu exposes import and exports`() {
        val model =
            startupProfilerFileMenuModel(
                language = UiLanguage.fromLocale(Locale.ENGLISH),
                importEnabled = true,
                exportEnabled = false,
            )

        assertEquals("File", model.fileTitle)
        assertEquals("Import…", model.importLabel)
        assertEquals("Export", model.exportMenu.title)
        assertEquals("Export CSV", model.exportMenu.csvLabel)
        assertEquals("Export JSON", model.exportMenu.jsonLabel)
        assertTrue(model.importEnabled)
        assertFalse(model.exportMenu.enabled)
    }

    @Test
    fun `chinese file menu localizes actions`() {
        val model =
            startupProfilerFileMenuModel(
                language = UiLanguage.fromLocale(Locale.SIMPLIFIED_CHINESE),
                importEnabled = false,
                exportEnabled = true,
            )

        assertEquals("文件", model.fileTitle)
        assertEquals("导入…", model.importLabel)
        assertEquals("导出", model.exportMenu.title)
        assertEquals("导出 CSV", model.exportMenu.csvLabel)
        assertEquals("导出 JSON", model.exportMenu.jsonLabel)
        assertFalse(model.importEnabled)
        assertTrue(model.exportMenu.enabled)
    }
}
