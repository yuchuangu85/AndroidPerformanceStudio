package com.androidperformancestudio.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceSelectorAdoptionTest {
    @Test
    fun `all device dropdowns use the shared device selector`() {
        val pages =
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
        pages.forEach { (module, page) ->
            val source = Files.readString(Path.of("..", module, "src/main/kotlin/com/androidperformancestudio", page))
            assertTrue(source.contains("import com.androidperformancestudio.ui.DeviceDropdownSelector"), page)
            assertEquals(1, Regex("DeviceDropdownSelector\\(").findAll(source).count(), page)
        }
    }
}
