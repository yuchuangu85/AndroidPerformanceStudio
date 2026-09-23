package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** App and process targets share the opt-in searchable dropdown; device/config menus remain unchanged. */
class SearchableApplicationDropdownContractTest {
    @Test
    fun `all profiler app and process selectors enable shared dropdown search`() {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        val selectors =
            mapOf(
                "simpleperf-viewer/presentation/src/main/kotlin/com/androidperformancestudio/presentation/DeviceTargetPage.kt" to
                    listOf("items = packages,", "items = processes,"),
                "frame-profiler/frame-app/src/main/kotlin/com/androidperformancestudio/frame/app/FrameProfilerMainPage.kt" to
                    listOf("items = state.processes,"),
                "memory-profiler/presentation/src/main/kotlin/com/androidperformancestudio/memory/presentation/MemoryProfilerToolbarSelectors.kt" to
                    listOf("items = state.processes,"),
                "simpleperf-viewer/method-recording-app/src/main/kotlin/com/androidperformancestudio/methodrecording/app/MethodRecordingMainPage.kt" to
                    listOf("items = state.processes,"),
                "startup-profiler/startup-app/src/main/kotlin/com/androidperformancestudio/startup/app/StartupProfilerMainPage.kt" to
                    listOf("items = state.targets,"),
                "battery-profiler/battery-app/src/main/kotlin/com/androidperformancestudio/battery/app/BatteryProfilerMainPage.kt" to
                    listOf("items = state.targets,"),
            )
        selectors.forEach { (relativePath, itemExpressions) ->
            val source = Files.readString(desktopViewer.resolve(relativePath))
            itemExpressions.forEach { items ->
                val index = source.indexOf(items)
                assertTrue(index >= 0, "$relativePath: missing $items")
                assertTrue(
                    source.substring(index, (index + 140).coerceAtMost(source.length)).contains("searchable = true,"),
                    "$relativePath: $items must use searchable DropdownSelector",
                )
            }
        }
    }
}
