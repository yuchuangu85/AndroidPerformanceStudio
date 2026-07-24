package dev.agentperf.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.file.Path

enum class AppDestination {
    HOME,
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

internal fun AppDestination.shouldMaximizeWindow(): Boolean =
    when (this) {
        AppDestination.HOME -> false
        else -> true
    }

class AppNavigator(
    initialDestination: AppDestination = AppDestination.HOME,
) {
    var destination by mutableStateOf(initialDestination)
        private set
    var inspectorCorrelationHint by mutableStateOf<InspectorCorrelationHint?>(null)
        private set
    var perfettoTraceFile by mutableStateOf<Path?>(null)
        private set
    var perfettoTraceNotice by mutableStateOf<String?>(null)
        private set

    fun open(destination: AppDestination) {
        if (destination != AppDestination.LAYOUT_INSPECTOR) inspectorCorrelationHint = null
        if (destination != AppDestination.PERFETTO) {
            perfettoTraceFile = null
            perfettoTraceNotice = null
        }
        this.destination = destination
    }

    fun openLayoutInspector(correlationHint: InspectorCorrelationHint?) {
        inspectorCorrelationHint = correlationHint
        destination = AppDestination.LAYOUT_INSPECTOR
    }

    fun openPerfettoTrace(
        path: Path,
        sourceTool: String,
    ) {
        inspectorCorrelationHint = null
        perfettoTraceFile = path
        perfettoTraceNotice =
            "Opened from $sourceTool for correlation only; no causal relationship is inferred."
        destination = AppDestination.PERFETTO
    }
}
