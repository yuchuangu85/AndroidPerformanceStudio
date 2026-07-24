@file:Suppress("MaxLineLength")

package com.androidperformancestudio.frame.parser

import com.androidperformancestudio.frame.model.ExpectedDurationSource
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.frame.model.FrameStages

public data class FrameStatsParseResult(
    val frames: List<FrameSample>,
    val warnings: List<String> = emptyList(),
)

public class GfxInfoFrameStatsParser {
    @Suppress("NestedBlockDepth")
    public fun parse(
        text: String,
        sessionId: String,
        packageName: String? = null,
    ): FrameStatsParseResult {
        val parsed = mutableListOf<FrameSample>()
        val warnings = mutableListOf<String>()
        var header: List<String>? = null
        var malformedRows = 0

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("Flags,") -> header = line.split(',').map(String::trim)
                header != null && line.isFrameDataRow() -> {
                    val columns = line.split(',').map(String::trim)
                    if (columns.size != header.size) {
                        malformedRows += 1
                    } else {
                        parseRow(
                            header = requireNotNull(header),
                            columns = columns,
                            frameId = parsed.size.toLong(),
                            sessionId = sessionId,
                            packageName = packageName,
                        )?.let(parsed::add) ?: run { malformedRows += 1 }
                    }
                }
            }
        }

        if (header == null) warnings += "No gfxinfo framestats header was found."
        if (malformedRows > 0) warnings += "$malformedRows malformed frame row(s) were skipped."
        if (parsed.isEmpty() && header != null) warnings += "The framestats section did not contain usable frames."

        return FrameStatsParseResult(
            frames = fillInferredExpectedDurations(parsed),
            warnings = warnings,
        )
    }

    private fun parseRow(
        header: List<String>,
        columns: List<String>,
        frameId: Long,
        sessionId: String,
        packageName: String?,
    ): FrameSample? {
        val values = header.zip(columns).associate { (name, value) -> name to value.toLongOrNull() }
        val flags = values["Flags"] ?: return null
        val intendedVsync = values["IntendedVsync"].positive()
        val actualVsync = values["Vsync"].positive()
        val completed = values["FrameCompleted"].positive()
        val present = values["DisplayPresentTime"].positive()
        val deadline = values["FrameDeadline"].positive()
        val interval = values["FrameInterval"].positive()
        val expected =
            positiveDifference(deadline, intendedVsync)?.let {
                it to ExpectedDurationSource.PLATFORM_DEADLINE
            } ?: interval?.let { it to ExpectedDurationSource.FRAME_INTERVAL }

        return FrameSample(
            frameId = frameId,
            sessionId = sessionId,
            source = FrameSource.GFXINFO,
            packageName = packageName,
            intendedVsyncNs = intendedVsync,
            actualVsyncNs = actualVsync,
            frameCompletedNs = completed,
            presentNs = present,
            expectedDurationNs = expected?.first,
            expectedDurationSource = expected?.second ?: ExpectedDurationSource.UNKNOWN,
            totalDurationNs = positiveDifference(completed ?: present, intendedVsync),
            stages =
                FrameStages(
                    inputNs = positiveDifference(values["AnimationStart"], values["HandleInputStart"]),
                    animationNs = positiveDifference(values["PerformTraversalsStart"], values["AnimationStart"]),
                    layoutMeasureNs = positiveDifference(values["DrawStart"], values["PerformTraversalsStart"]),
                    drawNs = positiveDifference(values["SyncQueued"], values["DrawStart"]),
                    syncNs = positiveDifference(values["IssueDrawCommandsStart"], values["SyncStart"]),
                    commandIssueNs = positiveDifference(values["SwapBuffers"], values["IssueDrawCommandsStart"]),
                    swapBuffersNs = positiveDifference(completed, values["SwapBuffers"]),
                    gpuNs = positiveDifference(values["GpuCompleted"], values["SwapBuffers"]),
                ),
            eligibleForJank = flags == 0L,
            states = if (flags == 0L) emptyMap() else mapOf("gfxinfo.flags" to flags.toString()),
        )
    }

    private fun fillInferredExpectedDurations(frames: List<FrameSample>): List<FrameSample> {
        val inferredInterval =
            frames
                .mapNotNull(FrameSample::intendedVsyncNs)
                .zipWithNext { previous, next -> next - previous }
                .filter { it in MIN_REFRESH_INTERVAL_NS..MAX_REFRESH_INTERVAL_NS }
                .sorted()
                .let { intervals -> intervals.getOrNull(intervals.size / 2) }
                ?: return frames

        return frames.map { frame ->
            if (frame.expectedDurationNs != null) {
                frame
            } else {
                frame.copy(
                    expectedDurationNs = inferredInterval,
                    expectedDurationSource = ExpectedDurationSource.INFERRED_VSYNC,
                )
            }
        }
    }

    private fun String.isFrameDataRow(): Boolean = substringBefore(',').toLongOrNull() != null

    private fun Long?.positive(): Long? = this?.takeIf { it > 0L }

    private fun positiveDifference(
        end: Long?,
        start: Long?,
    ): Long? =
        if (end == null || start == null) {
            null
        } else if (end <= 0L || start <= 0L || end < start) {
            null
        } else {
            end - start
        }

    private companion object {
        const val MIN_REFRESH_INTERVAL_NS = 4_000_000L
        const val MAX_REFRESH_INTERVAL_NS = 50_000_000L
    }
}
