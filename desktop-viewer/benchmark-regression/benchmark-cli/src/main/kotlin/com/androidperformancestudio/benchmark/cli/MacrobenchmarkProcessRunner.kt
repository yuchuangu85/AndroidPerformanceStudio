package com.androidperformancestudio.benchmark.cli

import java.nio.file.Files
import java.util.concurrent.TimeUnit

public data class MacrobenchmarkProcessResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
)

public class MacrobenchmarkProcessRunner(
    private val timeoutSeconds: Long = 60L * 60L,
) {
    public fun run(command: MacrobenchmarkCommand): MacrobenchmarkProcessResult {
        val process =
            ProcessBuilder(listOf(command.executable.toString()) + command.arguments)
                .directory(command.workingDirectory.toFile())
                .redirectErrorStream(true)
                .apply { environment().putAll(command.environment) }
                .start()
        val outputFile = Files.createTempFile("aps-macrobenchmark", ".log").toFile()
        return try {
            process.inputStream.use { input -> outputFile.outputStream().use { output -> input.copyTo(output) } }
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                MacrobenchmarkProcessResult(-1, outputFile.readText(), timedOut = true)
            } else {
                MacrobenchmarkProcessResult(process.exitValue(), outputFile.readText(), timedOut = false)
            }
        } finally {
            outputFile.delete()
        }
    }
}
