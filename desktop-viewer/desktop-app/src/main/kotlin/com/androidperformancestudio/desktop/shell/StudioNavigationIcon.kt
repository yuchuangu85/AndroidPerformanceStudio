package com.androidperformancestudio.desktop.shell

import com.androidperformancestudio.desktop.AppDestination
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_home
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_source_workspaces
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_layout_inspector
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_cpu_profiler
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_trace_analyzer
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_memory_profiler
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_frame_profiler
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_startup_profiler
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_battery
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_network
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_gpu_inspector
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_benchmark
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_method_recording
import com.androidperformancestudio.ui_components.generated.resources.ic_settings
import com.androidperformancestudio.ui_components.generated.resources.Res as UiComponentsRes
import org.jetbrains.compose.resources.DrawableResource

/**
 * Icon projected for each [AppDestination] in the primary navigation.
 *
 * The vectors live in the shared ui-components compose resources so every shell surface can reuse
 * them and so the collapsed rail can render an icon instead of a truncated label.
 */
internal val AppDestination.navigationIconResource: DrawableResource
    get() =
        when (this) {
            AppDestination.HOME -> UiComponentsRes.drawable.ic_nav_home
            AppDestination.SOURCE_WORKSPACES -> UiComponentsRes.drawable.ic_nav_source_workspaces
            AppDestination.LAYOUT_INSPECTOR -> UiComponentsRes.drawable.ic_nav_layout_inspector
            AppDestination.SIMPLEPERF -> UiComponentsRes.drawable.ic_nav_cpu_profiler
            AppDestination.PERFETTO -> UiComponentsRes.drawable.ic_nav_trace_analyzer
            AppDestination.MEMORY_PROFILER -> UiComponentsRes.drawable.ic_nav_memory_profiler
            AppDestination.FRAME_PROFILER -> UiComponentsRes.drawable.ic_nav_frame_profiler
            AppDestination.STARTUP_PROFILER -> UiComponentsRes.drawable.ic_nav_startup_profiler
            AppDestination.BATTERY_PROFILER -> UiComponentsRes.drawable.ic_nav_battery
            AppDestination.NETWORK_PROFILER -> UiComponentsRes.drawable.ic_nav_network
            AppDestination.GPU_INSPECTOR -> UiComponentsRes.drawable.ic_nav_gpu_inspector
            AppDestination.BENCHMARK_REGRESSION -> UiComponentsRes.drawable.ic_nav_benchmark
            AppDestination.METHOD_RECORDING -> UiComponentsRes.drawable.ic_nav_method_recording
            AppDestination.SETTINGS -> UiComponentsRes.drawable.ic_settings
        }

