package com.androidperformancestudio.memory.capture

import com.androidperformancestudio.adb.AndroidDevicePropertyClient
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbException
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import com.androidperformancestudio.platform.toolchain.HostCancellationSignal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val MIN_DURATION_MS = 1_000L
private const val MAX_DURATION_MS = 60_000L

/**
 * Captures the raw Perfetto heapprofd `com.android.art` sampled-allocation stream.
 *
 * This is Android 12+ ART *sampling*, not Studio's Full JVMTI allocation recorder. A nonempty
 * trace proves artifact transfer only: no allocation rows or stack coverage are asserted until
 * Trace Processor verifies its contents. ART samples do not record object deletion or GC events.
 * See https://perfetto.dev/docs/data-sources/native-heap-profiler#art-allocation-profiling.
 */
class SampledArtAllocationCaptureSession(
    private val adbClient: AdbClient,
) {
    constructor(adbExecutable: Path) : this(DefaultAdbClient(adbExecutable))

    @Suppress("LongMethod", "ReturnCount")
    suspend fun capture(
        request: SampledArtAllocationCaptureRequest,
        cancellationSignal: HostCancellationSignal = HostCancellationSignal(),
    ): StudioResult<SampledArtAllocationCaptureResult> {
        val sdkLevel =
            (
                AndroidDevicePropertyClient(adbClient).sdkInt(
                    serial = request.serial,
                    timeout = 30.seconds,
                    isCancellationRequested = cancellationSignal::isCancelled,
                ) as? StudioResult.Success
            )?.value ?: return validationFailure("ART_SAMPLED_SDK_UNKNOWN", "Unable to read Android API level.")
        if (sdkLevel < MINIMUM_ART_SAMPLING_API) {
            return validationFailure(
                "ART_SAMPLED_UNSUPPORTED_API",
                "ART allocation sampling requires Android API $MINIMUM_ART_SAMPLING_API or newer; " +
                    "device is API $sdkLevel.",
            )
        }

        val sessionDirectory = request.sessionRoot.resolve(request.sessionId)
        Files.createDirectories(sessionDirectory)
        val localConfig = sessionDirectory.resolve("${request.sessionId}.art-sampled.cfg")
        val partialTrace = sessionDirectory.resolve("${request.sessionId}.art-sampled.pb.partial")
        val traceFile = sessionDirectory.resolve("${request.sessionId}.art-sampled.pb")
        val devicePaths =
            DevicePaths(
                config = "/data/misc/perfetto-configs/art-sampled-${request.sessionId}.cfg",
                trace = "/data/misc/perfetto-traces/art-sampled-${request.sessionId}.pb",
            )
        Files.writeString(localConfig, configFor(request))
        Files.deleteIfExists(partialTrace)

        var cleanupWarning: MemoryCaptureWarning? = null
        val transfer =
            try {
                transferTrace(request, localConfig, partialTrace, devicePaths, cancellationSignal)
            } catch (cancelled: java.util.concurrent.CancellationException) {
                Files.deleteIfExists(partialTrace)
                throw cancelled
            } finally {
                cleanupWarning = cleanupDevice(request.serial, devicePaths)
            }
        if (transfer is StudioResult.Failure) {
            Files.deleteIfExists(partialTrace)
            return transfer
        }
        if (!Files.isRegularFile(partialTrace) || Files.size(partialTrace) == 0L) {
            Files.deleteIfExists(partialTrace)
            return validationFailure("ART_SAMPLED_EMPTY_TRACE", "ART sampling produced an empty Perfetto trace.")
        }
        Files.move(partialTrace, traceFile, StandardCopyOption.REPLACE_EXISTING)
        return StudioResult.Success(
            SampledArtAllocationCaptureResult(
                sessionId = request.sessionId,
                serial = request.serial,
                pid = request.pid,
                packageName = request.packageName,
                sdkLevel = sdkLevel,
                samplingIntervalBytes = request.samplingIntervalBytes,
                durationMs = request.durationMs,
                traceFile = traceFile,
                configFile = localConfig,
                warnings = listOfNotNull(cleanupWarning),
            ),
        )
    }

    @Suppress("ReturnCount")
    private suspend fun transferTrace(
        request: SampledArtAllocationCaptureRequest,
        localConfig: Path,
        partialTrace: Path,
        devicePaths: DevicePaths,
        signal: HostCancellationSignal,
    ): StudioResult<Unit> {
        val push =
            runAdb(AdbStage.PUSH) {
                adbClient.push(
                    request.serial,
                    localConfig,
                    devicePaths.config,
                    30.seconds,
                    isCancellationRequested = signal::isCancelled,
                )
            }
        if (push is StudioResult.Failure) return push
        val record =
            runAdb(AdbStage.RECORD) {
                adbClient.shell(
                    request.serial,
                    listOf("perfetto", "--txt", "-c", devicePaths.config, "-o", devicePaths.trace),
                    request.durationMs.milliseconds + 20.seconds,
                    isCancellationRequested = signal::isCancelled,
                )
            }
        if (record is StudioResult.Failure) return record
        return runAdb(AdbStage.PULL) {
            adbClient.pull(
                request.serial,
                devicePaths.trace,
                partialTrace,
                30.seconds,
                isCancellationRequested = signal::isCancelled,
            )
        }
    }

    private fun configFor(request: SampledArtAllocationCaptureRequest): String =
        """
        buffers { size_kb: 8192 }
        data_sources {
          config {
            name: "android.heapprofd"
            target_buffer: 0
            heapprofd_config {
              sampling_interval_bytes: ${request.samplingIntervalBytes}
              pid: ${request.pid}
              heaps: "com.android.art"
            }
          }
        }
        duration_ms: ${request.durationMs}
        """.trimIndent()

    private suspend fun runAdb(
        stage: AdbStage,
        operation: suspend () -> Unit,
    ): StudioResult<Unit> =
        try {
            operation()
            StudioResult.Success(Unit)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (error: AdbException) {
            StudioResult.Failure(
                StudioError(
                    category = ErrorCategory.PROCESS_EXIT,
                    code = stage.errorCode,
                    message = "ART sampled allocation ${stage.label} failed: ${error.message.orEmpty()}",
                    cause = error,
                ),
            )
        }

    private suspend fun cleanupDevice(
        serial: String,
        paths: DevicePaths,
    ): MemoryCaptureWarning? =
        try {
            // Cleanup must still run after the caller cancels the recording operation.
            adbClient.shell(serial, listOf("rm", "-f", paths.config, paths.trace), 30.seconds)
            null
        } catch (error: AdbException) {
            MemoryCaptureWarning("ART_SAMPLED_DEVICE_CLEANUP_FAILED", error.message.orEmpty())
        }

    private fun validationFailure(
        code: String,
        message: String,
    ): StudioResult.Failure = StudioResult.Failure(StudioError(ErrorCategory.DATA_VALIDATION, code, message))

    private enum class AdbStage(
        val errorCode: String,
        val label: String,
    ) {
        PUSH("ART_SAMPLED_CONFIG_PUSH_FAILED", "config push"),
        RECORD("ART_SAMPLED_RECORD_FAILED", "recording"),
        PULL("ART_SAMPLED_PULL_FAILED", "trace pull"),
    }

    private data class DevicePaths(
        val config: String,
        val trace: String,
    )

    companion object {
        const val MINIMUM_ART_SAMPLING_API: Int = 31
    }
}

data class SampledArtAllocationCaptureRequest(
    val sessionId: String,
    val sessionRoot: Path,
    val serial: String,
    val pid: Int,
    val packageName: String,
    val samplingIntervalBytes: Int = 4096,
    val durationMs: Long = 10_000L,
) {
    init {
        require(sessionId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) { "Unsupported sessionId characters" }
        require(serial.isNotBlank()) { "serial must not be blank" }
        require(pid > 0) { "pid must be positive" }
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(samplingIntervalBytes > 0) { "samplingIntervalBytes must be positive" }
        require(durationMs in MIN_DURATION_MS..MAX_DURATION_MS) { "durationMs must be between 1 and 60 seconds" }
    }
}

/** Metadata for a raw trace only; it does not claim that allocation samples were observed. */
data class SampledArtAllocationCaptureResult(
    val sessionId: String,
    val serial: String,
    val pid: Int,
    val packageName: String,
    val sdkLevel: Int,
    val samplingIntervalBytes: Int,
    val durationMs: Long,
    val traceFile: Path,
    val configFile: Path,
    val warnings: List<MemoryCaptureWarning>,
) {
    val source: String = "perfetto/android.heapprofd/com.android.art"
    val hasDeallocationEvents: Boolean = false
    val hasGcEvents: Boolean = false
}
