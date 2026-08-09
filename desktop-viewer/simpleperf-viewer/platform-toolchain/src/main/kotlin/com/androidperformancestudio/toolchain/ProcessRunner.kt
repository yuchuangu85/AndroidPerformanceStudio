package com.androidperformancestudio.toolchain

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.platform.toolchain.HostProcessCancelledException
import com.androidperformancestudio.platform.toolchain.HostProcessLaunchRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRunner
import com.androidperformancestudio.platform.toolchain.HostProcessStartException
import com.androidperformancestudio.platform.toolchain.HostProcessTimeoutException
import com.androidperformancestudio.platform.toolchain.JvmHostProcessRunner
import com.androidperformancestudio.platform.toolchain.RunningHostProcess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class ProcessRequest(
    val executable: Path,
    val arguments: List<String> = emptyList(),
    val workingDirectory: Path? = null,
    val environmentOverrides: Map<String, String> = emptyMap(),
    val timeout: Duration = 30.seconds,
    val charset: Charset = StandardCharsets.UTF_8,
    val maxCapturedCharactersPerStream: Int = DEFAULT_CAPTURE_LIMIT,
) {
    init {
        require(timeout.isPositive() && timeout.isFinite()) { "timeout must be positive and finite" }
        require(maxCapturedCharactersPerStream > 0) { "capture limit must be positive" }
    }

    val command: List<String> = listOf(executable.toString()) + arguments

    companion object {
        const val DEFAULT_CAPTURE_LIMIT = 1_048_576
    }
}

data class CapturedProcessText(
    val text: String,
    val truncated: Boolean,
)

data class ProcessOutput(
    val pid: Long,
    val command: List<String>,
    val exitCode: Int?,
    val stdout: CapturedProcessText,
    val stderr: CapturedProcessText,
    val startedAt: Instant,
    val finishedAt: Instant,
)

sealed interface ProcessRunResult {
    data class Completed(
        val output: ProcessOutput,
    ) : ProcessRunResult

    data class Failed(
        val error: StudioError,
        val output: ProcessOutput? = null,
    ) : ProcessRunResult
}

class ProcessCancellationSignal {
    private val cancelled = AtomicBoolean(false)

    val isCancelled: Boolean
        get() = cancelled.get()

    fun cancel() {
        cancelled.set(true)
    }
}

/**
 * Compatibility adapter for existing profiler code. Process execution and lifecycle handling
 * are owned by platform-core:host-toolchain.
 */
class JvmProcessRunner(
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    terminationGracePeriod: Duration = 500.milliseconds,
) {
    private val delegate: HostProcessRunner =
        JvmHostProcessRunner(
            ioDispatcher = ioDispatcher,
            terminationGracePeriod = terminationGracePeriod,
        )

    fun launch(request: HostProcessLaunchRequest): RunningHostProcess = delegate.launch(request)

    suspend fun run(
        request: ProcessRequest,
        cancellationSignal: ProcessCancellationSignal = ProcessCancellationSignal(),
    ): ProcessRunResult {
        val startedAt = Instant.now()
        return try {
            val result =
                delegate.executeText(
                    HostProcessRequest(
                        executable = request.executable,
                        arguments = request.arguments,
                        timeout = request.timeout,
                        workingDirectory = request.workingDirectory,
                        environmentOverrides = request.environmentOverrides,
                        charset = request.charset,
                        maxOutputBytesPerStream = request.maxCapturedCharactersPerStream,
                        isCancellationRequested = { cancellationSignal.isCancelled },
                    ),
                )
            val output =
                ProcessOutput(
                    pid = result.pid,
                    command = request.command,
                    exitCode = result.exitCode,
                    stdout = CapturedProcessText(result.stdout, result.stdoutTruncated),
                    stderr = CapturedProcessText(result.stderr, result.stderrTruncated),
                    startedAt = startedAt,
                    finishedAt = Instant.now(),
                )
            if (result.exitCode == 0) {
                ProcessRunResult.Completed(output)
            } else {
                failure(
                    category = ErrorCategory.PROCESS_EXIT,
                    code = "PROCESS_EXIT_${result.exitCode}",
                    message = "Process exited with code ${result.exitCode}",
                    output = output,
                )
            }
        } catch (error: HostProcessTimeoutException) {
            failure(
                ErrorCategory.PROCESS_TIMEOUT,
                "PROCESS_TIMED_OUT",
                "Process timed out",
                terminatedOutput(error.pid, request, startedAt),
            )
        } catch (error: HostProcessCancelledException) {
            failure(
                ErrorCategory.PROCESS_CANCELLED,
                "PROCESS_CANCELLED",
                "Process was cancelled",
                terminatedOutput(error.pid, request, startedAt),
            )
        } catch (error: HostProcessStartException) {
            failure(
                ErrorCategory.PROCESS_START,
                "PROCESS_START_FAILED",
                error.message ?: "Failed to start executable: ${request.executable}",
            )
        }
    }

    private fun terminatedOutput(
        pid: Long,
        request: ProcessRequest,
        startedAt: Instant,
    ): ProcessOutput =
        ProcessOutput(
            pid = pid,
            command = request.command,
            exitCode = null,
            stdout = CapturedProcessText("", false),
            stderr = CapturedProcessText("", false),
            startedAt = startedAt,
            finishedAt = Instant.now(),
        )

    private fun failure(
        category: ErrorCategory,
        code: String,
        message: String,
        output: ProcessOutput? = null,
    ): ProcessRunResult.Failed =
        ProcessRunResult.Failed(
            error = StudioError(category = category, code = code, message = message),
            output = output,
        )
}
