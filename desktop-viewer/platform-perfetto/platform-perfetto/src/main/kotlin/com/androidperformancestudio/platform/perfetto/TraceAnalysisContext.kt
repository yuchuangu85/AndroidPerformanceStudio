package com.androidperformancestudio.platform.perfetto

import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.contracts.ArtifactFileEvidence
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.toolchain.HostProcessCancelledException
import com.androidperformancestudio.platform.toolchain.HostProcessLaunchRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRunner
import com.androidperformancestudio.platform.toolchain.HostProcessStartException
import com.androidperformancestudio.platform.toolchain.HostProcessTimeoutException
import com.androidperformancestudio.platform.toolchain.JvmHostProcessRunner
import com.androidperformancestudio.platform.toolchain.RunningHostProcess
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.deleteIfExists
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public data class TraceProcessorTool(
    public val path: Path,
    public val version: String,
    public val sha256: String,
) {
    init {
        require(version == TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION) {
            "trace processor version must match the pinned schema"
        }
        require(SHA_256.matches(sha256)) { "trace processor SHA-256 must be lowercase hexadecimal" }
    }

    private companion object {
        private val SHA_256: Regex = Regex("[0-9a-f]{64}")
    }
}

public class TraceAnalysisContexts(
    private val tool: TraceProcessorTool,
    private val processRunner: HostProcessRunner = JvmHostProcessRunner(),
    private val startupTimeout: Duration = 10.seconds,
    private val isReady: suspend (Int) -> Boolean = ::isTraceProcessorReady,
) {
    private val contexts: ConcurrentHashMap<String, TraceAnalysisContext> = ConcurrentHashMap()
    private val openMutex: Mutex = Mutex()

    public suspend fun open(
        artifact: CaptureArtifact,
        traceFile: Path,
    ): StudioResult<TraceAnalysisContext> =
        openMutex.withLock {
            contexts[artifact.id.value]?.let { return@withLock StudioResult.Success(it) }
            if (!Files.isRegularFile(traceFile)) {
                return@withLock failure("TRACE_FILE_NOT_FOUND", "Trace file does not exist")
            }
            if (ArtifactFileEvidence.sha256(traceFile) != artifact.sha256) {
                return@withLock failure(
                    "TRACE_ARTIFACT_HASH_MISMATCH",
                    "Trace bytes no longer match the registered Capture Artifact",
                )
            }

            val context =
                TraceAnalysisContext(
                    tool = tool,
                    traceFile = traceFile,
                    port = PrivatePortAllocator.acquire(),
                    processRunner = processRunner,
                    startupTimeout = startupTimeout,
                    isReady = isReady,
                    onClose = { contexts.remove(artifact.id.value, it) },
                )
            when (val started = context.start()) {
                is StudioResult.Success -> {
                    contexts[artifact.id.value] = context
                    StudioResult.Success(context)
                }
                is StudioResult.Failure -> {
                    context.close()
                    started
                }
            }
        }
}

public class TraceAnalysisContext internal constructor(
    private val tool: TraceProcessorTool,
    private val traceFile: Path,
    internal val port: Int,
    private val processRunner: HostProcessRunner,
    private val startupTimeout: Duration,
    private val isReady: suspend (Int) -> Boolean,
    private val onClose: (TraceAnalysisContext) -> Unit,
) : AutoCloseable {
    private val closed: AtomicBoolean = AtomicBoolean(false)
    private val workDirectory: Path = Files.createTempDirectory("aps-trace-context-")
    private var server: RunningHostProcess? = null

    internal suspend fun start(): StudioResult<Unit> =
        try {
            server =
                processRunner.launch(
                    HostProcessLaunchRequest(
                        executable = tool.path,
                        arguments =
                            listOf(
                                "server",
                                "http",
                                "--ip-address",
                                "127.0.0.1",
                                "--port",
                                port.toString(),
                                traceFile.toString(),
                            ),
                        outputFile = workDirectory.resolve("trace-processor.log"),
                    ),
                )
            val deadline = System.nanoTime() + startupTimeout.inWholeNanoseconds
            while (System.nanoTime() < deadline && server?.isAlive == true) {
                if (isReady(port)) return StudioResult.Success(Unit)
                delay(50)
            }
            failure("TRACE_PROCESSOR_NOT_READY", "Trace processor did not become ready")
        } catch (error: CancellationException) {
            close()
            throw error
        } catch (error: HostProcessStartException) {
            failure("TRACE_PROCESSOR_START_FAILED", error.message.orEmpty())
        } catch (error: Exception) {
            failure("TRACE_PROCESSOR_START_FAILED", error.message ?: "Trace processor could not start")
        }

    public suspend fun <T> query(query: TraceQuery<T>): StudioResult<List<T>> {
        if (query.schema.traceProcessorVersion != tool.version) {
            return failure("TRACE_SCHEMA_INCOMPATIBLE", "Query schema does not match the pinned trace processor")
        }
        return when (val result = queryRaw(query.sql)) {
            is StudioResult.Success ->
                try {
                    StudioResult.Success(query.map(result.value))
                } catch (error: IllegalArgumentException) {
                    failure("TRACE_QUERY_RESULT_INVALID", error.message ?: "Trace query returned invalid data")
                }
            is StudioResult.Failure -> result
        }
    }

    /** Executes SQL against this artifact-scoped context when the result columns are discovered at runtime. */
    public suspend fun queryRaw(sql: String): StudioResult<TraceQueryResult> {
        if (closed.get() || server?.isAlive != true) return failure("TRACE_CONTEXT_CLOSED", "Trace analysis context is closed")
        if (sql.isBlank()) return failure("TRACE_QUERY_INVALID", "Trace SQL must not be blank")
        return try {
            val result =
                processRunner.executeText(
                    HostProcessRequest(
                        executable = tool.path,
                        // Query the artifact-scoped warm context rather than launching another
                        // processor which would parse the same Capture Artifact again.
                        arguments = listOf("query", "--remote", "127.0.0.1:$port", sql),
                    ),
                )
            if (result.exitCode != 0) return failure("TRACE_QUERY_FAILED", "Trace query failed with exit code ${result.exitCode}")
            StudioResult.Success(TraceQueryResult.parse(result.stdout))
        } catch (error: HostProcessTimeoutException) {
            failure("TRACE_QUERY_TIMEOUT", "Trace query timed out")
        } catch (error: HostProcessCancelledException) {
            failure("TRACE_QUERY_CANCELLED", "Trace query was cancelled")
        } catch (error: HostProcessStartException) {
            failure("TRACE_QUERY_START_FAILED", error.message.orEmpty())
        } catch (error: IllegalArgumentException) {
            failure("TRACE_QUERY_RESULT_INVALID", error.message ?: "Trace query returned invalid data")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server?.close()
        server = null
        workDirectory.deleteRecursively()
        PrivatePortAllocator.release(port)
        onClose(this)
    }

    private fun Path.deleteRecursively() {
        if (!Files.exists(this)) return
        Files.walk(this).use { files ->
            files.sorted(Comparator.reverseOrder()).forEach(Path::deleteIfExists)
        }
    }
}

private fun <T> failure(
    code: String,
    message: String,
): StudioResult<T> =
    StudioResult.Failure(
        StudioError(
            category = ErrorCategory.PROCESS_EXIT,
            code = code,
            message = message,
        ),
    )

private object PrivatePortAllocator {
    private val ports: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    fun acquire(): Int =
        generateSequence {
            ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use(ServerSocket::getLocalPort)
        }.first { ports.add(it) }

    fun release(port: Int) {
        ports.remove(port)
    }
}

private suspend fun isTraceProcessorReady(port: Int): Boolean =
    runCatching {
        val connection = URI.create("http://127.0.0.1:$port/status").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 150
        connection.readTimeout = 150
        connection.responseCode == HttpURLConnection.HTTP_OK
    }.getOrDefault(false)
