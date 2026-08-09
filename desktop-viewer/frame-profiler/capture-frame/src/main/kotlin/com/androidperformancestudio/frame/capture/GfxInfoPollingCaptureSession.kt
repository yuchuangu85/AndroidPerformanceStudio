package com.androidperformancestudio.frame.capture

import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.parser.GfxInfoFrameStatsParser
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbException
import com.androidperformancestudio.platform.adb.DefaultAdbClient
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
        runner = TypedAdbShellRunner(DefaultAdbClient(adbExecutable)),
    )

    private val seenFrames = linkedMapOf<FrameIdentity, Long>()
    private var nextFrameId = 0L
    private var hasPolled = false

    public suspend fun start(): List<String> {
        seenFrames.clear()
        nextFrameId = 0L
        hasPolled = false
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
        var overlappingFrames = 0
        val newFrames =
            parsed.frames.mapNotNull { frame ->
                val identity = frame.identity()
                if (identity in seenFrames) {
                    overlappingFrames += 1
                    null
                } else {
                    seenFrames[identity] = frame.intendedVsyncNs ?: nextFrameId
                    frame.copy(
                        frameId = nextFrameId++,
                        processId = target.processId,
                    )
                }
            }
        pruneSeenFrames()
        val windowWarning =
            if (hasPolled && parsed.frames.size >= FRAMESTATS_WINDOW_WARNING_SIZE && overlappingFrames == 0) {
                listOf("The gfxinfo window had no overlap with the previous poll; frames may have been overwritten.")
            } else {
                emptyList()
            }
        hasPolled = true
        return GfxInfoPollBatch(frames = newFrames, warnings = parsed.warnings + windowWarning)
    }

    private fun pruneSeenFrames() {
        val newestTimestamp = seenFrames.values.maxOrNull() ?: return
        val oldestTimestamp = newestTimestamp - DEDUPLICATION_WINDOW_NS
        seenFrames.entries.removeAll { (_, timestamp) -> timestamp < oldestTimestamp }
        while (seenFrames.size > MAX_DEDUPLICATION_IDENTITIES) seenFrames.remove(seenFrames.keys.first())
    }

    private fun FrameSample.identity(): FrameIdentity =
        FrameIdentity(
            windowId = windowId,
            intendedVsyncNs = intendedVsyncNs,
            frameCompletedNs = frameCompletedNs,
            presentNs = presentNs,
            totalDurationNs = totalDurationNs,
        )

    private data class FrameIdentity(
        val windowId: String?,
        val intendedVsyncNs: Long?,
        val frameCompletedNs: Long?,
        val presentNs: Long?,
        val totalDurationNs: Long?,
    )

    private companion object {
        const val FRAMESTATS_WINDOW_WARNING_SIZE = 110
        const val DEDUPLICATION_WINDOW_NS = 5_000_000_000L
        const val MAX_DEDUPLICATION_IDENTITIES = 1_024
    }
}

private class TypedAdbShellRunner(
    private val adbClient: AdbClient,
) : AdbShellRunner {
    override suspend fun execute(
        serial: String,
        arguments: List<String>,
    ): String =
        try {
            adbClient
                .shell(
                    serial = serial,
                    arguments = arguments,
                    timeout = COMMAND_TIMEOUT,
                    maxOutputBytesPerStream = MAX_FRAMESTATS_CHARACTERS,
                ).stdout
        } catch (error: AdbException) {
            throw GfxInfoCaptureException(error.message ?: "ADB shell command failed").also { it.initCause(error) }
        }

    private companion object {
        val COMMAND_TIMEOUT = 10.seconds
        const val MAX_FRAMESTATS_CHARACTERS = 8 * 1024 * 1024
    }
}
