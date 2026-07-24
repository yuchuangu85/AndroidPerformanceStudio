package dev.agentperf.memory.capture

import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessCancellationSignal
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

typealias MemoryCaptureProcessRunner = suspend (ProcessRequest, ProcessCancellationSignal) -> ProcessRunResult

private const val MISSING_HPROF_CONV_MESSAGE =
    "SDK Platform Tools hprof-conv was not found; raw Android HPROF was kept. " +
        "Install SDK Platform Tools or import a standard Java HPROF for analysis."

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
    private val adbExecutable: Path,
    private val hprofConvLocator: AndroidSdkHprofConvLocator = AndroidSdkHprofConvLocator(),
    private val processRunner: MemoryCaptureProcessRunner = { request, signal ->
        JvmProcessRunner().run(request, signal)
    },
) {
    @Suppress("ReturnCount")
    suspend fun capture(
        request: MemoryCaptureRequest,
        cancellationSignal: ProcessCancellationSignal = ProcessCancellationSignal(),
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
                cleanupWarning?.let(::add)
            }

        if (conversionResult is StudioResult.Failure) return conversionResult

        return StudioResult.Success(
            MemoryCaptureResult(
                sessionId = request.sessionId,
                sessionDirectory = sessionDirectory,
                deviceHprofPath = deviceHprofPath,
                rawHprofFile = rawHprofFile,
                convertedHprofFile = if (hprofConv == null) null else convertedHprofFile,
                warnings = warnings,
            ),
        )
    }

    private suspend fun runAdb(
        serial: String,
        adbArguments: List<String>,
        cancellationSignal: ProcessCancellationSignal,
    ): StudioResult<Unit> {
        val result =
            processRunner(
                ProcessRequest(
                    executable = adbExecutable,
                    arguments = listOf("-s", serial) + adbArguments,
                    timeout = 2.minutes,
                ),
                cancellationSignal,
            )
        return when (result) {
            is ProcessRunResult.Completed -> StudioResult.Success(Unit)
            is ProcessRunResult.Failed -> StudioResult.Failure(adbError(adbArguments, result))
        }
    }

    private suspend fun convert(
        hprofConv: Path,
        rawHprofFile: Path,
        convertedHprofFile: Path,
        cancellationSignal: ProcessCancellationSignal,
    ): StudioResult<MemoryCaptureConversion> {
        val result =
            processRunner(
                ProcessRequest(
                    executable = hprofConv,
                    arguments = listOf(rawHprofFile.toString(), convertedHprofFile.toString()),
                    timeout = 2.minutes,
                ),
                cancellationSignal,
            )
        return when (result) {
            is ProcessRunResult.Completed -> StudioResult.Success(MemoryCaptureConversion.Converted)
            is ProcessRunResult.Failed ->
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
        cancellationSignal: ProcessCancellationSignal,
    ): MemoryCaptureWarning? {
        val result =
            processRunner(
                ProcessRequest(
                    executable = adbExecutable,
                    arguments = listOf("-s", serial, "shell", "rm", "-f", deviceHprofPath),
                    timeout = 30.seconds,
                ),
                cancellationSignal,
            )
        return if (result is ProcessRunResult.Completed) {
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
        result: ProcessRunResult.Failed,
    ): StudioError {
        val message = combinedOutput(result).ifBlank { result.error.message }
        return when {
            adbArguments.contains("dumpheap") ->
                StudioError(
                    category = result.error.category,
                    code = "DUMPHEAP_FAILED",
                    message =
                        "Heap dump failed; target app may not be debuggable " +
                            "or shell lacks dump permission. $message",
                    cause = result.error.cause,
                )
            adbArguments.contains("pull") ->
                StudioError(
                    category = result.error.category,
                    code = "PULL_FAILED",
                    message = "Failed to pull raw heap dump from device. $message",
                    cause = result.error.cause,
                )
            else -> result.error
        }
    }

    private fun combinedOutput(result: ProcessRunResult.Failed): String =
        listOfNotNull(result.output?.stderr?.text, result.output?.stdout?.text)
            .joinToString(separator = "\n")
            .trim()

    private enum class MemoryCaptureConversion {
        Converted,
        MissingTool,
    }
}
