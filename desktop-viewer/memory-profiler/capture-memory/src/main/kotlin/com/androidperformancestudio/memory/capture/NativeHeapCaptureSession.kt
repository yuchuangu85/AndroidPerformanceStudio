@file:Suppress("LongMethod", "MaxLineLength", "ReturnCount")

package com.androidperformancestudio.memory.capture

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessCancellationSignal
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Captures a Native heap profile from the target process via heapprofd (Perfetto).
 *
 * Requires Android 10+ (API 29+); heapprofd is available on debuggable builds. The captured trace
 * is the raw Perfetto `.pb` stream, which can be exported or opened in Perfetto / Android Studio.
 * Any in-app allocation summary is best-effort; the raw trace remains the authoritative artifact.
 */
class NativeHeapCaptureSession(
    private val adbExecutable: Path,
    private val processRunner: MemoryCaptureProcessRunner = { request, signal ->
        JvmProcessRunner().run(request, signal)
    },
) {
    suspend fun capture(
        request: NativeHeapCaptureRequest,
        cancellationSignal: ProcessCancellationSignal = ProcessCancellationSignal(),
    ): StudioResult<NativeHeapCaptureResult> {
        val sdkLevel = readSdkApiLevel(request.serial, cancellationSignal)
        if (sdkLevel == null) {
            return failure("NATIVE_HEAP_SDK_UNKNOWN", "Unable to read Android API level.")
        }
        if (sdkLevel < MINIMUM_HEAPROFD_API) {
            return failure(
                "NATIVE_HEAP_UNSUPPORTED_API",
                "Native heap capture requires Android API $MINIMUM_HEAPROFD_API or newer; connected device is API $sdkLevel.",
            )
        }

        val sessionDirectory = request.sessionRoot.resolve(request.sessionId)
        Files.createDirectories(sessionDirectory)
        val traceFile = sessionDirectory.resolve("${request.sessionId}.native-heap.pb")
        val deviceConfigPath = "/data/local/tmp/heapprofd-${request.sessionId}.cfg"
        val deviceTracePath = "/data/local/tmp/heapprofd-${request.sessionId}.pb"
        val localConfigPath = sessionDirectory.resolve("${request.sessionId}.cfg")
        Files.writeString(localConfigPath, heapprofdConfig(request.pid))

        val pushResult =
            runAdb(
                request.serial,
                listOf("push", localConfigPath.toString(), deviceConfigPath),
                PUSH_TIMEOUT,
                cancellationSignal,
            )
        if (pushResult is StudioResult.Failure) {
            return pushResult
        }

        val captureResult =
            runAdb(
                request.serial,
                listOf("shell", "perfetto", "--txt", "-c", deviceConfigPath, "-o", deviceTracePath),
                CAPTURE_TIMEOUT,
                cancellationSignal,
            )
        if (captureResult is StudioResult.Failure) {
            cleanup(request.serial, listOf(deviceConfigPath, deviceTracePath), cancellationSignal)
            return captureResult
        }

        val pullResult =
            runAdb(
                request.serial,
                listOf("pull", deviceTracePath, traceFile.toString()),
                PULL_TIMEOUT,
                cancellationSignal,
            )
        if (pullResult is StudioResult.Failure) {
            cleanup(request.serial, listOf(deviceConfigPath, deviceTracePath), cancellationSignal)
            return pullResult
        }
        if (!Files.isRegularFile(traceFile) || Files.size(traceFile) == 0L) {
            cleanup(request.serial, listOf(deviceConfigPath, deviceTracePath), cancellationSignal)
            return failure("NATIVE_HEAP_EMPTY_TRACE", "Native heap capture produced an empty trace.")
        }

        val warnings = mutableListOf<MemoryCaptureWarning>()
        cleanup(request.serial, listOf(deviceConfigPath, deviceTracePath), cancellationSignal)?.let(warnings::add)
        return StudioResult.Success(
            NativeHeapCaptureResult(
                sessionId = request.sessionId,
                sessionDirectory = sessionDirectory,
                deviceTracePath = deviceTracePath,
                traceFile = traceFile,
                deviceSdkApiLevel = sdkLevel,
                warnings = warnings,
            ),
        )
    }

    private fun heapprofdConfig(pid: Int): String =
        """
        buffers {
          size_kb: 8192
        }
        data_sources {
          config {
            name: "android.heapprofd"
            target_buffer: 0
            heapprofd_config {
              sampling_interval_bytes: 4096
              pid: $pid
            }
          }
        }
        duration_ms: ${CAPTURE_DURATION_MS}
        """.trimIndent()

    private suspend fun readSdkApiLevel(
        serial: String,
        cancellationSignal: ProcessCancellationSignal,
    ): Int? {
        val result =
            processRunner(
                ProcessRequest(
                    executable = adbExecutable,
                    arguments = listOf("-s", serial, "shell", "getprop", "ro.build.version.sdk"),
                    timeout = 30.seconds,
                ),
                cancellationSignal,
            )
        return when (result) {
            is ProcessRunResult.Completed ->
                result.output.stdout.text
                    .trim()
                    .toIntOrNull()
            is ProcessRunResult.Failed -> null
        }
    }

    private suspend fun runAdb(
        serial: String,
        arguments: List<String>,
        timeout: kotlin.time.Duration,
        cancellationSignal: ProcessCancellationSignal,
    ): StudioResult<Unit> {
        val result =
            processRunner(
                ProcessRequest(
                    executable = adbExecutable,
                    arguments = listOf("-s", serial) + arguments,
                    timeout = timeout,
                ),
                cancellationSignal,
            )
        return when (result) {
            is ProcessRunResult.Completed -> StudioResult.Success(Unit)
            is ProcessRunResult.Failed -> StudioResult.Failure(adbError(arguments, result))
        }
    }

    private suspend fun cleanup(
        serial: String,
        devicePaths: List<String>,
        cancellationSignal: ProcessCancellationSignal,
    ): MemoryCaptureWarning? {
        val result =
            processRunner(
                ProcessRequest(
                    executable = adbExecutable,
                    arguments = listOf("-s", serial, "shell", "rm", "-f") + devicePaths,
                    timeout = 30.seconds,
                ),
                cancellationSignal,
            )
        return if (result is ProcessRunResult.Completed) {
            null
        } else {
            MemoryCaptureWarning(
                code = "DEVICE_CLEANUP_FAILED",
                message = "Failed to remove temporary device native heap files ${devicePaths.joinToString()}.",
            )
        }
    }

    private fun adbError(
        adbArguments: List<String>,
        result: ProcessRunResult.Failed,
    ): StudioError {
        val message = combinedOutput(result).ifBlank { result.error.message }
        return when {
            adbArguments.contains("push") ->
                StudioError(
                    category = result.error.category,
                    code = "NATIVE_HEAP_CONFIG_PUSH_FAILED",
                    message = "Failed to push the heapprofd config to the device. $message",
                    cause = result.error.cause,
                )
            adbArguments.contains("perfetto") ->
                StudioError(
                    category = result.error.category,
                    code = "NATIVE_HEAP_CAPTURE_FAILED",
                    message =
                        "perfetto/heapprofd capture failed; the target process may not be debuggable " +
                            "or the device may lack heapprofd support. $message",
                    cause = result.error.cause,
                )
            adbArguments.contains("pull") ->
                StudioError(
                    category = result.error.category,
                    code = "NATIVE_HEAP_PULL_FAILED",
                    message = "Failed to pull the native heap trace. $message",
                    cause = result.error.cause,
                )
            else -> result.error
        }
    }

    private fun combinedOutput(result: ProcessRunResult.Failed): String =
        listOfNotNull(result.output?.stderr?.text, result.output?.stdout?.text)
            .joinToString(separator = "\n")
            .trim()

    private fun failure(
        code: String,
        message: String,
    ): StudioResult.Failure =
        StudioResult.Failure(
            StudioError(
                category = ErrorCategory.DATA_VALIDATION,
                code = code,
                message = message,
            ),
        )

    companion object {
        const val MINIMUM_HEAPROFD_API: Int = 29
        private const val CAPTURE_DURATION_MS = 10_000L
        private val PUSH_TIMEOUT = 30.seconds
        private val PULL_TIMEOUT = 30.seconds
        private val CAPTURE_TIMEOUT = CAPTURE_DURATION_MS.milliseconds + 20.seconds
    }
}

data class NativeHeapCaptureRequest(
    val sessionId: String,
    val sessionRoot: Path,
    val serial: String,
    val pid: Int,
    val packageName: String,
) {
    init {
        require(sessionId.matches(SESSION_ID_PATTERN)) { "sessionId contains unsupported characters" }
        require(serial.isNotBlank()) { "serial must not be blank" }
        require(pid > 0) { "pid must be positive" }
        require(packageName.isNotBlank()) { "packageName must not be blank" }
    }

    companion object {
        private val SESSION_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}

data class NativeHeapCaptureResult(
    val sessionId: String,
    val sessionDirectory: Path,
    val deviceTracePath: String,
    val traceFile: Path,
    val deviceSdkApiLevel: Int,
    val warnings: List<MemoryCaptureWarning> = emptyList(),
)
