package com.androidperformancestudio.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.file.Path
import com.androidperformancestudio.source.SourceLocation

enum class AppDestination {
    HOME,
    SOURCE_WORKSPACES,
    LAYOUT_INSPECTOR,
    SIMPLEPERF,
    PERFETTO,
    MEMORY_PROFILER,
    FRAME_PROFILER,
    STARTUP_PROFILER,
    BATTERY_PROFILER,
    NETWORK_PROFILER,
    GPU_INSPECTOR,
    BENCHMARK_REGRESSION,
}

internal fun com.androidperformancestudio.desktop.AppDestination.shouldMaximizeWindow(): Boolean =
    when (this) {
        AppDestination.HOME -> false
        else -> true
    }

class AppNavigator(
    initialDestination: com.androidperformancestudio.desktop.AppDestination = AppDestination.HOME,
) {
    var destination by mutableStateOf(initialDestination)
        private set
    var inspectorCorrelationHint by mutableStateOf<com.androidperformancestudio.desktop.InspectorCorrelationHint?>(null)
        private set
    var perfettoTraceFile by mutableStateOf<Path?>(null)
        private set
    var perfettoTraceNotice by mutableStateOf<String?>(null)
        private set
    var sourceLocation by mutableStateOf<SourceLocation?>(null)
        private set

    fun open(destination: com.androidperformancestudio.desktop.AppDestination) {
        if (destination != AppDestination.LAYOUT_INSPECTOR) inspectorCorrelationHint = null
        if (destination != AppDestination.PERFETTO) {
            perfettoTraceFile = null
            perfettoTraceNotice = null
        }
        if (destination != AppDestination.SOURCE_WORKSPACES) sourceLocation = null
        this.destination = destination
    }

    fun openSource(location: SourceLocation) {
        inspectorCorrelationHint = null
        sourceLocation = location
        destination = AppDestination.SOURCE_WORKSPACES
    }

    fun openLayoutInspector(correlationHint: com.androidperformancestudio.desktop.InspectorCorrelationHint?) {
        inspectorCorrelationHint = correlationHint
        destination = AppDestination.LAYOUT_INSPECTOR
    }

    fun openPerfettoTrace(
        path: Path,
        notice: String,
    ) {
        inspectorCorrelationHint = null
        perfettoTraceFile = path
        perfettoTraceNotice = notice
        destination = AppDestination.PERFETTO
    }
}
