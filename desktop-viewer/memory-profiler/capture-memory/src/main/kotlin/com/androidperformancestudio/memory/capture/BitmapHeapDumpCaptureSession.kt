@file:Suppress("CyclomaticComplexMethod", "LongMethod", "MagicNumber", "MaxLineLength", "ReturnCount")

package com.androidperformancestudio.memory.capture

import com.androidperformancestudio.memory.model.ProcessMemorySnapshot
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbException
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import com.androidperformancestudio.platform.toolchain.HostCancellationSignal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class BitmapCaptureRequest(
    val sessionId: String,
    val sessionRoot: Path,
    val serial: String,
    val pid: Int,
    val packageName: String,
) {
    init {
        require(sessionId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")))
        require(serial.isNotBlank())
        require(pid > 0)
        require(packageName.isNotBlank())
    }
}

enum class BitmapCaptureStage {
    CheckingDevice,
    DumpingHeap,
    PullingHeap,
    ReadingMemory,
    CleaningUp,
}

data class BitmapCaptureProgress(
    val stage: BitmapCaptureStage,
    val percent: Int,
)

data class BitmapCaptureResult(
    val sessionId: String,
    val sessionDirectory: Path,
    val deviceHprofPath: String,
    val hprofFile: Path,
    val sdkLevel: Int,
    val memorySnapshot: ProcessMemorySnapshot?,
    val warnings: List<MemoryCaptureWarning> = emptyList(),
)

class BitmapHeapDumpCaptureSession(
    private val adbClient: AdbClient,
) {
    constructor(adbExecutable: Path) : this(DefaultAdbClient(adbExecutable))

    suspend fun capture(
        request: BitmapCaptureRequest,
        cancellationSignal: HostCancellationSignal = HostCancellationSignal(),
        onProgress: (BitmapCaptureProgress) -> Unit = {},
    ): StudioResult<BitmapCaptureResult> {
        onProgress(BitmapCaptureProgress(BitmapCaptureStage.CheckingDevice, 5))
        val sdkResult = runAdb(request.serial, listOf("shell", "getprop", "ro.build.version.sdk"), 30.seconds, cancellationSignal)
        if (sdkResult is StudioResult.Failure) return sdkResult
        val sdkText = (sdkResult as StudioResult.Success).value.trim()
        val sdkLevel =
            sdkText.toIntOrNull()
                ?: return failure("BITMAP_SDK_UNKNOWN", "Unable to read Android API level: $sdkText")
        if (sdkLevel < MINIMUM_BITMAP_DUMP_API) {
            return failure(
                "BITMAP_DUMP_UNSUPPORTED_API",
                "Bitmap dump requires Android API $MINIMUM_BITMAP_DUMP_API or newer; connected device is API $sdkLevel.",
            )
        }

        val sessionDirectory = request.sessionRoot.resolve(request.sessionId)
        Files.createDirectories(sessionDirectory)
        val hprofFile = sessionDirectory.resolve("bitmap.raw.hprof")
        val safePackage = request.packageName.replace(Regex("[^A-Za-z0-9_.]"), "_")
        val deviceHprofPath = "/data/local/tmp/bitmap-$safePackage-${request.sessionId}.hprof"

        onProgress(BitmapCaptureProgress(BitmapCaptureStage.DumpingHeap, 15))
        val dumpResult =
            runAdb(
                request.serial,
                listOf("shell", "am", "dumpheap", "-b", "png", request.pid.toString(), deviceHprofPath),
                10.minutes,
                cancellationSignal,
            )
        if (dumpResult is StudioResult.Failure) return dumpResult.withCode("BITMAP_DUMPHEAP_FAILED")

        onProgress(BitmapCaptureProgress(BitmapCaptureStage.PullingHeap, 55))
        val pullResult =
            runAdb(
                request.serial,
                listOf("pull", deviceHprofPath, hprofFile.toString()),
                10.minutes,
                cancellationSignal,
            )
        if (pullResult is StudioResult.Failure) {
            cleanup(request.serial, deviceHprofPath, cancellationSignal)
            return pullResult.withCode("BITMAP_PULL_FAILED")
        }
        if (!Files.isRegularFile(hprofFile) || Files.size(hprofFile) == 0L) {
            cleanup(request.serial, deviceHprofPath, cancellationSignal)
            return failure("BITMAP_PULL_EMPTY", "Bitmap HPROF pull completed but the local file is missing or empty.")
        }

        onProgress(BitmapCaptureProgress(BitmapCaptureStage.ReadingMemory, 75))
        val warnings = mutableListOf<MemoryCaptureWarning>()
        val memorySnapshot =
            when (
                val meminfo =
                    runAdb(
                        request.serial,
                        listOf("shell", "dumpsys", "meminfo", request.pid.toString()),
                        30.seconds,
                        cancellationSignal,
                    )
            ) {
                is StudioResult.Success -> parseProcessMemory(meminfo.value)
                is StudioResult.Failure -> null
            }
        if (memorySnapshot == null) {
            warnings += MemoryCaptureWarning("MEMINFO_UNAVAILABLE", "Process memory snapshot was unavailable.")
        }

        onProgress(BitmapCaptureProgress(BitmapCaptureStage.CleaningUp, 90))
        cleanup(request.serial, deviceHprofPath, cancellationSignal)?.let(warnings::add)
        return StudioResult.Success(
            BitmapCaptureResult(
                sessionId = request.sessionId,
                sessionDirectory = sessionDirectory,
                deviceHprofPath = deviceHprofPath,
                hprofFile = hprofFile,
                sdkLevel = sdkLevel,
                memorySnapshot = memorySnapshot,
                warnings = warnings,
            ),
        )
    }

    private suspend fun runAdb(
        serial: String,
        arguments: List<String>,
        timeout: kotlin.time.Duration,
        cancellationSignal: HostCancellationSignal,
    ): StudioResult<String> =
        try {
            val output =
                when (arguments.firstOrNull()) {
                    "shell" ->
                        adbClient
                            .shell(
                                serial = serial,
                                arguments = arguments.drop(1),
                                timeout = timeout,
                                isCancellationRequested = cancellationSignal::isCancelled,
                            ).stdout
                    "pull" ->
                        adbClient
                            .pull(
                                serial = serial,
                                remotePath = arguments[1],
                                localPath = Path.of(arguments[2]),
                                timeout = timeout,
                                isCancellationRequested = cancellationSignal::isCancelled,
                            ).stdout
                    else -> error("Unsupported typed ADB operation: ${arguments.firstOrNull()}")
                }
            StudioResult.Success(output.trim())
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (error: AdbException) {
            StudioResult.Failure(
                StudioError(
                    category = com.androidperformancestudio.model.ErrorCategory.PROCESS_EXIT,
                    code = "ADB_COMMAND_FAILED",
                    message = error.message.orEmpty(),
                    cause = error,
                ),
            )
        }

    private suspend fun cleanup(
        serial: String,
        devicePath: String,
        cancellationSignal: HostCancellationSignal,
    ): MemoryCaptureWarning? =
        when (runAdb(serial, listOf("shell", "rm", "-f", devicePath), 30.seconds, cancellationSignal)) {
            is StudioResult.Success -> null
            is StudioResult.Failure ->
                MemoryCaptureWarning(
                    "DEVICE_CLEANUP_FAILED",
                    "Failed to remove temporary device bitmap dump $devicePath.",
                )
        }

    private fun parseProcessMemory(output: String): ProcessMemorySnapshot? {
        val values = mutableMapOf<String, Long>()
        output.lineSequence().forEach { line ->
            val columns = line.trim().split(Regex("\\s+"))
            when {
                columns.size >= 3 && columns[0] == "Java" && columns[1] == "Heap:" ->
                    columns[2].toLongOrNull()?.let { values["java"] = it * KIBIBYTE }
                columns.size >= 3 && columns[0] == "Native" && columns[1] == "Heap:" ->
                    columns[2].toLongOrNull()?.let { values["native"] = it * KIBIBYTE }
                columns.size >= 3 && columns[0] == "TOTAL" && columns[1] == "PSS:" ->
                    columns[2].toLongOrNull()?.let { values["total"] = it * KIBIBYTE }
            }
        }
        return ProcessMemorySnapshot(
            totalPssBytes = values["total"] ?: return null,
            javaHeapPssBytes = values["java"] ?: return null,
            nativeHeapPssBytes = values["native"] ?: return null,
        )
    }

    private fun StudioResult.Failure.withCode(code: String): StudioResult.Failure = StudioResult.Failure(error.copy(code = code))

    private fun failure(
        code: String,
        message: String,
    ): StudioResult.Failure =
        StudioResult.Failure(
            StudioError(
                category = com.androidperformancestudio.model.ErrorCategory.DATA_VALIDATION,
                code = code,
                message = message,
            ),
        )

    companion object {
        const val MINIMUM_BITMAP_DUMP_API: Int = 35
        private const val KIBIBYTE = 1024L
    }
}
