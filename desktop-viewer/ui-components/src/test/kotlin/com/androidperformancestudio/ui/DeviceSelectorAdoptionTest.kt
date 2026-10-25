package com.androidperformancestudio.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceSelectorAdoptionTest {
    private val devicePages =
        listOf(
            "layout-inspector/presentation" to "desktop/LayoutInspectorMainPage.kt",
            "simpleperf-viewer/presentation" to "presentation/DeviceTargetPage.kt",
            "simpleperf-viewer/method-recording-app" to "methodrecording/app/MethodRecordingMainPage.kt",
            "memory-profiler/presentation" to "memory/presentation/MemoryProfilerToolbarSelectors.kt",
            "perfetto-viewer/perfetto-app" to "perfetto/app/PerfettoMainPage.kt",
            "frame-profiler/frame-app" to "frame/app/FrameProfilerMainPage.kt",
            "startup-profiler/presentation" to "startup/presentation/StartupWorkspaceControls.kt",
            "battery-profiler/battery-app" to "battery/app/BatteryProfilerMainPage.kt",
            "gpu-inspector-integration/presentation" to "gpu/presentation/GpuAnalysisHubScreen.kt",
        )

    @Test
    fun `all device dropdowns use the shared device selector`() {
        devicePages.forEach { (module, page) ->
            val source = Files.readString(Path.of("..", module, "src/main/kotlin/com/androidperformancestudio", page))
            assertTrue(source.contains("import com.androidperformancestudio.ui.DeviceDropdownSelector"), page)
            assertEquals(1, Regex("DeviceDropdownSelector\\(").findAll(source).count(), page)
        }
    }

    @Test
    fun `pages publish only real device and process selectors to the title bar`() {
        val processModules =
            setOf(
                "simpleperf-viewer/presentation",
                "simpleperf-viewer/method-recording-app",
                "memory-profiler/presentation",
                "frame-profiler/frame-app",
                "startup-profiler/presentation",
                "battery-profiler/battery-app",
            )
        devicePages.forEach { (module, page) ->
            val source = Files.readString(Path.of("..", module, "src/main/kotlin/com/androidperformancestudio", page))
            assertTrue(source.contains("TitleBarTargetOrInline(TitleBarTargetSlot.DEVICE)"), page)
            assertEquals(
                module in processModules,
                source.contains("TitleBarTargetOrInline(TitleBarTargetSlot.PROCESS)"),
                page,
            )
        }
    }

    @Test
    fun `title bar selectors use the same fixed width on every page`() {
        devicePages.forEach { (module, page) ->
            val source = Files.readString(Path.of("..", module, "src/main/kotlin/com/androidperformancestudio", page))
            assertTrue(source.contains("TitleBarTargetWidth"), page)
        }
    }
}
