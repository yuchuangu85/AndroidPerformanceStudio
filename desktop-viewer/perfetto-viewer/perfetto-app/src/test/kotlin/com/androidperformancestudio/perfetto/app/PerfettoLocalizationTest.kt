package com.androidperformancestudio.perfetto.app

import com.androidperformancestudio.perfetto_app.generated.resources.Res
import com.androidperformancestudio.perfetto_app.generated.resources.diagnostic_frame_jank_title
import com.androidperformancestudio.perfetto_app.generated.resources.file
import com.androidperformancestudio.perfetto_app.generated.resources.recent_sessions
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerfettoLocalizationTest {
    private val fileMenuSource =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/perfetto/app/PerfettoFileMenu.kt"),
        )
    private val chineseResources =
        Files.readString(
            Path.of("src/main/composeResources/values-zh/strings.xml"),
        )

    @Test
    fun `native file menu follows the application language`() {
        assertTrue(fileMenuSource.contains("localizedStringResource"))
        assertFalse(fileMenuSource.contains("stringResource("))
        assertTrue(fileMenuSource.contains("Res.string.file, language"))
        assertTrue(fileMenuSource.contains("Res.string.open_recent, language"))
    }

    @Test
    fun `trace analyzer provides Chinese workspace resources`() {
        assertTrue(chineseResources.contains("<string name=\"file\">文件</string>"))
        assertTrue(chineseResources.contains("<string name=\"recent_sessions\">最近的会话</string>"))
        assertTrue(chineseResources.contains("<string name=\"trace_diagnostics\">轨迹诊断 · %1\$s</string>"))
        assertTrue(chineseResources.contains("<string name=\"diagnostic_frame_jank_title\">帧卡顿检测</string>"))

        assertEquals("文件", localizedStringResource(Res.string.file, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("最近的会话", localizedStringResource(Res.string.recent_sessions, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals(
            "帧卡顿检测",
            localizedStringResource(Res.string.diagnostic_frame_jank_title, UiLanguage.SIMPLIFIED_CHINESE),
        )
    }
}
