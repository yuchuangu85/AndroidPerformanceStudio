package com.androidperformancestudio.aiplatform.providers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Prompts use stdin and credentials use allowlisted environment overrides, never arguments. */
public class ProviderProcessRequest(
    public val executable: Path,
    arguments: List<String>,
    public val stdin: String = "",
    public val workDirectory: Path,
    public val timeoutMillis: Long,
    public val maxOutputBytes: Int,
    public val maxErrorBytes: Int = 16_384,
    inheritedEnvironmentNames: Set<String> = emptySet(),
    environment: Map<String, String> = emptyMap(),
) {
    public val arguments: List<String> = Collections.unmodifiableList(arguments.toList())
    public val inheritedEnvironmentNames: Set<String> = Collections.unmodifiableSet(inheritedEnvironmentNames.toSet())
    public val environment: Map<String, String> = Collections.unmodifiableMap(environment.toMap())

    init {
        require(executable.isAbsolute && timeoutMillis in 1..300_000)
        require(maxOutputBytes in 1..1_048_576 && maxErrorBytes in 1..65_536)
        require(this.arguments.size <= 64 && this.arguments.all { it.length <= 65_536 && '\u0000' !in it })
        require(stdin.toByteArray(Charsets.UTF_8).size <= 1_048_576)
        require(this.inheritedEnvironmentNames.all { it in ALLOWED_ENVIRONMENT })
        require(this.environment.all { (name, value) -> validConnectionEnvironment(name, value) }) { "E_PROVIDER_ENVIRONMENT" }
    }

    override fun toString(): String = "ProviderProcessRequest(redacted)"
}

private val ANTHROPIC_ENVIRONMENT = setOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL")
private val ALLOWED_ENVIRONMENT = ANTHROPIC_ENVIRONMENT + setOf("OPENAI_API_KEY", "CODEX_HOME")
private val BASE_ENVIRONMENT = setOf("PATH", "HOME", "USERPROFILE", "SystemRoot", "WINDIR", "TMPDIR", "TEMP", "TMP", "LANG", "LC_ALL")

private fun validConnectionEnvironment(
    name: String,
    value: String,
): Boolean {
    if (name !in ANTHROPIC_ENVIRONMENT ||
        value.isBlank() ||
        value.length > 16_384 ||
        value.toByteArray(Charsets.UTF_8).size > 16_384 ||
        value.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }
    ) {
        return false
    }
    if (name != "ANTHROPIC_BASE_URL") return true
    val uri =
        try {
            URI(value)
        } catch (_: Exception) {
            return false
        }
    return !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null &&
        uri.rawQuery == null &&
        uri.rawFragment == null &&
        (uri.port == -1 || uri.port in 1..65_535) &&
        (
            uri.scheme.equals("https", ignoreCase = true) ||
                (uri.scheme.equals("http", ignoreCase = true) && uri.host in setOf("localhost", "127.0.0.1", "[::1]"))
        )
}

public class ProviderProcessResult(
    public val exitCode: Int,
    public val stdout: String,
) {
    override fun toString(): String = "ProviderProcessResult(exitCode=$exitCode, stdout=redacted)"
}

public class ProviderProcessException(
    public val code: String,
) : IllegalStateException(code)

/** Process bounds are transport controls, not an OS sandbox and not a tool permission grant. */
public class ControlledProviderProcess {
    public suspend fun execute(request: ProviderProcessRequest): ProviderProcessResult =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            if (!Files.isRegularFile(request.executable) || !Files.isExecutable(request.executable)) {
                throw ProviderProcessException("E_PROVIDER_MISSING_RUNTIME")
            }
            val builder =
                ProcessBuilder(listOf(request.executable.toString()) + request.arguments)
                    .directory(request.workDirectory.toFile())
            builder.environment().apply {
                clear()
                (BASE_ENVIRONMENT + request.inheritedEnvironmentNames).forEach { name ->
                    System.getenv(name)?.let { put(name, it) }
                }
                putAll(request.environment)
            }
            val active =
                try {
                    builder.start()
                } catch (_: Exception) {
                    throw ProviderProcessException("E_PROVIDER_PROCESS_START")
                }
            // Dedicated bounded pumps cannot hold coroutine cancellation hostage on a blocking pipe.
            val pumps = Executors.newFixedThreadPool(3) { task -> Thread(task, "aps-provider-pipe").apply { isDaemon = true } }
            val ownedProcesses = mutableMapOf(active.pid() to active.toHandle())
            val stdout =
                pumps.submit<ByteArray> {
                    active.inputStream.use { readBounded(it, request.maxOutputBytes, "E_OUTPUT_LIMIT") }
                }
            val stderr =
                pumps.submit<ByteArray> {
                    active.errorStream.use { readBounded(it, request.maxErrorBytes, "E_PROVIDER_STDERR_LIMIT") }
                }
            val input = pumps.submit<Unit> { active.outputStream.use { it.write(request.stdin.toByteArray(Charsets.UTF_8)) } }
            try {
                val result =
                    withTimeoutOrNull(request.timeoutMillis) {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            trackDescendants(ownedProcesses)
                            // Observe overflow immediately, even when the child has not closed the pipe.
                            listOf(stdout, stderr, input).filter { it.isDone }.forEach { it.awaitResult() }
                            if (!active.isAlive && stdout.isDone && stderr.isDone && input.isDone) break
                            delay(10)
                        }
                        ProviderProcessResult(active.exitValue(), decode(stdout.awaitResult()))
                    }
                if (result == null) {
                    currentCoroutineContext().ensureActive()
                    throw ProviderProcessException("E_PROVIDER_TIMEOUT")
                }
                result
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: ProviderProcessException) {
                throw failure
            } catch (_: Exception) {
                throw ProviderProcessException("E_PROVIDER_PROCESS_IO")
            } finally {
                withContext(NonCancellable) {
                    try {
                        terminateOwnedProcesses(ownedProcesses)
                    } finally {
                        // Each pump owns its stream: closing a pipe here can block behind a pending write.
                        listOf(stdout, stderr, input).forEach { it.cancel(true) }
                        pumps.shutdownNow()
                        runCatching { pumps.awaitTermination(500, TimeUnit.MILLISECONDS) }
                    }
                }
            }
        }

    private fun trackDescendants(ownedProcesses: MutableMap<Long, ProcessHandle>) {
        // Retained handles also reveal children of a previously observed, reparented descendant.
        ownedProcesses.values.toList().forEach { process ->
            runCatching {
                process.descendants().use { stream -> stream.forEach { ownedProcesses[it.pid()] = it } }
            }
        }
    }

    private suspend fun terminateOwnedProcesses(ownedProcesses: MutableMap<Long, ProcessHandle>) {
        for (forcibly in listOf(false, true)) {
            val signalled = mutableSetOf<Long>()
            repeat(if (forcibly) 25 else 10) {
                trackDescendants(ownedProcesses)
                ownedProcesses.values.toList().asReversed().forEach { process ->
                    runCatching {
                        if (process.isAlive && signalled.add(process.pid())) {
                            // Process.destroy() also closes stdin synchronously and can deadlock with its writer.
                            if (forcibly) process.destroyForcibly() else process.destroy()
                        }
                    }
                }
                if (ownedProcesses.values.none { it.isAlive }) return
                delay(20)
            }
        }
    }

    private fun readBounded(
        input: InputStream,
        limit: Int,
        code: String,
    ): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size().toLong() + count > limit) throw ProviderProcessException(code)
            output.write(buffer, 0, count)
        }
    }

    private fun decode(bytes: ByteArray): String =
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: Exception) {
            throw ProviderProcessException("E_PROVIDER_PROTOCOL")
        }

    private fun <T> Future<T>.awaitResult(): T =
        try {
            get()
        } catch (failure: ExecutionException) {
            val cause = failure.cause
            if (cause is ProviderProcessException) throw cause
            throw ProviderProcessException("E_PROVIDER_PROCESS_IO")
        }
}
