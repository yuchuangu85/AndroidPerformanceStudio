package com.androidperformancestudio.perfetto.presentation

import com.androidperformancestudio.perfetto_presentation.generated.resources.Res
import com.androidperformancestudio.perfetto_presentation.generated.resources.configuration
import com.androidperformancestudio.perfetto_presentation.generated.resources.start_capture
import com.androidperformancestudio.perfetto_presentation.generated.resources.system_overview
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_template
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerfettoLocalizationTest {
    private val capturePageSource =
        Files.readString(
            Path.of(
                "src/main/kotlin/com/androidperformancestudio/perfetto/presentation/PerfettoCapturePage.kt",
            ),
        )
    private val chineseResources =
        Files.readString(
            Path.of("src/main/composeResources/values-zh/strings.xml"),
        )

    @Test
    fun `capture page resolves every label with the application language`() {
        assertTrue(capturePageSource.contains("localizedStringResource"))
        assertFalse(capturePageSource.contains("stringResource("))
        assertTrue(capturePageSource.contains("Res.string.trace_template, language"))
        assertTrue(capturePageSource.contains("Res.string.ready, language"))
    }

    @Test
    fun `capture page provides Chinese resources`() {
        assertTrue(chineseResources.contains("<string name=\"trace_template\">轨迹模板</string>"))
        assertTrue(chineseResources.contains("<string name=\"configuration\">配置</string>"))
        assertTrue(chineseResources.contains("<string name=\"start_capture\">开始采集</string>"))
        assertTrue(chineseResources.contains("<string name=\"system_overview\">系统概览</string>"))

        assertEquals("轨迹模板", localizedStringResource(Res.string.trace_template, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("配置", localizedStringResource(Res.string.configuration, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("开始采集", localizedStringResource(Res.string.start_capture, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("系统概览", localizedStringResource(Res.string.system_overview, UiLanguage.SIMPLIFIED_CHINESE))
    }
}
