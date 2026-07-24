package com.androidperformancestudio.frame.capture

import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.parser.GfxInfoFrameStatsParser
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

public data class GfxInfoCaptureTarget(
    val serial: String,
    val packageName: String,
    val processId: Int,
)

public data class GfxInfoPollBatch(
    val frames: List<FrameSample>,
    val warnings: List<String> = emptyList(),
)

public class GfxInfoCaptureException(
    message: String,
) : IllegalStateException(message)

internal fun interface AdbShellRunner {
    suspend fun execute(
        serial: String,
        arguments: List<String>,
    ): String
}

public class GfxInfoPollingCaptureSession internal constructor(
    private val target: GfxInfoCaptureTarget,
    private val sessionId: String,
    private val runner: AdbShellRunner,
    private val parser: GfxInfoFrameStatsParser = GfxInfoFrameStatsParser(),
) {
    public constructor(
        adbExecutable: Path,
        target: GfxInfoCaptureTarget,
        sessionId: String,
    ) : this(
        target = target,
        sessionId = sessionId,
        runner = JvmAdbShellRunner(adbExecutable),
    )

    private val seenFrames = hashSetOf<FrameIdentity>()
    private var nextFrameId = 0L

    public suspend fun start(): List<String> {
        seenFrames.clear()
        nextFrameId = 0L
        return runCatching {
            runner.execute(
                serial = target.serial,
                arguments = listOf("dumpsys", "gfxinfo", target.packageName, "reset"),
            )
        }.exceptionOrNull()
            ?.let { error ->
                listOf(
                    "Unable to reset existing gfxinfo data; " +
                        "the initial poll may include older frames: ${error.message}",
                )
            }.orEmpty()
    }

    public suspend fun poll(): GfxInfoPollBatch {
        val output =
            runner.execute(
                serial = target.serial,
                arguments = listOf("dumpsys", "gfxinfo", target.packageName, "framestats"),
            )
        val parsed = parser.parse(output, sessionId = sessionId, packageName = target.packageName)
        val newFrames =
            parsed.frames.mapNotNull { frame ->
                val identity = frame.identity()
                if (!seenFrames.add(identity)) {
                    null
                } else {
                    frame.copy(
                        frameId = nextFrameId++,
                        processId = target.processId,
                    )
                }
            }
        return GfxInfoPollBatch(frames = newFrames, warnings = parsed.warnings)
    }

    private fun FrameSample.identity(): FrameIdentity =
        FrameIdentity(
            intendedVsyncNs = intendedVsyncNs,
            frameCompletedNs = frameCompletedNs,
            presentNs = presentNs,
            totalDurationNs = totalDurationNs,
        )

    private data class FrameIdentity(
        val intendedVsyncNs: Long?,
        val frameCompletedNs: Long?,
        val presentNs: Long?,
        val totalDurationNs: Long?,
    )
}

private class JvmAdbShellRunner(
    private val adbExecutable: Path,
    private val processRunner: JvmProcessRunner = JvmProcessRunner(),
) : AdbShellRunner {
    override suspend fun execute(
        serial: String,
        arguments: List<String>,
    ): String {
        val request =
            ProcessRequest(
                executable = adbExecutable,
                arguments = listOf("-s", serial, "shell") + arguments,
                timeout = COMMAND_TIMEOUT,
                maxCapturedCharactersPerStream = MAX_FRAMESTATS_CHARACTERS,
            )
        return when (val result = processRunner.run(request)) {
            is ProcessRunResult.Completed -> result.output.stdout.text
            is ProcessRunResult.Failed -> {
                val stderr =
                    result.output
                        ?.stderr
                        ?.text
                        .orEmpty()
                        .trim()
                val detail = stderr.ifEmpty { result.error.message }
                throw GfxInfoCaptureException(detail)
            }
        }
    }

    private companion object {
        val COMMAND_TIMEOUT = 10.seconds
        const val MAX_FRAMESTATS_CHARACTERS = 8 * 1024 * 1024
    }
}
