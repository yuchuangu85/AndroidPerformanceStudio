@file:Suppress("LongMethod", "ReturnCount")

package com.androidperformancestudio.methodcapture

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessCancellationSignal
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

typealias MethodTraceCaptureProcessRunner = suspend (ProcessRequest, ProcessCancellationSignal) -> ProcessRunResult

/**
 * Captures a Java/Kotlin method trace from the target process via `am profile start/stop`.
 *
 * Requires a debuggable (or `profileableByShell`) app, or a rooted device. The captured `.trace`
 * file is the ART method-trace format parsed by `ArtTraceParser`. The UI can call [requestStop] to
 * end the capture early (which trips the stop signal and flushes via `am profile stop`).
 */
class MethodTraceCaptureSession(
    private val adbExecutable: Path,
    private val processRunner: MethodTraceCaptureProcessRunner = { request, signal ->
        JvmProcessRunner().run(request, signal)
    },
) {
    @Volatile
    private var activeStopSignal: ProcessCancellationSignal? = null

    /** True while a capture is between `am profile start` and `am profile stop`. */
    internal val isRecording: Boolean
        get() = activeStopSignal != null

    /** Ends an in-progress capture early by tripping the active stop signal. */
    fun requestStop() {
        activeStopSignal?.cancel()
    }

    suspend fun capture(
        request: MethodTraceCaptureRequest,
        cancellationSignal: ProcessCancellationSignal = ProcessCancellationSignal(),
    ): StudioResult<MethodTraceCaptureResult> {
        val sdkLevel = readSdkApiLevel(request.serial, cancellationSignal)
        if (sdkLevel == null) {
            return failure("METHOD_TRACE_SDK_UNKNOWN", "Unable to read Android API level.")
        }
        if (sdkLevel < MINIMUM_API) {
            return failure(
                "METHOD_TRACE_UNSUPPORTED_API",
                "Method tracing requires Android API $MINIMUM_API or newer; connected device is API $sdkLevel.",
            )
        }

        val sessionDirectory = request.sessionRoot.resolve(request.sessionId)
        Files.createDirectories(sessionDirectory)
        val traceFile = sessionDirectory.resolve("${request.sessionId}.trace")
        val deviceTracePath = "/data/local/tmp/aps-${request.sessionId}.trace"

        val startResult =
            runAdb(
                request.serial,
                listOf("shell", "am", "profile", "start", request.pid.toString(), deviceTracePath),
                START_TIMEOUT,
                cancellationSignal,
            )
        if (startResult is StudioResult.Failure) {
            cleanup(request.serial, listOf(deviceTracePath), cancellationSignal)
            return startResult
        }

        val stopSignal = ProcessCancellationSignal()
        activeStopSignal = stopSignal
        try {
            awaitCaptureDuration(request.durationSeconds, stopSignal, cancellationSignal)
        } finally {
            activeStopSignal = null
        }

        // `am profile stop` flushes the buffered trace to the device file.
        val stopResult =
            runAdb(
                request.serial,
                listOf("shell", "am", "profile", "stop", request.pid.toString()),
                STOP_TIMEOUT,
                cancellationSignal,
            )
        if (stopResult is StudioResult.Failure) {
            cleanup(request.serial, listOf(deviceTracePath), cancellationSignal)
            return stopResult
        }

        if (!waitForTraceFile(request.serial, deviceTracePath, cancellationSignal)) {
            cleanup(request.serial, listOf(deviceTracePath), cancellationSignal)
            return failure("METHOD_TRACE_EMPTY", "Method trace was not produced on the device.")
        }

        val pullResult =
            runAdb(
                request.serial,
                listOf("pull", deviceTracePath, traceFile.toString()),
                PULL_TIMEOUT,
                cancellationSignal,
            )
        if (pullResult is StudioResult.Failure) {
            cleanup(request.serial, listOf(deviceTracePath), cancellationSignal)
            return pullResult
        }
        if (!Files.isRegularFile(traceFile) || Files.size(traceFile) == 0L) {
            cleanup(request.serial, listOf(deviceTracePath), cancellationSignal)
            return failure("METHOD_TRACE_EMPTY", "Method trace capture produced an empty file.")
        }

        val warnings = mutableListOf<MethodTraceWarning>()
        cleanup(request.serial, listOf(deviceTracePath), cancellationSignal)?.let(warnings::add)
        return StudioResult.Success(
            MethodTraceCaptureResult(
                sessionId = request.sessionId,
                sessionDirectory = sessionDirectory,
                traceFile = traceFile,
                deviceSdkApiLevel = sdkLevel,
                warnings = warnings,
            ),
        )
    }

    private suspend fun awaitCaptureDuration(
        durationSeconds: Int,
        stopSignal: ProcessCancellationSignal,
        cancellationSignal: ProcessCancellationSignal,
    ) {
        val deadlineNanos = System.nanoTime() + durationSeconds * NANOS_PER_SECOND
        while (System.nanoTime() < deadlineNanos && !stopSignal.isCancelled && !cancellationSignal.isCancelled) {
            kotlinx.coroutines.delay(100)
        }
    }

    private suspend fun readSdkApiLevel(
        serial: String,
        cancellationSignal: ProcessCancellationSignal,
    ): Int? {
        val result =
            processRunner(
                ProcessRequest(
                    executable = adbExecutable,
                    arguments = listOf("-s", serial, "shell", "getprop", "ro.build.version.sdk"),
                    timeout = COMMAND_TIMEOUT,
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

    private suspend fun waitForTraceFile(
        serial: String,
        deviceTracePath: String,
        cancellationSignal: ProcessCancellationSignal,
    ): Boolean {
        repeat(FILE_POLL_ATTEMPTS) {
            if (traceFileExists(serial, deviceTracePath, cancellationSignal)) return true
            kotlinx.coroutines.delay(FILE_POLL_INTERVAL_MS)
        }
        return false
    }

    private suspend fun traceFileExists(
        serial: String,
        deviceTracePath: String,
        cancellationSignal: ProcessCancellationSignal,
    ): Boolean {
        val result =
            processRunner(
                ProcessRequest(
                    executable = adbExecutable,
                    arguments = listOf("-s", serial, "shell", "ls", "-l", deviceTracePath),
                    timeout = COMMAND_TIMEOUT,
                ),
                cancellationSignal,
            )
        return result is ProcessRunResult.Completed
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
    ): MethodTraceWarning? {
        val result =
            processRunner(
                ProcessRequest(
                    executable = adbExecutable,
                    arguments = listOf("-s", serial, "shell", "rm", "-f") + devicePaths,
                    timeout = COMMAND_TIMEOUT,
                ),
                cancellationSignal,
            )
        return if (result is ProcessRunResult.Completed) {
            null
        } else {
            MethodTraceWarning(
                code = "DEVICE_CLEANUP_FAILED",
                message = "Failed to remove temporary device method-trace files ${devicePaths.joinToString()}.",
            )
        }
    }

    private fun adbError(
        adbArguments: List<String>,
        result: ProcessRunResult.Failed,
    ): StudioError {
        val message = combinedOutput(result).ifBlank { result.error.message }
        return when {
            adbArguments.contains("start") && adbArguments.contains("am") ->
                StudioError(
                    category = result.error.category,
                    code = "METHOD_TRACE_START_FAILED",
                    message =
                        "Failed to start method tracing; the target process may not be debuggable " +
                            "or profileable. $message",
                    cause = result.error.cause,
                )
            adbArguments.contains("stop") && adbArguments.contains("am") ->
                StudioError(
                    category = result.error.category,
                    code = "METHOD_TRACE_STOP_FAILED",
                    message = "Failed to stop method tracing. $message",
                    cause = result.error.cause,
                )
            adbArguments.contains("pull") ->
                StudioError(
                    category = result.error.category,
                    code = "METHOD_TRACE_PULL_FAILED",
                    message = "Failed to pull the method trace. $message",
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
        const val MINIMUM_API: Int = 21
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val FILE_POLL_ATTEMPTS = 20
        private const val FILE_POLL_INTERVAL_MS = 250L
        private val START_TIMEOUT = 30.seconds
        private val STOP_TIMEOUT = 30.seconds
        private val PULL_TIMEOUT = 30.seconds
        private val COMMAND_TIMEOUT = 30.seconds
    }
}

data class MethodTraceCaptureRequest(
    val sessionId: String,
    val sessionRoot: Path,
    val serial: String,
    val pid: Int,
    val packageName: String,
    val durationSeconds: Int = 10,
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

data class MethodTraceCaptureResult(
    val sessionId: String,
    val sessionDirectory: Path,
    val traceFile: Path,
    val deviceSdkApiLevel: Int,
    val warnings: List<MethodTraceWarning> = emptyList(),
)

data class MethodTraceWarning(
    val code: String,
    val message: String,
)
