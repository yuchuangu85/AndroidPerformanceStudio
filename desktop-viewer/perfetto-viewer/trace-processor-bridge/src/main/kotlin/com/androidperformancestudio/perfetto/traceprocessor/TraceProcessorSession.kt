package com.androidperformancestudio.perfetto.traceprocessor

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Manages a trace_processor subprocess in `server http` mode.
 * The Perfetto UI connects to localhost:9001 for native trace parsing acceleration.
 */
class TraceProcessorSession(
    private val binary: TraceProcessorBinary,
    private val traceFile: Path,
    val httpPort: Int = 9001,
) {
    private var process: Process? = null

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

        if (!Files.isRegularFile(traceFile)) {
            return@withContext StudioResult.Failure(
                StudioError(
                    category = ErrorCategory.IO,
                    code = "TRACE_FILE_NOT_FOUND",
                    message = "Trace file does not exist: $traceFile",
                ),
            )
        }
        try {
            process = ProcessBuilder(
                binary.path.toString(),
                "server",
                "http",
                "--port",
                httpPort.toString(),
                traceFile.toString(),
            ).redirectErrorStream(true).start()
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            while (System.nanoTime() < deadline && process?.isAlive == true) {
                if (isHttpReady()) {
                    isRunning = true
                    return@withContext StudioResult.Success(Unit)
                }
                Thread.sleep(50)
            }
            val output = process?.inputStream?.bufferedReader()?.readText().orEmpty().take(1000)
            stop()
            StudioResult.Failure(
                StudioError(
                    category = ErrorCategory.PROCESS_START,
                    code = "TRACE_PROCESSOR_NOT_READY",
                    message = "trace_processor did not become ready${output.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}",
                ),
            )
        } catch (exception: Exception) {
            stop()
            StudioResult.Failure(
                StudioError(
                    category = ErrorCategory.PROCESS_START,
                    code = "TRACE_PROCESSOR_START_FAILED",
                    message = exception.message ?: "Failed to start trace_processor",
                    cause = exception,
                ),
            )
        }
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

        try {
            val queryProcess = ProcessBuilder(binary.path.toString(), "query", traceFile.toString(), sql)
                .redirectErrorStream(true)
                .start()
            val response = queryProcess.inputStream.bufferedReader().readText()
            if (!queryProcess.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                queryProcess.destroyForcibly()
                return@withContext StudioResult.Failure(
                    StudioError(ErrorCategory.PROCESS_EXIT, "TRACE_QUERY_TIMEOUT", "trace_processor query timed out"),
                )
            }
            if (queryProcess.exitValue() != 0) {
                return@withContext StudioResult.Failure(
                    StudioError(ErrorCategory.PROCESS_EXIT, "TRACE_QUERY_FAILED", response.ifBlank { "trace_processor query failed" }),
                )
            }
            StudioResult.Success(response)
        } catch (exception: Exception) {
            StudioResult.Failure(
                StudioError(ErrorCategory.IO, "TRACE_QUERY_FAILED", exception.message ?: "trace_processor query failed", exception),
            )
        }
    }

    fun stop() {
        process?.destroy()
        if (process?.isAlive == true) process?.destroyForcibly()
        process = null
        isRunning = false
    }

    private fun isHttpReady(): Boolean = runCatching {
        val connection = URI.create("http://127.0.0.1:$httpPort/status").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 150
        connection.readTimeout = 150
        connection.requestMethod = "GET"
        connection.responseCode in 200..499
    }.getOrDefault(false)

}
