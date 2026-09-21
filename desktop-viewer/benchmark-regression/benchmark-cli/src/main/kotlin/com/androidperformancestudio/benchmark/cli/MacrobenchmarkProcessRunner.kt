package com.androidperformancestudio.benchmark.cli

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

public data class MacrobenchmarkProcessResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
)

public class MacrobenchmarkProcessRunner(
    private val timeoutSeconds: Long = 60L * 60L,
) {
    init {
        require(timeoutSeconds > 0) { "timeoutSeconds must be positive" }
    }

    /**
     * Drains stdout concurrently with the process wait. Reading the complete stream before
     * waitFor would make the timeout ineffective for a noisy or hung Gradle process.
     */
    public fun run(command: MacrobenchmarkCommand): MacrobenchmarkProcessResult {
        val process =
            ProcessBuilder(listOf(command.executable.toString()) + command.arguments)
                .directory(command.workingDirectory.toFile())
                .redirectErrorStream(true)
                .apply { environment().putAll(command.environment) }
                .start()
        val output = StringBuilder()
        val reader =
            Thread(
                {
                    process.inputStream.use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            synchronized(output) { output.append(String(buffer, 0, read, StandardCharsets.UTF_8)) }
                        }
                    }
                },
                "aps-macrobenchmark-output",
            ).apply { isDaemon = true }
        reader.start()
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        reader.join(OUTPUT_DRAIN_TIMEOUT_MILLIS)
        if (!completed) {
            // Gradle may leave child processes behind; preserve whatever output was drained before
            // forcibly terminating the wrapper and return a stable timeout exit code.
            return MacrobenchmarkProcessResult(-1, synchronized(output) { output.toString() }, timedOut = true)
        }
        return MacrobenchmarkProcessResult(process.exitValue(), synchronized(output) { output.toString() }, timedOut = false)
    }

    private companion object {
        const val OUTPUT_DRAIN_TIMEOUT_MILLIS = 5_000L
    }
}
