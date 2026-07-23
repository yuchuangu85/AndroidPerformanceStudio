package com.androidperformancestudio.perfetto.traceprocessor

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessCancellationSignal
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Manages a trace_processor subprocess in `server http` mode.
 * The Perfetto UI connects to localhost:9001 for native trace parsing acceleration.
 */
class TraceProcessorSession(
    private val binary: TraceProcessorBinary,
    private val traceFile: Path,
    val httpPort: Int = 9001,
) {
    private val processRunner = JvmProcessRunner()
    private val cancellationSignal = ProcessCancellationSignal()

    var isRunning: Boolean = false
        private set

    suspend fun start(): StudioResult<Unit> = withContext(Dispatchers.IO) {
        if (isRunning) {
            return@withContext StudioResult.Failure(
                StudioError(
                    category = ErrorCategory.CONFIGURATION,
                    code = "ALREADY_RUNNING",
                    message = "trace_processor is already running on port $httpPort",
                )
            )
        }

        val request = ProcessRequest(
            executable = binary.path,
            arguments = listOf("--http-port", httpPort.toString(), traceFile.toString()),
        )

        // TODO: Run as background process; currently blocks
        // For Phase 1, we'll launch trace_processor externally and connect to it
        isRunning = true
        StudioResult.Success(Unit)
    }

    suspend fun query(sql: String): StudioResult<String> = withContext(Dispatchers.IO) {
        if (!isRunning) {
            return@withContext StudioResult.Failure(
                StudioError(
                    category = ErrorCategory.CONFIGURATION,
                    code = "NOT_RUNNING",
                    message = "trace_processor is not running",
                )
            )
        }

        // Query via HTTP POST to localhost:${httpPort}/query
        // For Phase 1, we'll shell out to trace_processor --run-sql
        val request = ProcessRequest(
            executable = binary.path,
            arguments = listOf("--run-sql", sql, traceFile.toString()),
        )

        when (val result = processRunner.run(request)) {
            is ProcessRunResult.Completed -> StudioResult.Success(result.output.stdout.text)
            is ProcessRunResult.Failed -> StudioResult.Failure(result.error)
        }
    }

    fun stop() {
        cancellationSignal.cancel()
        isRunning = false
    }
}
