@file:Suppress("LongMethod", "ReturnCount")

package com.androidperformancestudio.methodcapture

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbException
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import com.androidperformancestudio.platform.toolchain.HostCancellationSignal
import com.androidperformancestudio.platform.toolchain.HostCommandResult
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

typealias MethodTraceCaptureProcessRunner = suspend (HostProcessRequest, HostCancellationSignal) -> HostCommandResult

/**
 * Captures a Java/Kotlin method trace from the target process via `am profile start/stop`.
 *
 * Requires a debuggable (or `profileableByShell`) app, or a rooted device. The captured `.trace`
 * file is the ART method-trace format parsed by `ArtTraceParser`. The UI can call [requestStop] to
 * end the capture early (which trips the stop signal and flushes via `am profile stop`).
 */
@Suppress("TooManyFunctions")
class MethodTraceCaptureSession(
    private val adbClient: AdbClient,
) {
    constructor(adbExecutable: Path) : this(DefaultAdbClient(adbExecutable))

    @Volatile
    private var activeStopSignal: HostCancellationSignal? = null

    /** True while a capture is between `am profile start` and `am profile stop`. */
    internal val isRecording: Boolean
        get() = activeStopSignal != null

    /** Ends an in-progress capture early by tripping the active stop signal. */
    fun requestStop() {
        activeStopSignal?.cancel()
    }

    suspend fun capture(
        request: MethodTraceCaptureRequest,
        cancellationSignal: HostCancellationSignal = HostCancellationSignal(),
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

        val stopSignal = HostCancellationSignal()
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
        stopSignal: HostCancellationSignal,
        cancellationSignal: HostCancellationSignal,
    ) {
        val deadlineNanos = System.nanoTime() + durationSeconds * NANOS_PER_SECOND
        while (System.nanoTime() < deadlineNanos && !stopSignal.isCancelled && !cancellationSignal.isCancelled) {
            kotlinx.coroutines.delay(CAPTURE_STOP_POLL_INTERVAL_MS)
        }
    }

    private suspend fun readSdkApiLevel(
        serial: String,
        cancellationSignal: HostCancellationSignal,
    ): Int? =
        try {
            adbClient
                .shell(
                    serial = serial,
                    arguments = listOf("getprop", "ro.build.version.sdk"),
                    timeout = COMMAND_TIMEOUT,
                    isCancellationRequested = cancellationSignal::isCancelled,
                ).stdout
                .trim()
                .toIntOrNull()
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            null
        }

    private suspend fun waitForTraceFile(
        serial: String,
        deviceTracePath: String,
        cancellationSignal: HostCancellationSignal,
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
        cancellationSignal: HostCancellationSignal,
    ): Boolean =
        runCatching {
            adbClient.shell(
                serial = serial,
                arguments = listOf("ls", "-l", deviceTracePath),
                timeout = COMMAND_TIMEOUT,
                isCancellationRequested = cancellationSignal::isCancelled,
            )
        }.isSuccess

    private suspend fun runAdb(
        serial: String,
        arguments: List<String>,
        timeout: kotlin.time.Duration,
        cancellationSignal: HostCancellationSignal,
    ): StudioResult<Unit> =
        try {
            when (arguments.firstOrNull()) {
                "shell" ->
                    adbClient.shell(
                        serial = serial,
                        arguments = arguments.drop(1),
                        timeout = timeout,
                        isCancellationRequested = cancellationSignal::isCancelled,
                    )
                "pull" ->
                    adbClient.pull(
                        serial = serial,
                        remotePath = arguments[1],
                        localPath = Path.of(arguments[2]),
                        timeout = timeout,
                        isCancellationRequested = cancellationSignal::isCancelled,
                    )
                else -> error("Unsupported typed ADB operation: ${arguments.firstOrNull()}")
            }
            StudioResult.Success(Unit)
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (error: AdbException) {
            StudioResult.Failure(adbError(arguments, error))
        }

    private suspend fun cleanup(
        serial: String,
        devicePaths: List<String>,
        cancellationSignal: HostCancellationSignal,
    ): MethodTraceWarning? {
        val result =
            runCatching {
                adbClient.shell(
                    serial = serial,
                    arguments = listOf("rm", "-f") + devicePaths,
                    timeout = COMMAND_TIMEOUT,
                    isCancellationRequested = cancellationSignal::isCancelled,
                )
            }
        return if (result.isSuccess) {
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
        error: RuntimeException,
    ): StudioError {
        val message = error.message.orEmpty()
        return when {
            adbArguments.contains("start") && adbArguments.contains("am") ->
                StudioError(
                    category = ErrorCategory.PROCESS_EXIT,
                    code = "METHOD_TRACE_START_FAILED",
                    message =
                        "Failed to start method tracing; the target process may not be debuggable " +
                            "or profileable. $message",
                    cause = error,
                )
            adbArguments.contains("stop") && adbArguments.contains("am") ->
                StudioError(
                    category = ErrorCategory.PROCESS_EXIT,
                    code = "METHOD_TRACE_STOP_FAILED",
                    message = "Failed to stop method tracing. $message",
                    cause = error,
                )
            adbArguments.contains("pull") ->
                StudioError(
                    category = ErrorCategory.PROCESS_EXIT,
                    code = "METHOD_TRACE_PULL_FAILED",
                    message = "Failed to pull the method trace. $message",
                    cause = error,
                )
            else -> StudioError(ErrorCategory.PROCESS_EXIT, "ADB_COMMAND_FAILED", message, error)
        }
    }

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
        private const val CAPTURE_STOP_POLL_INTERVAL_MS = 100L
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
