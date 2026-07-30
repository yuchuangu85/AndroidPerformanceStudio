package com.androidperformancestudio.memory.app

import com.androidperformancestudio.ui.UiLanguage
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryProfilerFileMenuTest {
    @Test
    fun `file menu exposes import and individual export availability`() {
        val model =
            memoryProfilerFileMenuModel(
                language = UiLanguage.fromLocale(Locale.ENGLISH),
                importEnabled = false,
                rawHprofExportEnabled = true,
                standardHprofExportEnabled = false,
                csvExportEnabled = true,
            )

        assertEquals("File", model.fileTitle)
        assertEquals("Import HPROF…", model.importLabel)
        assertEquals("Export", model.exportTitle)
        assertEquals("Export Raw HPROF", model.exportRawHprofLabel)
        assertEquals("Export Standard HPROF", model.exportStandardHprofLabel)
        assertEquals("Export CSV", model.exportCsvLabel)
        assertFalse(model.importEnabled)
        assertTrue(model.rawHprofExportEnabled)
        assertFalse(model.standardHprofExportEnabled)
        assertTrue(model.csvExportEnabled)
    }

    @Test
    fun `file menu localizes chinese labels`() {
        val model =
            memoryProfilerFileMenuModel(
                language = UiLanguage.fromLocale(Locale.SIMPLIFIED_CHINESE),
                importEnabled = true,
                rawHprofExportEnabled = true,
                standardHprofExportEnabled = true,
                csvExportEnabled = true,
            )

        assertEquals("文件", model.fileTitle)
        assertEquals("导入 HPROF…", model.importLabel)
        assertEquals("导出", model.exportTitle)
        assertEquals("导出原始 HPROF", model.exportRawHprofLabel)
        assertEquals("导出标准 HPROF", model.exportStandardHprofLabel)
        assertEquals("导出 CSV", model.exportCsvLabel)
    }
}
