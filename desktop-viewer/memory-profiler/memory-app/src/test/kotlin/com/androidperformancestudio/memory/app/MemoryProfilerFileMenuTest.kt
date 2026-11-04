package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.storage.MemorySessionMetadata
import com.androidperformancestudio.ui.UiLanguage
import java.nio.file.Path
import java.time.Instant
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
        assertEquals("Export Raw HPROF", model.exportRawHprofLabel)
        assertEquals("Export Standard HPROF", model.exportStandardHprofLabel)
        assertEquals("Export CSV", model.exportCsvLabel)
        assertFalse(model.importEnabled)
        assertTrue(model.rawHprofExportEnabled)
        assertFalse(model.standardHprofExportEnabled)
        assertTrue(model.csvExportEnabled)
        assertEquals("ART Sample Trace…", model.importSampledArtLabel)
        assertFalse(model.importSampledArtEnabled)
        assertEquals("Export ART Sample Trace", model.exportSampledArtLabel)
        assertFalse(model.exportSampledArtEnabled)
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
        assertEquals("导出原始 HPROF", model.exportRawHprofLabel)
        assertEquals("导出标准 HPROF", model.exportStandardHprofLabel)
        assertEquals("导出 CSV", model.exportCsvLabel)
        assertEquals("导入 ART 采样轨迹…", model.importSampledArtLabel)
        assertEquals("导出 ART 采样轨迹", model.exportSampledArtLabel)
    }

    @Test
    fun `file menu limits recent sessions rendered by the native menu`() {
        val sessions =
            (1..20).map { index ->
                MemorySessionMetadata(
                    sessionId = "session-$index",
                    packageName = "com.example.$index",
                    deviceSerial = "device",
                    capturedAt = Instant.ofEpochMilli(index.toLong()),
                    rawHprofFile = Path.of("session-$index.hprof"),
                )
            }

        val model =
            memoryProfilerFileMenuModel(
                language = UiLanguage.fromLocale(Locale.ENGLISH),
                importEnabled = true,
                rawHprofExportEnabled = false,
                standardHprofExportEnabled = false,
                csvExportEnabled = false,
                recentSessions = sessions,
            )

        assertEquals(12, model.recentSessions.size)
        assertEquals("session-1", model.recentSessions.first().sessionId)
        assertEquals("session-12", model.recentSessions.last().sessionId)
    }
}
