package com.androidperformancestudio.battery.app

import com.androidperformancestudio.ui.UiLanguage
import java.nio.file.Path
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatteryProfilerMenuTest {
    @Test
    fun `english menu exposes import export recent and advanced actions`() {
        val model =
            batteryProfilerMenuModel(
                language = UiLanguage.fromLocale(Locale.ENGLISH),
                recentFiles = listOf(Path.of("/reports/one.json")),
                importEnabled = true,
                jsonExportEnabled = false,
                csvExportEnabled = true,
                rawEvidenceExportEnabled = false,
                resetEnabled = true,
            )

        assertEquals("File", model.fileTitle)
        assertEquals("Import…", model.importLabel)
        assertEquals("Export", model.exportMenu.title)
        assertEquals("Export JSON", model.exportMenu.jsonLabel)
        assertEquals("Export CSV", model.exportMenu.csvLabel)
        assertEquals("Export Raw Evidence", model.exportMenu.rawEvidenceLabel)
        assertEquals("Open Recent", model.openRecentTitle)
        assertEquals(listOf("one.json"), model.recentItems.map { it.label })
        assertEquals("Advanced", model.advancedTitle)
        assertEquals("Reset Statistics", model.resetStatisticsLabel)
        assertTrue(model.importEnabled)
        assertFalse(model.exportMenu.jsonEnabled)
        assertTrue(model.exportMenu.csvEnabled)
        assertFalse(model.exportMenu.rawEvidenceEnabled)
        assertTrue(model.resetEnabled)
    }

    @Test
    fun `chinese menu localizes labels and disambiguates duplicate recent names`() {
        val model =
            batteryProfilerMenuModel(
                language = UiLanguage.fromLocale(Locale.SIMPLIFIED_CHINESE),
                recentFiles =
                    listOf(
                        Path.of("/reports/first/report.json"),
                        Path.of("/reports/second/report.json"),
                    ),
                importEnabled = false,
                jsonExportEnabled = true,
                csvExportEnabled = true,
                rawEvidenceExportEnabled = true,
                resetEnabled = false,
            )

        assertEquals("文件", model.fileTitle)
        assertEquals("导入…", model.importLabel)
        assertEquals("导出", model.exportMenu.title)
        assertEquals("导出 JSON", model.exportMenu.jsonLabel)
        assertEquals("导出 CSV", model.exportMenu.csvLabel)
        assertEquals("导出原始证据", model.exportMenu.rawEvidenceLabel)
        assertEquals("最近打开", model.openRecentTitle)
        assertTrue(model.recentItems.all { it.label == it.path.toString() })
        assertEquals("高级", model.advancedTitle)
        assertEquals("重置统计", model.resetStatisticsLabel)
        assertFalse(model.importEnabled)
        assertFalse(model.resetEnabled)
    }
}
