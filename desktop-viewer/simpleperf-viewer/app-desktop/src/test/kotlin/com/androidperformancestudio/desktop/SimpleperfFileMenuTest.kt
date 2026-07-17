package com.androidperformancestudio.desktop

import androidx.compose.ui.input.key.Key
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleperfFileMenuTest {
    @Test
    fun `file menu exposes open export and recent sessions with mac shortcuts`() {
        val sessions = listOf(Path.of("/profiles/session-one"), Path.of("/profiles/session-two"))
        val model =
            SimpleperfFileMenuModel(
                language = SimpleperfLanguage.ENGLISH,
                recentSessions = sessions,
                exportEnabled = false,
                isMacOs = true,
            )

        assertEquals("File", model.fileTitle)
        assertEquals("Open…", model.openLabel)
        assertEquals("Export", model.exportMenu.title)
        assertEquals("Session package", model.exportMenu.sessionPackageLabel)
        assertEquals("JSON + CSV", model.exportMenu.reportLabel)
        assertEquals("Firefox Profiler JSON (.json.gz)", model.exportMenu.geckoProfileLabel)
        assertEquals("Raw protobuf", model.exportMenu.rawProtobufLabel)
        assertEquals("Screenshot", model.exportMenu.screenshotLabel)
        assertEquals("simpleperf report", model.exportMenu.simpleperfReportLabel)
        assertEquals("report_html.py", model.exportMenu.htmlReportLabel)
        assertEquals("External open", model.exportMenu.externalOpenLabel)
        assertEquals("Configuration", model.configurationMenu.title)
        assertEquals("Capture Templates", model.configurationMenu.samplingTemplateLabel)
        assertEquals("Capture Configuration", model.configurationMenu.captureConfigurationLabel)
        assertEquals("Advanced Parameters", model.configurationMenu.advancedParametersLabel)
        assertEquals("Open Recent", model.openRecentTitle)
        assertEquals(listOf("session-one", "session-two"), model.recentItems.map { it.label })
        assertFalse(model.exportEnabled)
        assertEquals(SimpleperfMenuShortcut(Key.O, ctrl = false, meta = true), model.openShortcut)
        assertEquals(SimpleperfMenuShortcut(Key.E, ctrl = false, meta = true), model.exportShortcut)
    }

    @Test
    fun `file menu localizes labels and disambiguates duplicate session names`() {
        val model =
            SimpleperfFileMenuModel(
                language = SimpleperfLanguage.SIMPLIFIED_CHINESE,
                recentSessions =
                    listOf(
                        Path.of("/profiles/first/session"),
                        Path.of("/profiles/second/session"),
                    ),
                exportEnabled = true,
                isMacOs = false,
            )

        assertEquals("文件", model.fileTitle)
        assertEquals("打开…", model.openLabel)
        assertEquals("导出", model.exportMenu.title)
        assertEquals("会话包", model.exportMenu.sessionPackageLabel)
        assertEquals("JSON + CSV", model.exportMenu.reportLabel)
        assertEquals("Firefox Profiler JSON (.json.gz)", model.exportMenu.geckoProfileLabel)
        assertEquals("原始 protobuf", model.exportMenu.rawProtobufLabel)
        assertEquals("截图", model.exportMenu.screenshotLabel)
        assertEquals("外部打开", model.exportMenu.externalOpenLabel)
        assertEquals("配置", model.configurationMenu.title)
        assertEquals("采集模板", model.configurationMenu.samplingTemplateLabel)
        assertEquals("采集配置", model.configurationMenu.captureConfigurationLabel)
        assertEquals("高级参数", model.configurationMenu.advancedParametersLabel)
        assertEquals("最近打开", model.openRecentTitle)
        assertTrue(model.recentItems.all { it.label == it.path.toString() })
        assertTrue(model.exportEnabled)
        assertEquals(SimpleperfMenuShortcut(Key.O, ctrl = true, meta = false), model.openShortcut)
    }
}
