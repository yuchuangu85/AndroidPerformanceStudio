@file:Suppress("MagicNumber", "MaxLineLength", "ktlint:standard:max-line-length")

package com.androidperformancestudio.capture

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class LibSimpleperfReportResult(
    val output: Path,
    val stdout: String,
    val backend: String = "libsimpleperf_report",
)

data class LibSimpleperfBridgeResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
)

fun interface LibSimpleperfBridgeRunner {
    fun run(
        command: List<String>,
        timeoutSeconds: Long,
    ): LibSimpleperfBridgeResult
}

class LibSimpleperfReportAdapter(
    private val library: Path,
    private val bridgeExecutable: Path,
    private val runner: LibSimpleperfBridgeRunner = ProcessLibSimpleperfBridgeRunner,
) {
    fun convert(
        perfData: Path,
        outputProtobuf: Path,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): LibSimpleperfReportResult {
        require(Files.isRegularFile(library)) { "libsimpleperf_report does not exist: $library" }
        require(Files.isRegularFile(bridgeExecutable)) { "libsimpleperf_report bridge does not exist: $bridgeExecutable" }
        require(Files.isRegularFile(perfData)) { "perf.data does not exist: $perfData" }
        require(timeoutSeconds > 0) { "timeoutSeconds must be positive" }
        val normalizedOutput = outputProtobuf.toAbsolutePath().normalize()
        require(normalizedOutput != library.toAbsolutePath().normalize()) { "output must not overwrite libsimpleperf_report" }
        require(normalizedOutput != bridgeExecutable.toAbsolutePath().normalize()) { "output must not overwrite the bridge executable" }
        require(normalizedOutput != perfData.toAbsolutePath().normalize()) { "output must not overwrite perf.data" }
        normalizedOutput.parent?.let(Files::createDirectories)
        Files.deleteIfExists(normalizedOutput)
        val command =
            listOf(
                bridgeExecutable.toString(),
                "--library",
                library.toString(),
                "--record",
                perfData.toString(),
                "--output",
                normalizedOutput.toString(),
                "--format",
                "simpleperf-protobuf",
            )
        val result = runner.run(command, timeoutSeconds)
        check(!result.timedOut) { "libsimpleperf_report conversion timed out" }
        check(result.exitCode == 0) { "libsimpleperf_report conversion failed (${result.exitCode}): ${result.output}" }
        check(Files.isRegularFile(normalizedOutput)) { "libsimpleperf_report bridge did not create $normalizedOutput" }
        return LibSimpleperfReportResult(normalizedOutput, result.output)
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 300L

        fun discover(directory: Path): Path? {
            val names =
                when {
                    System
                        .getProperty(
                            "os.name",
                        ).startsWith("Windows", ignoreCase = true) -> listOf("simpleperf_report.dll", "libsimpleperf_report.dll")
                    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> listOf("libsimpleperf_report.dylib")
                    else -> listOf("libsimpleperf_report.so")
                }
            return names.asSequence().map(directory::resolve).firstOrNull(Files::isRegularFile)
        }
    }
}

private object ProcessLibSimpleperfBridgeRunner : LibSimpleperfBridgeRunner {
    override fun run(
        command: List<String>,
        timeoutSeconds: Long,
    ): LibSimpleperfBridgeResult {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader =
            Thread {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach { output.appendLine(it) } }
            }.apply { isDaemon = true }
        reader.start()
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        reader.join(5_000)
        return LibSimpleperfBridgeResult(if (completed) process.exitValue() else -1, output.toString(), !completed)
    }
}
