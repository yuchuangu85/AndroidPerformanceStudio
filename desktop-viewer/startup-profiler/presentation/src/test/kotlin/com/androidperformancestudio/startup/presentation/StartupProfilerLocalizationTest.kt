package com.androidperformancestudio.startup.presentation

import com.androidperformancestudio.startup.presentation.generated.resources.Res
import com.androidperformancestudio.startup.presentation.generated.resources.cold
import com.androidperformancestudio.startup.presentation.generated.resources.exact
import com.androidperformancestudio.startup.presentation.generated.resources.phase_first_frame_to_fully_drawn
import com.androidperformancestudio.startup.presentation.generated.resources.phase_range
import com.androidperformancestudio.startup.presentation.generated.resources.startup_profiler
import com.androidperformancestudio.startup.presentation.generated.resources.total
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupProfilerLocalizationTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/startup/presentation/StartupProfilerScreen.kt"),
        )

    @Test
    fun `startup profiler screen resolves labels with the application language`() {
        assertTrue(source.contains("localizedStringResource"))
        assertFalse(source.contains("stringResource("))
        assertTrue(source.contains("run.observedType.localizedLabel(language)"))
        assertTrue(source.contains("milestone.kind.localizedLabel(language)"))
        assertTrue(source.contains("milestone.confidence.localizedLabel(language)"))
        assertTrue(source.contains("phase.localizedName(language)"))
        assertFalse(source.contains("else -> return name"))
    }

    @Test
    fun `startup profiler presentation labels are translated to Chinese`() {
        assertEquals(
            "启动性能分析器",
            localizedStringResource(Res.string.startup_profiler, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "总耗时",
            localizedStringResource(Res.string.total, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "冷启动",
            localizedStringResource(Res.string.cold, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "精确",
            localizedStringResource(Res.string.exact, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "首帧到完全绘制",
            localizedStringResource(Res.string.phase_first_frame_to_fully_drawn, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "进程启动到首帧",
            localizedStringResource(
                Res.string.phase_range,
                UiLanguage.SIMPLIFIED_CHINESE,
                "进程启动",
                "首帧",
            ),
        )
    }
}
