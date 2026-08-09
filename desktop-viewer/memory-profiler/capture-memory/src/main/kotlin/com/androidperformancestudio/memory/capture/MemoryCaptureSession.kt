package com.androidperformancestudio.memory.capture

import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbException
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import com.androidperformancestudio.platform.toolchain.HostCancellationSignal
import com.androidperformancestudio.platform.toolchain.HostCommandResult
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.StudioHostProcessExecutor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

typealias MemoryHostProcessRunner = suspend (HostProcessRequest, HostCancellationSignal) -> HostCommandResult

private const val MISSING_HPROF_CONV_MESSAGE =
    "SDK Platform Tools hprof-conv was not found; raw Android HPROF was kept. " +
        "Install SDK Platform Tools or import a standard Java HPROF for analysis."

private const val FAILED_HPROF_CONV_MESSAGE =
    "hprof-conv could not convert this dump; the raw Android HPROF was kept for the built-in parser."

data class MemoryCaptureRequest(
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

data class MemoryCaptureResult(
    val sessionId: String,
    val sessionDirectory: Path,
    val deviceHprofPath: String,
    val rawHprofFile: Path,
    val convertedHprofFile: Path?,
    val deviceSdkApiLevel: Int? = null,
    val conversionSkipped: Boolean = false,
    val warnings: List<MemoryCaptureWarning> = emptyList(),
)

data class MemoryCaptureWarning(
    val code: String,
    val message: String,
)

class AndroidSdkHprofConvLocator(
    private val environment: Map<String, String> = System.getenv(),
    private val defaultSdkRoot: Path? = defaultSdkRoot(),
) {
    fun locate(): Path? =
        sdkRoots()
            .map { it.resolve("platform-tools").resolve(executableName()) }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }

    private fun sdkRoots(): List<Path> =
        buildList {
            environment["ANDROID_HOME"]?.takeIf(String::isNotBlank)?.let { add(Path.of(it)) }
            environment["ANDROID_SDK_ROOT"]?.takeIf(String::isNotBlank)?.let { add(Path.of(it)) }
            defaultSdkRoot?.let { add(it) }
        }.distinct()

    private fun executableName(): String = if (isWindows()) "hprof-conv.exe" else "hprof-conv"

    private fun isWindows(): Boolean = System.getProperty("os.name").contains("Windows", ignoreCase = true)

    companion object {
        private fun defaultSdkRoot(): Path? {
            val userHome = System.getProperty("user.home")?.takeIf(String::isNotBlank) ?: return null
            return when {
                System.getProperty("os.name").contains("Mac", ignoreCase = true) ->
                    Path.of(userHome, "Library", "Android", "sdk")
                System.getProperty("os.name").contains("Windows", ignoreCase = true) ->
                    System.getenv("LOCALAPPDATA")?.let { Path.of(it, "Android", "Sdk") }
                else -> Path.of(userHome, "Android", "Sdk")
            }
        }
    }
}

class MemoryHeapDumpCaptureSession(
    private val adbClient: AdbClient,
    private val hprofConvLocator: AndroidSdkHprofConvLocator = AndroidSdkHprofConvLocator(),
    private val hostProcessRunner: MemoryHostProcessRunner = { request, signal ->
        StudioHostProcessExecutor().run(request, signal)
    },
) {
    constructor(
        adbExecutable: Path,
        hprofConvLocator: AndroidSdkHprofConvLocator = AndroidSdkHprofConvLocator(),
        hostProcessRunner: MemoryHostProcessRunner = { request, signal ->
            StudioHostProcessExecutor().run(request, signal)
        },
    ) : this(DefaultAdbClient(adbExecutable), hprofConvLocator, hostProcessRunner)

    @Suppress("ReturnCount", "LongMethod")
    suspend fun capture(
        request: MemoryCaptureRequest,
        cancellationSignal: HostCancellationSignal = HostCancellationSignal(),
    ): StudioResult<MemoryCaptureResult> {
        val sessionDirectory = request.sessionRoot.resolve(request.sessionId)
        val rawHprofFile = sessionDirectory.resolve("${request.sessionId}.raw.hprof")
        val convertedHprofFile = sessionDirectory.resolve("${request.sessionId}.hprof")
        val deviceHprofPath = "/data/local/tmp/heap-${request.sessionId}.hprof"
        Files.createDirectories(sessionDirectory)

        val dumpResult =
            runAdb(
                request.serial,
                listOf("shell", "am", "dumpheap", request.pid.toString(), deviceHprofPath),
                cancellationSignal,
            )
        if (dumpResult is StudioResult.Failure) return dumpResult

        val pullResult =
            runAdb(
                request.serial,
                listOf("pull", deviceHprofPath, rawHprofFile.toString()),
                cancellationSignal,
            )
        if (pullResult is StudioResult.Failure) {
            cleanup(request.serial, deviceHprofPath, cancellationSignal)
            return pullResult
        }

        val deviceSdkApiLevel = readSdkApiLevel(request.serial, cancellationSignal)
        // API level is not an HPROF format boundary. Keep the raw dump for the built-in parser and
        // produce a Java-SE-compatible copy whenever hprof-conv is available.
        val hprofConv = hprofConvLocator.locate()
        val conversionResult =
            if (hprofConv == null) {
                StudioResult.Success<MemoryCaptureConversion>(MemoryCaptureConversion.MissingTool)
            } else {
                convert(hprofConv, rawHprofFile, convertedHprofFile, cancellationSignal)
            }

        val cleanupWarning = cleanup(request.serial, deviceHprofPath, cancellationSignal)
        val warnings =
            buildList {
                val missingTool =
                    conversionResult is StudioResult.Success &&
                        conversionResult.value == MemoryCaptureConversion.MissingTool
                if (missingTool) {
                    add(
                        MemoryCaptureWarning(
                            code = "HPROF_CONV_MISSING",
                            message = MISSING_HPROF_CONV_MESSAGE,
                        ),
                    )
                }
                if (conversionResult is StudioResult.Failure) {
                    add(MemoryCaptureWarning(code = "HPROF_CONV_FAILED", message = FAILED_HPROF_CONV_MESSAGE))
                }
                cleanupWarning?.let(::add)
            }

        val converted =
            conversionResult is StudioResult.Success && conversionResult.value == MemoryCaptureConversion.Converted

        return StudioResult.Success(
            MemoryCaptureResult(
                sessionId = request.sessionId,
                sessionDirectory = sessionDirectory,
                deviceHprofPath = deviceHprofPath,
                rawHprofFile = rawHprofFile,
                convertedHprofFile = convertedHprofFile.takeIf { converted },
                deviceSdkApiLevel = deviceSdkApiLevel,
                conversionSkipped = false,
                warnings = warnings,
            ),
        )
    }

    private suspend fun runAdb(
        serial: String,
        adbArguments: List<String>,
        cancellationSignal: HostCancellationSignal,
    ): StudioResult<Unit> =
        try {
            when (adbArguments.firstOrNull()) {
                "shell" ->
                    adbClient.shell(
                        serial = serial,
                        arguments = adbArguments.drop(1),
                        timeout = 2.minutes,
                        isCancellationRequested = cancellationSignal::isCancelled,
                    )
                "pull" ->
                    adbClient.pull(
                        serial = serial,
                        remotePath = adbArguments[1],
                        localPath = Path.of(adbArguments[2]),
                        timeout = 2.minutes,
                        isCancellationRequested = cancellationSignal::isCancelled,
                    )
                else -> error("Unsupported typed ADB operation: ${adbArguments.firstOrNull()}")
            }
            StudioResult.Success(Unit)
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (error: AdbException) {
            StudioResult.Failure(adbError(adbArguments, error))
        }

    /** Reads the device Android API level via getprop; returns null when it cannot be determined. */
    private suspend fun readSdkApiLevel(
        serial: String,
        cancellationSignal: HostCancellationSignal,
    ): Int? =
        try {
            adbClient
                .shell(
                    serial = serial,
                    arguments = listOf("getprop", "ro.build.version.sdk"),
                    timeout = 30.seconds,
                    isCancellationRequested = cancellationSignal::isCancelled,
                ).stdout
                .trim()
                .toIntOrNull()
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            null
        }

    private suspend fun convert(
        hprofConv: Path,
        rawHprofFile: Path,
        convertedHprofFile: Path,
        cancellationSignal: HostCancellationSignal,
    ): StudioResult<MemoryCaptureConversion> {
        val result =
            hostProcessRunner(
                HostProcessRequest(
                    executable = hprofConv,
                    arguments = listOf(rawHprofFile.toString(), convertedHprofFile.toString()),
                    timeout = 2.minutes,
                ),
                cancellationSignal,
            )
        return when (result) {
            is HostCommandResult.Completed -> StudioResult.Success(MemoryCaptureConversion.Converted)
            is HostCommandResult.Failed ->
                StudioResult.Failure(
                    StudioError(
                        category = result.error.category,
                        code = "HPROF_CONV_FAILED",
                        message = "Host hprof-conv failed: ${combinedOutput(result).ifBlank { result.error.message }}",
                        cause = result.error.cause,
                    ),
                )
        }
    }

    private suspend fun cleanup(
        serial: String,
        deviceHprofPath: String,
        cancellationSignal: HostCancellationSignal,
    ): MemoryCaptureWarning? {
        val result =
            runCatching {
                adbClient.shell(
                    serial = serial,
                    arguments = listOf("rm", "-f", deviceHprofPath),
                    timeout = 30.seconds,
                    isCancellationRequested = cancellationSignal::isCancelled,
                )
            }
        return if (result.isSuccess) {
            null
        } else {
            MemoryCaptureWarning(
                code = "DEVICE_CLEANUP_FAILED",
                message = "Failed to remove temporary device heap dump $deviceHprofPath.",
            )
        }
    }

    private fun adbError(
        adbArguments: List<String>,
        error: RuntimeException,
    ): StudioError {
        val message = error.message.orEmpty()
        return when {
            adbArguments.contains("dumpheap") ->
                StudioError(
                    category = com.androidperformancestudio.model.ErrorCategory.PROCESS_EXIT,
                    code = "DUMPHEAP_FAILED",
                    message =
                        "Heap dump failed; target app may not be debuggable " +
                            "or shell lacks dump permission. $message",
                    cause = error,
                )
            adbArguments.contains("pull") ->
                StudioError(
                    category = com.androidperformancestudio.model.ErrorCategory.PROCESS_EXIT,
                    code = "PULL_FAILED",
                    message = "Failed to pull raw heap dump from device. $message",
                    cause = error,
                )
            else ->
                StudioError(
                    category = com.androidperformancestudio.model.ErrorCategory.PROCESS_EXIT,
                    code = "ADB_COMMAND_FAILED",
                    message = message,
                    cause = error,
                )
        }
    }

    private fun combinedOutput(result: HostCommandResult.Failed): String =
        listOfNotNull(result.output?.stderr?.text, result.output?.stdout?.text)
            .joinToString(separator = "\n")
            .trim()
}

private enum class MemoryCaptureConversion {
    Converted,
    MissingTool,
}
