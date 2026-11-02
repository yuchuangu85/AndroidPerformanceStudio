package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DesktopAiContextRoutingTest {
    @Test
    fun `global AI entry selects the exact context producer for each supported destination`() {
        val expected = mapOf(
            AppDestination.LAYOUT_INSPECTOR to "layout-inspector",
            AppDestination.SIMPLEPERF to "simpleperf",
            AppDestination.PERFETTO to "trace",
            AppDestination.MEMORY_PROFILER to "memory",
            AppDestination.FRAME_PROFILER to "frame",
            AppDestination.STARTUP_PROFILER to "startup",
            AppDestination.BATTERY_PROFILER to "battery",
            AppDestination.NETWORK_PROFILER to "network",
            AppDestination.GPU_INSPECTOR to "gpu",
            AppDestination.BENCHMARK_REGRESSION to "benchmark",
            AppDestination.SOURCE_WORKSPACES to "source-workspace",
        )
        AppDestination.entries.forEach { destination ->
            if (destination in expected) assertEquals(expected[destination], destination.aiContextKey())
            else assertNull(destination.aiContextKey(), destination.name)
        }
    }
}
