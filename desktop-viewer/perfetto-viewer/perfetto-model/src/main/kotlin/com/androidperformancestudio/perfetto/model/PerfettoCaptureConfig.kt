package com.androidperformancestudio.perfetto.model
import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.model.StudioError
import java.nio.file.Path
import java.time.Instant

/**
 * Preset trace configuration templates, mirroring Simpleperf's [SamplingTemplate].
 */
enum class PerfettoTraceTemplate(
    val displayName: String,
    val description: String,
) {
    SYSTEM_OVERVIEW("System Overview", "sched, freq, binder, memory, gfx — full system trace"),
    APP_PERFORMANCE("App Performance", "atrace for target app + sched + binder"),
    GFX_PIPELINE("Graphics Pipeline", "SurfaceFlinger, HWUI, frame timeline, vsync"),
    INPUT_LATENCY("Input Latency", "input dispatcher + app response + binder"),
    MEMORY_PROFILE("Memory Profile", "meminfo, heapprofd, Java heap sampling"),
    CUSTOM("Custom", "User-defined trace config"),
}

enum class PerfettoProbeGroup(
    val displayName: String,
) {
    CPU("CPU"),
    MEMORY("Memory"),
    POWER("Power"),
    GPU("GPU"),
    ANDROID("Android apps & services"),
    NETWORK("Network"),
    STACK_SAMPLING("Stack sampling"),
    PERFETTO_SDK("Perfetto SDK"),
    ADVANCED("Advanced"),
}

/** Android-relevant probes exposed by Perfetto's record page. */
enum class PerfettoProbe(
    val group: PerfettoProbeGroup,
    val displayName: String,
    val minimumAndroidSdk: Int = 0,
    val requiredDataSource: String? = null,
    val requiresTargetPackage: Boolean = false,
) {
    CPU_USAGE(PerfettoProbeGroup.CPU, "Coarse CPU usage", requiredDataSource = "linux.sys_stats"),
    CPU_SCHEDULING(PerfettoProbeGroup.CPU, "Scheduling details", requiredDataSource = "linux.ftrace"),
    CPU_FREQUENCY(PerfettoProbeGroup.CPU, "CPU frequency and idle states", requiredDataSource = "linux.sys_stats"),
    SYSCALLS(PerfettoProbeGroup.CPU, "Syscalls", requiredDataSource = "linux.ftrace"),

    NATIVE_HEAP(
        PerfettoProbeGroup.MEMORY,
        "Native heap profiling",
        minimumAndroidSdk = 29,
        requiredDataSource = "android.heapprofd",
        requiresTargetPackage = true,
    ),
    JAVA_HEAP_DUMP(
        PerfettoProbeGroup.MEMORY,
        "Java heap dumps",
        minimumAndroidSdk = 30,
        requiredDataSource = "android.java_hprof",
        requiresTargetPackage = true,
    ),
    KERNEL_MEMINFO(PerfettoProbeGroup.MEMORY, "Kernel meminfo", requiredDataSource = "linux.sys_stats"),
    VMSTAT(PerfettoProbeGroup.MEMORY, "Virtual memory stats", requiredDataSource = "linux.sys_stats"),
    HIGH_FREQUENCY_MEMORY(PerfettoProbeGroup.MEMORY, "High-frequency memory events", requiredDataSource = "linux.ftrace"),
    LOW_MEMORY_KILLER(PerfettoProbeGroup.MEMORY, "Low memory killer", requiredDataSource = "linux.ftrace"),
    PROCESS_STATS(PerfettoProbeGroup.MEMORY, "Process stats", requiredDataSource = "linux.process_stats"),

    POWER_RAILS(PerfettoProbeGroup.POWER, "Battery drain & power rails", requiredDataSource = "android.power"),
    BOARD_VOLTAGES(PerfettoProbeGroup.POWER, "Board voltages & frequencies", requiredDataSource = "linux.ftrace"),

    GPU_FREQUENCY(PerfettoProbeGroup.GPU, "GPU frequency", requiredDataSource = "linux.ftrace"),
    GPU_MEMORY(PerfettoProbeGroup.GPU, "GPU memory", minimumAndroidSdk = 31, requiredDataSource = "android.gpu.memory"),
    GPU_WORK_PERIOD(PerfettoProbeGroup.GPU, "GPU work period", minimumAndroidSdk = 34, requiredDataSource = "linux.ftrace"),
    GPU_RENDER_STAGES(PerfettoProbeGroup.GPU, "GPU render stages", requiredDataSource = "gpu.renderstages"),
    MALI_FENCE_EVENTS(PerfettoProbeGroup.GPU, "Mali fence events", requiredDataSource = "linux.ftrace"),

    ATRACE(PerfettoProbeGroup.ANDROID, "Atrace userspace annotations", requiredDataSource = "linux.ftrace"),
    ANDROID_LOG(PerfettoProbeGroup.ANDROID, "Event log (logcat)", requiredDataSource = "android.log"),
    FRAME_TIMELINE(
        PerfettoProbeGroup.ANDROID,
        "Frame timeline",
        minimumAndroidSdk = 31,
        requiredDataSource = "android.surfaceflinger.frametimeline",
    ),
    GAME_INTERVENTIONS(
        PerfettoProbeGroup.ANDROID,
        "Game intervention list",
        minimumAndroidSdk = 33,
        requiredDataSource = "android.game_interventions",
    ),
    NETWORK_PACKETS(
        PerfettoProbeGroup.ANDROID,
        "Network packet tracing",
        minimumAndroidSdk = 34,
        requiredDataSource = "android.network_packets",
    ),

    WIFI_NETWORK(PerfettoProbeGroup.NETWORK, "Wi-Fi and network events", requiredDataSource = "linux.ftrace"),
    CALLSTACK_SAMPLING(PerfettoProbeGroup.STACK_SAMPLING, "Callstack sampling", requiredDataSource = "linux.perf"),
    TRACK_EVENTS(PerfettoProbeGroup.PERFETTO_SDK, "Track events", requiredDataSource = "track_event"),
    ADVANCED_FTRACE(PerfettoProbeGroup.ADVANCED, "Kernel tracing (ftrace)", requiredDataSource = "linux.ftrace"),
    PROCESS_THREAD_ASSOCIATION(PerfettoProbeGroup.ADVANCED, "Process and thread association", requiredDataSource = "linux.process_stats"),
}

fun PerfettoTraceTemplate.defaultProbes(): Set<PerfettoProbe> =
    when (this) {
        PerfettoTraceTemplate.SYSTEM_OVERVIEW ->
            setOf(
                PerfettoProbe.CPU_USAGE,
                PerfettoProbe.CPU_SCHEDULING,
                PerfettoProbe.CPU_FREQUENCY,
                PerfettoProbe.KERNEL_MEMINFO,
                PerfettoProbe.VMSTAT,
                PerfettoProbe.PROCESS_STATS,
                PerfettoProbe.ATRACE,
                PerfettoProbe.FRAME_TIMELINE,
            )
        PerfettoTraceTemplate.APP_PERFORMANCE ->
            setOf(
                PerfettoProbe.CPU_USAGE,
                PerfettoProbe.CPU_SCHEDULING,
                PerfettoProbe.PROCESS_STATS,
                PerfettoProbe.ATRACE,
                PerfettoProbe.FRAME_TIMELINE,
            )
        PerfettoTraceTemplate.GFX_PIPELINE ->
            setOf(
                PerfettoProbe.CPU_SCHEDULING,
                PerfettoProbe.CPU_FREQUENCY,
                PerfettoProbe.GPU_FREQUENCY,
                PerfettoProbe.ATRACE,
                PerfettoProbe.FRAME_TIMELINE,
            )
        PerfettoTraceTemplate.INPUT_LATENCY ->
            setOf(PerfettoProbe.CPU_SCHEDULING, PerfettoProbe.ATRACE)
        PerfettoTraceTemplate.MEMORY_PROFILE ->
            setOf(
                PerfettoProbe.NATIVE_HEAP,
                PerfettoProbe.KERNEL_MEMINFO,
                PerfettoProbe.VMSTAT,
                PerfettoProbe.HIGH_FREQUENCY_MEMORY,
                PerfettoProbe.LOW_MEMORY_KILLER,
                PerfettoProbe.PROCESS_STATS,
                PerfettoProbe.ATRACE,
            )
        PerfettoTraceTemplate.CUSTOM -> emptySet()
    }

data class PerfettoDeviceCapabilities(
    val androidSdk: Int,
    val buildType: String = "unknown",
    val dataSourceNames: Set<String> = emptySet(),
    val queryError: String? = null,
) {
    fun unsupportedReason(probe: PerfettoProbe): String? {
        val source = probe.requiredDataSource
        val sourceUnavailable =
            queryError == null && dataSourceNames.isNotEmpty() && source != null && source !in dataSourceNames
        val unsupportedBuild =
            probe == PerfettoProbe.SYSCALLS && buildType != "unknown" && buildType !in setOf("userdebug", "eng")
        return when {
            androidSdk > 0 && androidSdk < probe.minimumAndroidSdk -> "Requires Android ${probe.minimumAndroidSdk}+"
            unsupportedBuild -> "Requires a userdebug or eng build"
            sourceUnavailable -> "$source is unavailable on this device"
            else -> null
        }
    }
}

data class PerfettoCaptureConfig(
    val template: PerfettoTraceTemplate,
    val targetPackage: String? = null,
    val durationSeconds: Int = 10,
    val bufferSizeKb: Int = 32768,
    val additionalCategories: List<String> = emptyList(),
    val customConfigText: String? = null,
    val enabledProbes: Set<PerfettoProbe>? = null,
) {
    init {
        require(durationSeconds in 1..600) { "durationSeconds must be in [1, 600], was $durationSeconds" }
        require(bufferSizeKb in 1024..1048576) { "bufferSizeKb must be in [1024, 1048576], was $bufferSizeKb" }
    }
}

sealed interface PerfettoCaptureState {
    data object Idle : PerfettoCaptureState

    data class Preparing(
        val config: PerfettoCaptureConfig,
    ) : PerfettoCaptureState

    data class Recording(
        val startTime: Instant,
        val pid: Long,
    ) : PerfettoCaptureState

    data class Pulling(
        val bytesTransferred: Long,
        val totalBytes: Long?,
    ) : PerfettoCaptureState

    data class Completed(
        val traceFile: Path,
        val metadata: CaptureMetadata,
    ) : PerfettoCaptureState

    data class Failed(
        val error: StudioError,
    ) : PerfettoCaptureState
}

data class CaptureMetadata(
    val deviceSerial: String,
    val deviceModel: String,
    val androidSdk: Int,
    val capturedAt: Instant,
    val durationNanos: Long,
    val traceFileSizeBytes: Long,
    val config: PerfettoCaptureConfig,
    val command: String,
    val artifact: CaptureArtifact,
)

data class PerfettoDevice(
    val serial: String,
    val model: String,
    val androidSdk: Int = 0,
    val online: Boolean = true,
)
