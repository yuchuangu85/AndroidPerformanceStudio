package com.androidperformancestudio.capture

import com.androidperformancestudio.contracts.ArtifactAcquisition
import com.androidperformancestudio.contracts.ArtifactAcquisitionKind
import com.androidperformancestudio.contracts.ArtifactCompleteness
import com.androidperformancestudio.contracts.ArtifactFileEvidence
import com.androidperformancestudio.contracts.ArtifactFormat
import com.androidperformancestudio.contracts.ArtifactId
import com.androidperformancestudio.contracts.ArtifactKind
import com.androidperformancestudio.contracts.ArtifactLocation
import com.androidperformancestudio.contracts.ArtifactProducer
import com.androidperformancestudio.contracts.ArtifactProvenance
import com.androidperformancestudio.contracts.CapabilityId
import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.contracts.CaptureArtifactJson
import com.androidperformancestudio.contracts.ClockDomain
import com.androidperformancestudio.contracts.DeviceIdentityPseudonymizer
import com.androidperformancestudio.contracts.DeviceTargetIdentity
import com.androidperformancestudio.contracts.ProcessIdentity
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbCommandCancelledException
import com.androidperformancestudio.platform.adb.AdbCommandFailedException
import com.androidperformancestudio.platform.adb.AdbException
import com.androidperformancestudio.platform.adb.AdbTextResult
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import com.androidperformancestudio.platform.toolchain.HostCancellationSignal
import com.androidperformancestudio.platform.toolchain.HostCapturedText
import com.androidperformancestudio.platform.toolchain.HostCommandOutput
import com.androidperformancestudio.platform.toolchain.HostCommandResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.isRegularFile
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class CaptureRequest(
    val sessionId: String,
    val sessionRoot: Path,
    val serial: String,
    val availability: DeviceSimpleperfAvailability,
    val parameters: SamplingParameters,
) {
    init {
        require(sessionId.matches(SESSION_ID_PATTERN)) { "sessionId contains unsupported characters" }
        require(serial.isNotBlank()) { "serial must not be blank" }
    }

    companion object {
        private val SESSION_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}

sealed interface CaptureState {
    data object Idle : CaptureState

    sealed interface SessionState : CaptureState {
        val sessionDirectory: Path
    }

    data class Preparing(
        override val sessionDirectory: Path,
    ) : SessionState

    data class Recording(
        override val sessionDirectory: Path,
        val commandPreview: String,
    ) : SessionState

    data class Stopping(
        override val sessionDirectory: Path,
    ) : SessionState

    data class Pulling(
        override val sessionDirectory: Path,
    ) : SessionState

    data class Completed(
        override val sessionDirectory: Path,
        val perfData: Path,
    ) : SessionState

    data class Failed(
        val error: StudioError,
        override val sessionDirectory: Path,
    ) : SessionState

    data class Cancelled(
        override val sessionDirectory: Path,
    ) : SessionState
}

interface CaptureSession {
    val state: StateFlow<CaptureState>

    suspend fun capture(request: CaptureRequest): CaptureState

    suspend fun stop()

    fun cancel()
}

@Suppress("TooManyFunctions")
class SimpleperfCaptureSession(
    private val adbClient: AdbClient,
    private val simpleperfPreparer: DeviceSimpleperfPreparer,
    private val deviceIdentity: DeviceIdentityPseudonymizer = DeviceIdentityPseudonymizer(),
    private val adbCommandName: String = "adb",
) : CaptureSession {
    constructor(
        adbExecutable: Path,
        simpleperfPreparer: DeviceSimpleperfPreparer,
        deviceIdentity: DeviceIdentityPseudonymizer = DeviceIdentityPseudonymizer(),
    ) : this(DefaultAdbClient(adbExecutable), simpleperfPreparer, deviceIdentity, adbExecutable.toString())

    private val captureMutex = Mutex()
    private val mutableState = MutableStateFlow<CaptureState>(CaptureState.Idle)

    @Volatile
    private var activeCancellation: HostCancellationSignal? = null

    @Volatile
    private var activeRecording: ActiveRecording? = null

    override val state: StateFlow<CaptureState> = mutableState.asStateFlow()

    override suspend fun capture(request: CaptureRequest): CaptureState =
        captureMutex.withLock {
            val cancellation = HostCancellationSignal()
            activeCancellation = cancellation
            try {
                capture(request, cancellation)
            } finally {
                activeCancellation = null
            }
        }

    override fun cancel() {
        activeCancellation?.cancel()
    }

    override suspend fun stop() {
        val recording = activeRecording ?: return
        val result =
            runAdb(listOf("-s", recording.serial, "shell", "pkill", "-INT", "simpleperf")) {
                adbClient.shell(recording.serial, listOf("pkill", "-INT", "simpleperf"), 30.seconds)
            }
        recording.artifacts.writeProcessOutput("stop", result.outputOrNull())
        mutableState.value =
            if (result is HostCommandResult.Completed) {
                CaptureState.Stopping(recording.artifacts.sessionDirectory)
            } else {
                CaptureState.Recording(recording.artifacts.sessionDirectory, recording.commandPreview)
            }
    }

    private suspend fun capture(
        request: CaptureRequest,
        cancellation: HostCancellationSignal,
    ): CaptureState {
        val artifacts =
            try {
                CaptureArtifacts.create(request, deviceIdentity.localId(request.serial).value)
            } catch (exception: IOException) {
                return updateState(
                    CaptureState.Failed(
                        error = ioError("CAPTURE_SESSION_CREATE_FAILED", "Failed to create capture session", exception),
                        sessionDirectory = request.sessionRoot.resolve(request.sessionId),
                    ),
                )
            }
        updateState(CaptureState.Preparing(artifacts.sessionDirectory))
        return when (val prepared = simpleperfPreparer.prepare(request.serial, request.availability, cancellation)) {
            is StudioResult.Failure -> artifacts.finishFailure(prepared.error)
            is StudioResult.Success ->
                when (val outputReady = prepareRemoteOutput(request, artifacts, cancellation)) {
                    is StudioResult.Failure -> artifacts.finishFailure(outputReady.error, prepared.value)
                    is StudioResult.Success -> record(request, prepared.value, artifacts, cancellation)
                }
        }
    }

    private suspend fun prepareRemoteOutput(
        request: CaptureRequest,
        artifacts: CaptureArtifacts,
        cancellation: HostCancellationSignal,
    ): StudioResult<Unit> {
        val directory = request.parameters.outputPath.substringBeforeLast('/', missingDelimiterValue = "")
        if (directory.isBlank()) return StudioResult.Success(Unit)
        val result =
            runAdb(listOf("-s", request.serial, "shell", "mkdir", "-p", directory)) {
                adbClient.shell(
                    serial = request.serial,
                    arguments = listOf("mkdir", "-p", directory),
                    timeout = 30.seconds,
                    isCancellationRequested = cancellation::isCancelled,
                )
            }
        artifacts.writeProcessOutput("prepare-output", result.outputOrNull())
        return when (result) {
            is HostCommandResult.Completed -> StudioResult.Success(Unit)
            is HostCommandResult.Failed -> StudioResult.Failure(result.error)
        }
    }

    private suspend fun record(
        request: CaptureRequest,
        prepared: PreparedSimpleperf,
        artifacts: CaptureArtifacts,
        cancellation: HostCancellationSignal,
    ): CaptureState {
        val command = SimpleperfRecordCommand(request.serial, prepared.devicePath, request.parameters)
        val commandPreview = command.preview(adbCommandName)
        artifacts.writeCommand(commandPreview)
        updateState(CaptureState.Recording(artifacts.sessionDirectory, commandPreview))
        activeRecording = ActiveRecording(request.serial, commandPreview, artifacts)
        val recordResult =
            try {
                runAdb(command.adbArguments) {
                    adbClient.shell(
                        serial = request.serial,
                        arguments = command.shellArguments,
                        timeout = request.parameters.captureTimeout(),
                        isCancellationRequested = cancellation::isCancelled,
                    )
                }
            } finally {
                activeRecording = null
            }
        artifacts.writeProcessOutput("record", recordResult.outputOrNull())
        val outcome =
            when (recordResult) {
                is HostCommandResult.Failed -> artifacts.finishFailure(recordResult.captureError(), prepared)
                is HostCommandResult.Completed -> pull(request, prepared, artifacts, cancellation)
            }
        cleanupRemoteOutput(request, artifacts)
        return outcome
    }

    private suspend fun cleanupRemoteOutput(
        request: CaptureRequest,
        artifacts: CaptureArtifacts,
    ) {
        val cleanupResult =
            runAdb(listOf("-s", request.serial, "shell", "rm", "-f", request.parameters.outputPath)) {
                adbClient.shell(
                    request.serial,
                    listOf("rm", "-f", request.parameters.outputPath),
                    30.seconds,
                )
            }
        artifacts.writeProcessOutput("cleanup", cleanupResult.outputOrNull())
    }

    private suspend fun pull(
        request: CaptureRequest,
        prepared: PreparedSimpleperf,
        artifacts: CaptureArtifacts,
        cancellation: HostCancellationSignal,
    ): CaptureState {
        updateState(CaptureState.Pulling(artifacts.sessionDirectory))
        val pullResult =
            runAdb(
                listOf("-s", request.serial, "pull", request.parameters.outputPath, artifacts.perfData.toString()),
            ) {
                adbClient.pull(
                    serial = request.serial,
                    remotePath = request.parameters.outputPath,
                    localPath = artifacts.perfData,
                    timeout = 2.minutes,
                    isCancellationRequested = cancellation::isCancelled,
                )
            }
        artifacts.writeProcessOutput("pull", pullResult.outputOrNull())
        return when (pullResult) {
            is HostCommandResult.Failed -> artifacts.finishFailure(pullResult.error, prepared)
            is HostCommandResult.Completed ->
                if (artifacts.perfData.isRegularFile()) {
                    artifacts.finishCompleted(request, prepared)
                } else {
                    artifacts.finishFailure(
                        ioError(
                            code = "CAPTURE_PERF_DATA_MISSING",
                            message = "adb pull completed without producing perf.data",
                        ),
                        prepared,
                    )
                }
        }
    }

    private suspend fun runAdb(
        arguments: List<String>,
        block: suspend () -> AdbTextResult,
    ): HostCommandResult {
        val startedAt = Instant.now()
        return try {
            val result = block()
            HostCommandResult.Completed(result.toHostOutput(adbCommandName, arguments, startedAt))
        } catch (error: AdbCommandCancelledException) {
            adbFailure(error, arguments, startedAt)
        } catch (error: AdbException) {
            adbFailure(error, arguments, startedAt)
        }
    }

    private fun adbFailure(
        error: RuntimeException,
        arguments: List<String>,
        startedAt: Instant,
    ): HostCommandResult.Failed {
        val output =
            (error as? AdbCommandFailedException)?.let { failure ->
                HostCommandOutput(
                    pid = -1,
                    command = listOf(adbCommandName) + arguments,
                    exitCode = failure.exitCode,
                    stdout = HostCapturedText("", false),
                    stderr = HostCapturedText(failure.standardError, false),
                    startedAt = startedAt,
                    finishedAt = Instant.now(),
                )
            }
        return HostCommandResult.Failed(error.toAdbStudioError(), output)
    }

    private fun CaptureArtifacts.finishCompleted(
        request: CaptureRequest,
        prepared: PreparedSimpleperf,
    ): CaptureState =
        updateState(
            try {
                writeProperties("COMPLETED", prepared)
                writeCaptureArtifact(request, prepared)
                CaptureState.Completed(sessionDirectory, perfData)
            } catch (exception: IOException) {
                CaptureState.Failed(
                    ioError("CAPTURE_METADATA_WRITE_FAILED", "Failed to persist capture metadata", exception),
                    sessionDirectory,
                )
            },
        )

    private fun CaptureArtifacts.finishFailure(
        error: StudioError,
        prepared: PreparedSimpleperf? = null,
    ): CaptureState =
        updateState(
            try {
                val status = if (error.category == ErrorCategory.PROCESS_CANCELLED) "CANCELLED" else "FAILED"
                writeProperties(status, prepared, error)
                if (status == "CANCELLED") {
                    CaptureState.Cancelled(sessionDirectory)
                } else {
                    CaptureState.Failed(error, sessionDirectory)
                }
            } catch (exception: IOException) {
                CaptureState.Failed(
                    ioError("CAPTURE_METADATA_WRITE_FAILED", "Failed to persist capture failure", exception),
                    sessionDirectory,
                )
            },
        )

    private fun CaptureArtifacts.writeCaptureArtifact(
        request: CaptureRequest,
        prepared: PreparedSimpleperf,
    ) {
        val capabilities = setOf(CPU_SAMPLES, CPU_CALL_STACKS, CPU_THREAD_TIMELINE)
        val deviceId = deviceIdentity.localId(request.serial)
        val artifact =
            CaptureArtifact(
                id = ArtifactId(sessionDirectory.fileName.toString()),
                kind = ArtifactKind("cpu.simpleperf"),
                location = ArtifactLocation(perfData.toAbsolutePath().normalize().toString()),
                sha256 = ArtifactFileEvidence.sha256(perfData),
                format = ArtifactFormat("simpleperf", prepared.version),
                provenance =
                    ArtifactProvenance(
                        producer = ArtifactProducer.Known("Android simpleperf", prepared.version),
                        acquisition =
                            ArtifactAcquisition(
                                ArtifactAcquisitionKind.CAPTURE,
                                "Android Performance Studio",
                                performedAtEpochMillis = System.currentTimeMillis(),
                            ),
                    ),
                device = DeviceTargetIdentity(deviceId),
                process =
                    (request.parameters.target as? SimpleperfTarget.Process)?.let { target ->
                        ProcessIdentity(
                            pid = target.pid,
                            deviceLocalId = deviceId,
                            packageName = target.appPackage,
                        )
                    },
                clockDomains = setOf(SIMPLEPERF_CLOCK),
                requestedCapabilities = capabilities,
                availableCapabilities = capabilities,
                completeness = ArtifactCompleteness.COMPLETE,
            )
        Files.writeString(sessionDirectory.resolve("capture-artifact.json"), CaptureArtifactJson.encode(artifact))
    }

    private fun updateState(newState: CaptureState): CaptureState {
        mutableState.value = newState
        return newState
    }
}

private data class ActiveRecording(
    val serial: String,
    val commandPreview: String,
    val artifacts: CaptureArtifacts,
)

private class CaptureArtifacts private constructor(
    val sessionDirectory: Path,
    private val serial: String,
    private val deviceLocalId: String,
) {
    val perfData: Path = sessionDirectory.resolve("perf.data")

    fun writeCommand(command: String) {
        Files.writeString(sessionDirectory.resolve("capture-command.txt"), command.replace(serial, "<device>"))
    }

    fun writeProcessOutput(
        name: String,
        output: HostCommandOutput?,
    ) {
        Files.writeString(sessionDirectory.resolve("$name.stdout.log"), output?.stdout?.text.orEmpty())
        Files.writeString(sessionDirectory.resolve("$name.stderr.log"), output?.stderr?.text.orEmpty())
        val metadata =
            listOf(
                "exitCode=${output?.exitCode?.toString().orEmpty()}",
                "stdout.truncated=${output?.stdout?.truncated ?: false}",
                "stderr.truncated=${output?.stderr?.truncated ?: false}",
                "command=${output?.command?.joinToString(" ").orEmpty().replace(serial, "<device>").propertyValue()}",
                "startedAt=${output?.startedAt ?: ""}",
                "finishedAt=${output?.finishedAt ?: ""}",
            )
        Files.writeString(
            sessionDirectory.resolve("$name.properties"),
            metadata.joinToString("\n", postfix = "\n"),
        )
    }

    fun writeProperties(
        status: String,
        prepared: PreparedSimpleperf?,
        error: StudioError? = null,
    ) {
        val values =
            buildList {
                add("status=$status")
                add("deviceLocalId=$deviceLocalId")
                prepared?.let {
                    add("simpleperf.source=${it.source}")
                    add("simpleperf.path=${it.devicePath.propertyValue()}")
                    add("simpleperf.version=${it.version.orEmpty().propertyValue()}")
                    add("simpleperf.abi=${it.abi.orEmpty().propertyValue()}")
                }
                error?.let {
                    add("error.category=${it.category}")
                    add("error.code=${it.code.propertyValue()}")
                    add("error.message=${it.message.propertyValue()}")
                }
            }
        Files.writeString(sessionDirectory.resolve("session.properties"), values.joinToString("\n", postfix = "\n"))
    }

    companion object {
        fun create(
            request: CaptureRequest,
            deviceLocalId: String,
        ): CaptureArtifacts {
            val directory =
                request.sessionRoot
                    .toAbsolutePath()
                    .normalize()
                    .resolve(request.sessionId)
                    .normalize()
            require(directory.startsWith(request.sessionRoot.toAbsolutePath().normalize())) {
                "session directory must remain under session root"
            }
            Files.createDirectories(directory)
            return CaptureArtifacts(directory, request.serial, deviceLocalId)
        }
    }
}

private fun HostCommandResult.outputOrNull(): HostCommandOutput? =
    when (this) {
        is HostCommandResult.Completed -> output
        is HostCommandResult.Failed -> output
    }

private fun HostCommandResult.Failed.captureError(): StudioError {
    val stderr = output?.stderr?.text.orEmpty()
    return if (error.category == ErrorCategory.PROCESS_EXIT && NOT_PROFILEABLE_ERROR in stderr) {
        StudioError(
            category = ErrorCategory.CONFIGURATION,
            code = "TARGET_NOT_PROFILEABLE",
            message =
                "The selected app is not debuggable/profileable. Select a debuggable or profileable app, " +
                    "or use a rooted/userdebug device.",
        )
    } else {
        error
    }
}

private fun AdbTextResult.toHostOutput(
    executable: String,
    arguments: List<String>,
    startedAt: Instant,
): HostCommandOutput =
    HostCommandOutput(
        pid = pid,
        command = listOf(executable) + arguments,
        exitCode = exitCode,
        stdout = HostCapturedText(stdout, stdoutTruncated),
        stderr = HostCapturedText(stderr, stderrTruncated),
        startedAt = startedAt,
        finishedAt = Instant.now(),
    )

private fun Throwable.toAdbStudioError(): StudioError {
    val category =
        when (this) {
            is com.androidperformancestudio.platform.adb.AdbCommandCancelledException -> ErrorCategory.PROCESS_CANCELLED
            is com.androidperformancestudio.platform.adb.AdbCommandTimeoutException -> ErrorCategory.PROCESS_TIMEOUT
            is com.androidperformancestudio.platform.adb.AdbProcessStartException -> ErrorCategory.PROCESS_START
            is AdbCommandFailedException -> ErrorCategory.PROCESS_EXIT
            else -> ErrorCategory.UNKNOWN
        }
    val code =
        when (this) {
            is com.androidperformancestudio.platform.adb.AdbCommandCancelledException -> "PROCESS_CANCELLED"
            is com.androidperformancestudio.platform.adb.AdbCommandTimeoutException -> "PROCESS_TIMED_OUT"
            is com.androidperformancestudio.platform.adb.AdbProcessStartException -> "PROCESS_START_FAILED"
            is AdbCommandFailedException -> "PROCESS_EXIT_$exitCode"
            else -> "ADB_COMMAND_FAILED"
        }
    return StudioError(category, code, message ?: "ADB command failed", this)
}

private fun SamplingParameters.captureTimeout() = durationSeconds?.seconds?.plus(15.seconds) ?: 24.hours

private fun String.propertyValue(): String = replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

private val CPU_SAMPLES = CapabilityId("cpu.samples")
private val CPU_CALL_STACKS = CapabilityId("cpu.call_stacks")
private val CPU_THREAD_TIMELINE = CapabilityId("cpu.thread_timeline")
private val SIMPLEPERF_CLOCK = ClockDomain("simpleperf.perf_time")

private const val NOT_PROFILEABLE_ERROR = "doesn't exist or isn't debuggable/profileable"

private fun ioError(
    code: String,
    message: String,
    cause: Throwable? = null,
): StudioError = StudioError(ErrorCategory.IO, code, message, cause)
