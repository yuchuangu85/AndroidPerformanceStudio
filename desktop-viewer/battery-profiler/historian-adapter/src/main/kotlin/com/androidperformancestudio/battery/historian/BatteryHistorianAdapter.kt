@file:Suppress("MaxLineLength")

package com.androidperformancestudio.battery.historian

import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

public data class BugreportArtifact(
    val path: Path,
    val sizeBytes: Long,
)

public fun interface BugreportCommandRunner {
    public suspend fun execute(arguments: List<String>): Unit
}

public class BatteryHistorianAdapter(
    private val adbExecutable: Path,
    private val serial: String,
    private val runner: BugreportCommandRunner = JvmBugreportCommandRunner(adbExecutable),
) {
    public suspend fun generateBugreport(output: Path): BugreportArtifact {
        require(output.fileName.toString().endsWith(".zip", ignoreCase = true)) { "Bugreport output must use the .zip extension" }
        output.toAbsolutePath().parent?.let(Files::createDirectories)
        runner.execute(listOf("-s", serial, "bugreport", output.toAbsolutePath().toString()))
        require(Files.isRegularFile(output)) { "ADB completed without producing the bugreport file" }
        return BugreportArtifact(output.toAbsolutePath(), Files.size(output))
    }

    public fun historianUploadUri(
        serviceUrl: String,
        artifact: BugreportArtifact,
    ): String {
        val normalized = serviceUrl.trim().removeSuffix("/")
        require(normalized.startsWith("http://") || normalized.startsWith("https://")) { "Historian service URL must use HTTP or HTTPS" }
        require(artifact.sizeBytes > 0) { "Bugreport file is empty" }
        return "$normalized/"
    }
}

private class JvmBugreportCommandRunner(
    private val adbExecutable: Path,
    private val processRunner: JvmProcessRunner = JvmProcessRunner(),
) : BugreportCommandRunner {
    override suspend fun execute(arguments: List<String>) {
        val request =
            ProcessRequest(
                executable = adbExecutable,
                arguments = arguments,
                timeout = BUGREPORT_TIMEOUT,
                maxCapturedCharactersPerStream = MAX_OUTPUT,
            )
        when (val result = processRunner.run(request)) {
            is ProcessRunResult.Completed -> Unit
            is ProcessRunResult.Failed -> {
                val detail =
                    result.output
                        ?.stderr
                        ?.text
                        ?.trim()
                        .orEmpty()
                        .ifEmpty { result.error.message }
                throw IllegalStateException(detail)
            }
        }
    }

    private companion object {
        val BUGREPORT_TIMEOUT = 10.minutes
        const val MAX_OUTPUT = 4 * 1024 * 1024
    }
}
