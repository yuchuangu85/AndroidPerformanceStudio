package com.androidperformancestudio.perfetto.traceprocessor

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Manages a trace_processor subprocess in `server http` mode.
 * The Perfetto UI connects to localhost:9001 for native trace parsing acceleration.
 */
class TraceProcessorSession(
    private val binary: TraceProcessorBinary,
    private val traceFile: Path,
    val httpPort: Int = 9001,
    private val processRunner: JvmProcessRunner = JvmProcessRunner(),
) {
    private var process: Process? = null
    private var serverLog: Path? = null

    var isRunning: Boolean = false
        private set

    suspend fun start(): StudioResult<Unit> =
        withContext(Dispatchers.IO) {
            if (isRunning) {
                return@withContext StudioResult.Failure(
                    StudioError(
                        category = ErrorCategory.CONFIGURATION,
                        code = "ALREADY_RUNNING",
                        message = "trace_processor is already running on port $httpPort",
                    ),
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
                val logFile = Files.createTempFile("aps-trace-processor", ".log")
                serverLog = logFile
                process =
                    ProcessBuilder(
                        binary.path.toString(),
                        "server",
                        "http",
                        "--port",
                        httpPort.toString(),
                        traceFile.toString(),
                    ).redirectErrorStream(true)
                        .redirectOutput(logFile.toFile())
                        .start()
                val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
                while (System.nanoTime() < deadline && process?.isAlive == true) {
                    if (isHttpReady()) {
                        isRunning = true
                        return@withContext StudioResult.Success(Unit)
                    }
                    Thread.sleep(50)
                }
                val failedProcess = process
                failedProcess?.destroy()
                if (failedProcess?.isAlive == true) failedProcess.destroyForcibly()
                failedProcess?.waitFor()
                val output = runCatching { Files.readString(logFile) }.getOrDefault("").take(1000)
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

    suspend fun query(sql: String): StudioResult<String> =
        withContext(Dispatchers.IO) {
            if (!isRunning) {
                return@withContext StudioResult.Failure(
                    StudioError(
                        category = ErrorCategory.CONFIGURATION,
                        code = "NOT_RUNNING",
                        message = "trace_processor is not running",
                    ),
                )
            }

            // The v57 HTTP API is protobuf RPC, not JSON. Keep the server for
            // Perfetto UI acceleration and use the matching CLI query command
            // here so diagnostics remain portable without generated protobufs.
            when (
                val result =
                    processRunner.run(
                        ProcessRequest(
                            executable = binary.path,
                            arguments = listOf("query", traceFile.toString(), sql),
                            timeout = 30.seconds,
                            maxCapturedCharactersPerStream = 8 * 1024 * 1024,
                        ),
                    )
            ) {
                is ProcessRunResult.Completed -> StudioResult.Success(result.output.stdout.text)
                is ProcessRunResult.Failed -> {
                    val output = result.output?.let { it.stderr.text.ifBlank { it.stdout.text } }.orEmpty()
                    StudioResult.Failure(
                        StudioError(
                            category = result.error.category,
                            code = if (result.error.code == "PROCESS_TIMED_OUT") "TRACE_QUERY_TIMEOUT" else "TRACE_QUERY_FAILED",
                            message = output.ifBlank { result.error.message },
                            cause = result.error.cause,
                        ),
                    )
                }
            }
        }

    fun stop() {
        process?.destroy()
        if (process?.isAlive == true) process?.destroyForcibly()
        process = null
        serverLog?.let { runCatching { Files.deleteIfExists(it) } }
        serverLog = null
        isRunning = false
    }

    private fun isHttpReady(): Boolean =
        runCatching {
            val connection = URI.create("http://127.0.0.1:$httpPort/status").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 150
            connection.readTimeout = 150
            connection.requestMethod = "GET"
            connection.responseCode == HttpURLConnection.HTTP_OK
        }.getOrDefault(false)
}
