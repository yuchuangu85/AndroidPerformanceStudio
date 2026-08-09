package com.androidperformancestudio.platform.toolchain

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class HostCapturedText(
    val text: String,
    val truncated: Boolean,
)

data class HostCommandOutput(
    val pid: Long,
    val command: List<String>,
    val exitCode: Int?,
    val stdout: HostCapturedText,
    val stderr: HostCapturedText,
    val startedAt: Instant,
    val finishedAt: Instant,
)

sealed interface HostCommandResult {
    data class Completed(
        val output: HostCommandOutput,
    ) : HostCommandResult

    data class Failed(
        val error: StudioError,
        val output: HostCommandOutput? = null,
    ) : HostCommandResult
}

class HostCancellationSignal {
    private val cancelled = AtomicBoolean(false)

    val isCancelled: Boolean
        get() = cancelled.get()

    fun cancel() {
        cancelled.set(true)
    }
}

/** Maps the canonical host runner's typed failures into the shared Studio error contract. */
class StudioHostProcessExecutor(
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
        request: HostProcessRequest,
        cancellationSignal: HostCancellationSignal = HostCancellationSignal(),
    ): HostCommandResult {
        val startedAt = Instant.now()
        return try {
            val result =
                delegate.executeText(
                    request.copy(
                        isCancellationRequested = {
                            request.isCancellationRequested() || cancellationSignal.isCancelled
                        },
                    ),
                )
            val output =
                HostCommandOutput(
                    pid = result.pid,
                    command = request.command,
                    exitCode = result.exitCode,
                    stdout = HostCapturedText(result.stdout, result.stdoutTruncated),
                    stderr = HostCapturedText(result.stderr, result.stderrTruncated),
                    startedAt = startedAt,
                    finishedAt = Instant.now(),
                )
            if (result.exitCode == 0) {
                HostCommandResult.Completed(output)
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
        request: HostProcessRequest,
        startedAt: Instant,
    ): HostCommandOutput =
        HostCommandOutput(
            pid = pid,
            command = request.command,
            exitCode = null,
            stdout = HostCapturedText("", false),
            stderr = HostCapturedText("", false),
            startedAt = startedAt,
            finishedAt = Instant.now(),
        )

    private fun failure(
        category: ErrorCategory,
        code: String,
        message: String,
        output: HostCommandOutput? = null,
    ): HostCommandResult.Failed =
        HostCommandResult.Failed(
            error = StudioError(category = category, code = code, message = message),
            output = output,
        )
}
