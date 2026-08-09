package com.androidperformancestudio.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
    METHOD_RECORDING,
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
    val retainedDestinations = mutableStateListOf(initialDestination)
    var inspectorCorrelationHint by mutableStateOf<com.androidperformancestudio.desktop.InspectorCorrelationHint?>(null)
        private set
    var perfettoTraceFile by mutableStateOf<Path?>(null)
        private set
    var perfettoTraceNotice by mutableStateOf<String?>(null)
        private set
    var sourceLocation by mutableStateOf<SourceLocation?>(null)
        private set
    var memoryImportFile by mutableStateOf<Path?>(null)
        private set
    var memoryImportIsJavaHeap by mutableStateOf(false)
        private set
    var methodRecordingTraceFile by mutableStateOf<Path?>(null)
        private set

    fun open(destination: com.androidperformancestudio.desktop.AppDestination) {
        activate(destination)
    }

    fun openSource(location: SourceLocation) {
        sourceLocation = location
        activate(AppDestination.SOURCE_WORKSPACES)
    }

    fun openLayoutInspector(correlationHint: com.androidperformancestudio.desktop.InspectorCorrelationHint?) {
        inspectorCorrelationHint = correlationHint
        activate(AppDestination.LAYOUT_INSPECTOR)
    }

    fun openPerfettoTrace(
        path: Path,
        notice: String,
    ) {
        perfettoTraceFile = path
        perfettoTraceNotice = notice
        activate(AppDestination.PERFETTO)
    }

    /** Opens the memory profiler and imports [file] (HPROF, or java_hprof when [javaHeap]). */
    fun openMemoryProfiler(
        file: Path,
        javaHeap: Boolean = false,
    ) {
        inspectorCorrelationHint = null
        memoryImportFile = file
        memoryImportIsJavaHeap = javaHeap
        activate(AppDestination.MEMORY_PROFILER)
    }

    /** Opens method recording and imports the ART [file]. */
    fun openMethodRecording(file: Path) {
        inspectorCorrelationHint = null
        methodRecordingTraceFile = file
        activate(AppDestination.METHOD_RECORDING)
    }

    private fun activate(destination: AppDestination) {
        if (destination !in retainedDestinations) retainedDestinations += destination
        this.destination = destination
    }
}
