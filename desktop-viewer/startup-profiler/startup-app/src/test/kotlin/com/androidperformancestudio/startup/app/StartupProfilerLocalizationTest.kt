package com.androidperformancestudio.startup.app

import com.androidperformancestudio.startup.startup_app.generated.resources.Res
import com.androidperformancestudio.startup.startup_app.generated.resources.experiment_complete
import com.androidperformancestudio.startup.startup_app.generated.resources.found_launchable_activities
import com.androidperformancestudio.startup.startup_app.generated.resources.measured_run_progress
import com.androidperformancestudio.startup.startup_app.generated.resources.preparing_startup_experiment
import com.androidperformancestudio.startup.startup_app.generated.resources.seconds_short
import com.androidperformancestudio.startup.startup_app.generated.resources.warm_up_progress
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupProfilerLocalizationTest {
    private val controllerSource =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/startup/app/StartupProfilerController.kt"),
        )
    private val runnerSource =
        Files.readString(
            Path.of(
                "..",
                "capture-startup",
                "src/main/kotlin/com/androidperformancestudio/startup/capture/StartupExperimentRunner.kt",
            ),
        )

    @Test
    fun `experiment progress is structured and localized by the controller`() {
        assertTrue(controllerSource.contains("progress.localizedMessage(language)"))
        assertTrue(controllerSource.contains("StartupExperimentProgressStage.WARM_UP"))
        assertTrue(controllerSource.contains("StartupExperimentProgressStage.MEASURED_RUN"))
        assertTrue(controllerSource.contains("StartupExperimentProgressStage.COMPLETE"))
        assertFalse(runnerSource.contains("\"Warm-up "))
        assertFalse(runnerSource.contains("\"Measured run "))
        assertFalse(runnerSource.contains("\"Complete\""))
    }

    @Test
    fun `startup profiler operation labels are translated to Chinese`() {
        assertEquals(
            "找到 2 个可启动 Activity。",
            localizedStringResource(Res.string.found_launchable_activities, UiLanguage.SIMPLIFIED_CHINESE, 2),
        )
        assertEquals(
            "正在准备启动实验…",
            localizedStringResource(Res.string.preparing_startup_experiment, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "预热 1/3",
            localizedStringResource(Res.string.warm_up_progress, UiLanguage.SIMPLIFIED_CHINESE, 1, 3),
        )
        assertEquals(
            "测量 2/5",
            localizedStringResource(Res.string.measured_run_progress, UiLanguage.SIMPLIFIED_CHINESE, 2, 5),
        )
        assertEquals(
            "完成",
            localizedStringResource(Res.string.experiment_complete, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "30 秒",
            localizedStringResource(Res.string.seconds_short, UiLanguage.SIMPLIFIED_CHINESE, 30),
        )
    }
}
