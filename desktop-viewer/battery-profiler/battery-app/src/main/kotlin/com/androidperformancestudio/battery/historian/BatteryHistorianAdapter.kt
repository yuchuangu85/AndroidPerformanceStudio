@file:Suppress("MaxLineLength")

package com.androidperformancestudio.battery.historian

import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

public data class BugreportArtifact(
    val path: Path,
    val sizeBytes: Long,
)

public fun interface BugreportCommandRunner {
    public suspend fun execute(
        serial: String,
        output: Path,
    ): Unit
}

public class BatteryHistorianAdapter(
    private val serial: String,
    private val runner: BugreportCommandRunner,
) {
    public constructor(
        adbExecutable: Path,
        serial: String,
    ) : this(serial, AdbBugreportCommandRunner(DefaultAdbClient(adbExecutable)))

    public constructor(
        adbExecutable: Path,
        serial: String,
        runner: BugreportCommandRunner,
    ) : this(serial, runner)

    public suspend fun generateBugreport(output: Path): BugreportArtifact {
        require(output.fileName.toString().endsWith(".zip", ignoreCase = true)) { "Bugreport output must use the .zip extension" }
        output.toAbsolutePath().parent?.let(Files::createDirectories)
        runner.execute(serial, output.toAbsolutePath())
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

private class AdbBugreportCommandRunner(
    private val adbClient: AdbClient,
) : BugreportCommandRunner {
    override suspend fun execute(
        serial: String,
        output: Path,
    ) {
        adbClient.bugreport(
            serial = serial,
            outputPath = output,
            timeout = BUGREPORT_TIMEOUT,
            maxOutputBytesPerStream = MAX_OUTPUT,
        )
    }

    private companion object {
        val BUGREPORT_TIMEOUT = 10.minutes
        const val MAX_OUTPUT = 4 * 1024 * 1024
    }
}
