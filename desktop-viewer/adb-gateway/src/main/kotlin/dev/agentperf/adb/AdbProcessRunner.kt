package dev.agentperf.adb

import java.util.concurrent.CompletableFuture

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

fun interface ProcessRunner {
    fun run(arguments: List<String>): ProcessResult
}

class AdbProcessRunner(
    private val executable: String = "adb",
) : ProcessRunner {
    override fun run(arguments: List<String>): ProcessResult {
        val process = ProcessBuilder(listOf(executable) + arguments).start()
        val stdout = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
        val stderr = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }
        val exitCode = process.waitFor()
        return ProcessResult(
            exitCode = exitCode,
            stdout = stdout.join(),
            stderr = stderr.join(),
        )
    }
}
