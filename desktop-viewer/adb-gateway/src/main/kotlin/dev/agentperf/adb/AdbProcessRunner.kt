package dev.agentperf.adb

import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val stdoutBytes: ByteArray = stdout.toByteArray(StandardCharsets.UTF_8),
)

fun interface ProcessRunner {
    fun run(arguments: List<String>): ProcessResult
}

class AdbProcessRunner(
    private val executable: String = "adb",
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : ProcessRunner {
    override fun run(arguments: List<String>): ProcessResult {
        val process = ProcessBuilder(listOf(executable) + arguments).start()
        val stdout = CompletableFuture.supplyAsync { process.inputStream.readBytes() }
        val stderr = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }
        val completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!completed) {
            process.destroyForcibly().waitFor()
        }
        val stdoutBytes = stdout.join()
        return ProcessResult(
            exitCode = if (completed) process.exitValue() else TIMEOUT_EXIT_CODE,
            stdout = stdoutBytes.toString(StandardCharsets.UTF_8),
            stderr = if (completed) {
                stderr.join()
            } else {
                "${stderr.join()}\nProcess timed out after ${timeoutMillis}ms".trim()
            },
            stdoutBytes = stdoutBytes,
        )
    }

    companion object {
        const val TIMEOUT_EXIT_CODE = -1
        private const val DEFAULT_TIMEOUT_MILLIS = 15_000L
    }
}
