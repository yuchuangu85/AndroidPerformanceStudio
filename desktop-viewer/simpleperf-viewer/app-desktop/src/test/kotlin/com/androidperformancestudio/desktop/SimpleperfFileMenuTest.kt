package com.androidperformancestudio.desktop

import com.androidperformancestudio.ui.UiLanguage
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleperfFileMenuTest {
    @Test
    fun `file menu exposes export formats configuration and recent sessions`() =
        runTest {
            val sessions = listOf(Path.of("/profiles/session-one"), Path.of("/profiles/session-two"))
            val model =
                simpleperfFileMenuModel(
                    language = UiLanguage.fromLocale(Locale.ENGLISH),
                    recentSessions = sessions,
                    exportEnabled = false,
                )
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
            assertEquals(listOf("session-one", "session-two"), model.recentItems.map { it.label })
            assertFalse(model.exportEnabled)
        }

    @Test
    fun `file menu localizes labels and disambiguates duplicate session names`() =
        runTest {
            val model =
                simpleperfFileMenuModel(
                    language = UiLanguage.fromLocale(Locale.SIMPLIFIED_CHINESE),
                    recentSessions =
                        listOf(
                            Path.of("/profiles/first/session"),
                            Path.of("/profiles/second/session"),
                        ),
                    exportEnabled = true,
                )
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
            assertTrue(model.recentItems.all { it.label == it.path.toString() })
            assertTrue(model.exportEnabled)
        }

    @Test
    fun `window menu opens session files and folders with separate native choosers`() {
        val menu = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/com/androidperformancestudio/desktop/SimpleperfFileMenu.kt"),
        )
        val page = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/com/androidperformancestudio/desktop/SimpleperfMainPage.kt"),
        )
        val actions = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopReportActionFactory.kt"),
        )

        assertTrue(menu.contains("onOpenFolder = onOpenFolder"))
        assertTrue(page.contains("onOpen = reportActions.onOpenSessionFile"))
        assertTrue(page.contains("onOpenFolder = reportActions.onOpenSessionFolder"))
        assertTrue(actions.contains("chooseFile(SESSION_FILE_DIALOG_TITLE)?.let(::openSession)"))
        assertTrue(actions.contains("chooseDirectory(SESSION_FOLDER_DIALOG_TITLE)?.let(::openSession)"))
    }
}
