package com.androidperformancestudio.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.file.Path
import com.androidperformancestudio.desktop_app.generated.resources.Res
import com.androidperformancestudio.desktop_app.generated.resources.*
import com.androidperformancestudio.source.SourceLocation
import org.jetbrains.compose.resources.StringResource

enum class AppDestination {
    HOME,
    SOURCE_WORKSPACES,
    LAYOUT_INSPECTOR,
    SIMPLEPERF,
    PERFETTO,
    MEMORY_PROFILER,
    FRAME_PROFILER,
    STARTUP_PROFILER,
    BATTERY_NETWORK,
    REVERSE_NET,
    GPU_INSPECTOR,
    BENCHMARK_REGRESSION,
    METHOD_RECORDING,
    // SCREENCAST is kept as a navigation-entry identity (icon/label/home-card source) but is no
    // longer an in-window destination: opening it spawns a separate OS window instead of switching
    // the navigator. See DesktopAppMainPage's screencast Window and CommercialAppShell's dispatch.
    SCREENCAST,
    SETTINGS,
}

data class AppArtifactOpenRequest(
    val path: Path,
    val requestId: Long,
)

internal fun com.androidperformancestudio.desktop.AppDestination.shouldMaximizeWindow(): Boolean =
    when (this) {
        AppDestination.HOME -> false
        // SCREENCAST opens its own window and is never an in-window destination; guard it anyway so a
        // stray navigator.open(SCREENCAST) could never trigger a main-window maximize.
        AppDestination.SCREENCAST -> false
        else -> true
    }

/**
 * Rail and command-palette label for each destination. Home uses the short "Home" string because
 * [titleResource] intentionally carries the product name for the window title.
 */
internal val com.androidperformancestudio.desktop.AppDestination.navigationLabelResource: StringResource
    get() =
        when (this) {
            AppDestination.HOME -> Res.string.home
            else -> titleResource
        }

/** Window-title string shown for each destination, derived from the single navigation source of truth. */
internal val com.androidperformancestudio.desktop.AppDestination.titleResource: StringResource
    get() =
        when (this) {
            AppDestination.HOME -> Res.string.android_performance_studio
            AppDestination.SOURCE_WORKSPACES -> Res.string.source_workspaces
            AppDestination.LAYOUT_INSPECTOR -> Res.string.layout_inspector
            AppDestination.SIMPLEPERF -> Res.string.cpu_profiler
            AppDestination.PERFETTO -> Res.string.trace_analyzer
            AppDestination.MEMORY_PROFILER -> Res.string.memory_profiler
            AppDestination.FRAME_PROFILER -> Res.string.frame_profiler
            AppDestination.STARTUP_PROFILER -> Res.string.startup_profiler
            AppDestination.BATTERY_NETWORK -> Res.string.battery_network
            AppDestination.REVERSE_NET -> Res.string.reverse_net_title
            AppDestination.GPU_INSPECTOR -> Res.string.gpu_inspector
            AppDestination.BENCHMARK_REGRESSION -> Res.string.benchmark_regression
            AppDestination.METHOD_RECORDING -> Res.string.method_recording
            AppDestination.SCREENCAST -> Res.string.device_screencast
            AppDestination.SETTINGS -> Res.string.settings
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
    var perfettoTraceRequestId by mutableStateOf(0L)
        private set
    var perfettoTraceNotice by mutableStateOf<String?>(null)
        private set
    var perfettoTimestampNanos by mutableStateOf<Long?>(null)
        private set
    var sourceLocation by mutableStateOf<SourceLocation?>(null)
        private set
    var memoryImportFile by mutableStateOf<Path?>(null)
        private set
    var memoryImportRequestId by mutableStateOf(0L)
        private set
    var memoryImportIsJavaHeap by mutableStateOf(false)
        private set
    var methodRecordingTraceFile by mutableStateOf<Path?>(null)
        private set
    var layoutArchiveRequest by mutableStateOf<AppArtifactOpenRequest?>(null)
        private set
    var simpleperfSessionRequest by mutableStateOf<AppArtifactOpenRequest?>(null)
        private set
    private var nextArtifactOpenRequestId: Long = 0

    fun open(destination: com.androidperformancestudio.desktop.AppDestination) {
        activate(destination)
    }

    /**
     * Closes [destination]: removes it from [retainedDestinations] so its composed subtree is
     * disposed and its resources released. HOME is never evictable. If [destination] is the
     * currently active destination, the shell first returns to HOME. Any pending navigator
     * request routed to [destination] is cleared so reopening does not re-trigger a stale
     * intent; requests targeting other modules are left untouched.
     */
    fun evict(destination: com.androidperformancestudio.desktop.AppDestination) {
        if (destination == AppDestination.HOME) return
        if (destination == this.destination) {
            this.destination = AppDestination.HOME
        }
        retainedDestinations.remove(destination)
        clearRequestsFor(destination)
    }

    /**
     * Closes every open module except HOME and the currently active destination. The active
     * module and HOME remain composed; everything else is disposed.
     */
    fun evictAll() {
        val active = this.destination
        retainedDestinations.toList().forEach { destination ->
            if (destination != AppDestination.HOME && destination != active) {
                evict(destination)
            }
        }
    }

    private fun clearRequestsFor(destination: com.androidperformancestudio.desktop.AppDestination) {
        when (destination) {
            AppDestination.PERFETTO -> clearPerfettoTrace()
            AppDestination.MEMORY_PROFILER -> {
                memoryImportFile = null
                memoryImportRequestId = 0L
                memoryImportIsJavaHeap = false
            }
            AppDestination.LAYOUT_INSPECTOR -> {
                layoutArchiveRequest = null
                inspectorCorrelationHint = null
            }
            AppDestination.SIMPLEPERF -> simpleperfSessionRequest = null
            AppDestination.METHOD_RECORDING -> methodRecordingTraceFile = null
            AppDestination.SOURCE_WORKSPACES -> sourceLocation = null
            else -> {}
        }
    }

    fun openSource(location: SourceLocation) {
        sourceLocation = location
        activate(AppDestination.SOURCE_WORKSPACES)
    }

    fun openLayoutInspector(correlationHint: com.androidperformancestudio.desktop.InspectorCorrelationHint?) {
        inspectorCorrelationHint = correlationHint
        activate(AppDestination.LAYOUT_INSPECTOR)
    }

    fun openLayoutArchive(path: Path) {
        layoutArchiveRequest = AppArtifactOpenRequest(path, ++nextArtifactOpenRequestId)
        activate(AppDestination.LAYOUT_INSPECTOR)
    }

    fun openSimpleperfSession(path: Path) {
        simpleperfSessionRequest = AppArtifactOpenRequest(path, ++nextArtifactOpenRequestId)
        activate(AppDestination.SIMPLEPERF)
    }

    fun openPerfettoTrace(
        path: Path,
        notice: String? = null,
        timestampNanos: Long? = null,
    ) {
        perfettoTraceFile = path
        perfettoTraceRequestId = ++nextArtifactOpenRequestId
        perfettoTraceNotice = notice
        perfettoTimestampNanos = timestampNanos
        activate(AppDestination.PERFETTO)
    }

    /** Clears the pending Perfetto trace intent once the page is left. */
    fun clearPerfettoTrace() {
        perfettoTraceFile = null
        perfettoTraceNotice = null
        perfettoTimestampNanos = null
    }

    /** Opens the memory profiler and imports [file] (HPROF, or java_hprof when [javaHeap]). */
    fun openMemoryProfiler(
        file: Path,
        javaHeap: Boolean = false,
    ) {
        inspectorCorrelationHint = null
        memoryImportFile = file
        memoryImportRequestId = ++nextArtifactOpenRequestId
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

internal fun AppDestination.hasNativeFeatureMenu(): Boolean =
    when (this) {
        AppDestination.LAYOUT_INSPECTOR,
        AppDestination.SIMPLEPERF,
        AppDestination.PERFETTO,
        AppDestination.MEMORY_PROFILER,
        AppDestination.FRAME_PROFILER,
        AppDestination.STARTUP_PROFILER,
        AppDestination.BATTERY_NETWORK,
        AppDestination.METHOD_RECORDING,
        -> true
        else -> false
    }
