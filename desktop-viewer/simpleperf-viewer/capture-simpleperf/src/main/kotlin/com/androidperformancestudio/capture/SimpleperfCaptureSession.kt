package com.androidperformancestudio.capture

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessCancellationSignal
import com.androidperformancestudio.toolchain.ProcessOutput
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
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

class SimpleperfCaptureSession(
    private val adbExecutable: Path,
    private val simpleperfPreparer: DeviceSimpleperfPreparer,
    private val processInvocation: CaptureProcessInvocation = { request, signal ->
        JvmProcessRunner().run(request, signal)
    },
) : CaptureSession {
    private val captureMutex = Mutex()
    private val mutableState = MutableStateFlow<CaptureState>(CaptureState.Idle)

    @Volatile
    private var activeCancellation: ProcessCancellationSignal? = null

    @Volatile
    private var activeRecording: ActiveRecording? = null

    override val state: StateFlow<CaptureState> = mutableState.asStateFlow()

    override suspend fun capture(request: CaptureRequest): CaptureState =
        captureMutex.withLock {
            val cancellation = ProcessCancellationSignal()
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
            processInvocation(
                ProcessRequest(
                    executable = adbExecutable,
                    arguments = listOf("-s", recording.serial, "shell", "pkill", "-INT", "simpleperf"),
                    timeout = 30.seconds,
                ),
                ProcessCancellationSignal(),
            )
        recording.artifacts.writeProcessOutput("stop", result.outputOrNull())
        mutableState.value =
            if (result is ProcessRunResult.Completed) {
                CaptureState.Stopping(recording.artifacts.sessionDirectory)
            } else {
                CaptureState.Recording(recording.artifacts.sessionDirectory, recording.commandPreview)
            }
    }

    private suspend fun capture(
        request: CaptureRequest,
        cancellation: ProcessCancellationSignal,
    ): CaptureState {
        val artifacts =
            try {
                CaptureArtifacts.create(request)
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
            is StudioResult.Success -> record(request, prepared.value, artifacts, cancellation)
        }
    }

    private suspend fun record(
        request: CaptureRequest,
        prepared: PreparedSimpleperf,
        artifacts: CaptureArtifacts,
        cancellation: ProcessCancellationSignal,
    ): CaptureState {
        val command = SimpleperfRecordCommand(request.serial, prepared.devicePath, request.parameters)
        val commandPreview = command.preview(adbExecutable.toString())
        artifacts.writeCommand(commandPreview)
        updateState(CaptureState.Recording(artifacts.sessionDirectory, commandPreview))
        activeRecording = ActiveRecording(request.serial, commandPreview, artifacts)
        val recordResult =
            try {
                processInvocation(
                    ProcessRequest(
                        executable = adbExecutable,
                        arguments = command.adbArguments,
                        timeout = request.parameters.captureTimeout(),
                    ),
                    cancellation,
                )
            } finally {
                activeRecording = null
            }
        artifacts.writeProcessOutput("record", recordResult.outputOrNull())
        val outcome =
            when (recordResult) {
                is ProcessRunResult.Failed -> artifacts.finishFailure(recordResult.error, prepared)
                is ProcessRunResult.Completed -> pull(request, prepared, artifacts, cancellation)
            }
        cleanupRemoteOutput(request, artifacts)
        return outcome
    }

    private suspend fun cleanupRemoteOutput(
        request: CaptureRequest,
        artifacts: CaptureArtifacts,
    ) {
        val cleanupResult =
            processInvocation(
                ProcessRequest(
                    executable = adbExecutable,
                    arguments =
                        listOf(
                            "-s",
                            request.serial,
                            "shell",
                            "rm",
                            "-f",
                            request.parameters.outputPath,
                        ),
                    timeout = 30.seconds,
                ),
                ProcessCancellationSignal(),
            )
        artifacts.writeProcessOutput("cleanup", cleanupResult.outputOrNull())
    }

    private suspend fun pull(
        request: CaptureRequest,
        prepared: PreparedSimpleperf,
        artifacts: CaptureArtifacts,
        cancellation: ProcessCancellationSignal,
    ): CaptureState {
        updateState(CaptureState.Pulling(artifacts.sessionDirectory))
        val pullResult =
            processInvocation(
                ProcessRequest(
                    executable = adbExecutable,
                    arguments =
                        listOf(
                            "-s",
                            request.serial,
                            "pull",
                            request.parameters.outputPath,
                            artifacts.perfData.toString(),
                        ),
                    timeout = 2.minutes,
                ),
                cancellation,
            )
        artifacts.writeProcessOutput("pull", pullResult.outputOrNull())
        return when (pullResult) {
            is ProcessRunResult.Failed -> artifacts.finishFailure(pullResult.error, prepared)
            is ProcessRunResult.Completed ->
                if (artifacts.perfData.isRegularFile()) {
                    artifacts.finishCompleted(prepared)
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

    private fun CaptureArtifacts.finishCompleted(prepared: PreparedSimpleperf): CaptureState =
        updateState(
            try {
                writeProperties("COMPLETED", prepared)
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
) {
    val perfData: Path = sessionDirectory.resolve("perf.data")

    fun writeCommand(command: String) {
        Files.writeString(sessionDirectory.resolve("capture-command.txt"), command)
    }

    fun writeProcessOutput(
        name: String,
        output: ProcessOutput?,
    ) {
        Files.writeString(sessionDirectory.resolve("$name.stdout.log"), output?.stdout?.text.orEmpty())
        Files.writeString(sessionDirectory.resolve("$name.stderr.log"), output?.stderr?.text.orEmpty())
        val metadata =
            listOf(
                "exitCode=${output?.exitCode?.toString().orEmpty()}",
                "stdout.truncated=${output?.stdout?.truncated ?: false}",
                "stderr.truncated=${output?.stderr?.truncated ?: false}",
                "command=${output?.command?.joinToString(" ").orEmpty().propertyValue()}",
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
                add("serial=${serial.propertyValue()}")
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
        fun create(request: CaptureRequest): CaptureArtifacts {
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
            return CaptureArtifacts(directory, request.serial)
        }
    }
}

private fun ProcessRunResult.outputOrNull(): ProcessOutput? =
    when (this) {
        is ProcessRunResult.Completed -> output
        is ProcessRunResult.Failed -> output
    }

private fun SamplingParameters.captureTimeout() = durationSeconds?.seconds?.plus(15.seconds) ?: 24.hours

private fun String.propertyValue(): String = replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

private fun ioError(
    code: String,
    message: String,
    cause: Throwable? = null,
): StudioError = StudioError(ErrorCategory.IO, code, message, cause)
