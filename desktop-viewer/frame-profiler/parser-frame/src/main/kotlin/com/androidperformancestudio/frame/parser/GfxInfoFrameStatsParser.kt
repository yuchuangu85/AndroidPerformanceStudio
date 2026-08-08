@file:Suppress("CyclomaticComplexMethod", "MaxLineLength", "NestedBlockDepth")

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
    public fun parse(
        text: String,
        sessionId: String,
        packageName: String? = null,
    ): FrameStatsParseResult {
        val parsed = mutableListOf<FrameSample>()
        val warnings = mutableListOf<String>()
        var header: List<String>? = null
        var windowId: String? = null
        var inProfileData = false
        var foundHeader = false
        var malformedRows = 0
        val unknownColumns = linkedSetOf<String>()

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("Window:") -> windowId = line.substringAfter(':').trim().ifEmpty { null }
                line == "---PROFILEDATA---" -> {
                    inProfileData = !inProfileData
                    if (!inProfileData) header = null
                }
                inProfileData && line.startsWith("Flags,") -> {
                    header = line.split(',').map(String::trim)
                    foundHeader = true
                }
                inProfileData && header != null && line.isFrameDataRow() -> {
                    val columns = line.split(',').map(String::trim)
                    if (columns.size != header.size) {
                        malformedRows += 1
                    } else {
                        unknownColumns += requireNotNull(header).filterNot(KNOWN_COLUMNS::contains)
                        parseRow(
                            header = requireNotNull(header),
                            columns = columns,
                            sessionId = sessionId,
                            packageName = packageName,
                            windowId = windowId,
                        )?.copy(frameId = parsed.size.toLong())?.let(parsed::add) ?: run { malformedRows += 1 }
                    }
                }
            }
        }

        if (!foundHeader) warnings += "No gfxinfo framestats header was found."
        if (malformedRows > 0) warnings += "$malformedRows malformed frame row(s) were skipped."
        if (parsed.isEmpty() && foundHeader) warnings += "The framestats section did not contain usable frames."
        if (unknownColumns.isNotEmpty()) {
            warnings += "Preserved unsupported framestats column(s): ${unknownColumns.joinToString()}."
        }

        val inferred = fillInferredExpectedDurations(parsed)
        if (inferred.mapNotNull(FrameSample::expectedDurationNs).distinctNear().size > 1) {
            warnings += "Multiple frame intervals were observed; legacy frame budgets were inferred per frame."
        }
        return FrameStatsParseResult(
            frames = inferred,
            warnings = warnings,
        )
    }

    private fun parseRow(
        header: List<String>,
        columns: List<String>,
        sessionId: String,
        packageName: String?,
        windowId: String?,
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
        val unknownStates =
            header
                .asSequence()
                .filterNot(KNOWN_COLUMNS::contains)
                .mapNotNull { name -> values[name]?.let { value -> name to value.toString() } }
                .associate { (name, value) -> "gfxinfo.column.$name" to value }

        return FrameSample(
            frameId = 0L,
            sessionId = sessionId,
            source = FrameSource.GFXINFO,
            packageName = packageName,
            windowId = windowId,
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
            states = unknownStates + if (flags == 0L) emptyMap() else mapOf("gfxinfo.flags" to flags.toString()),
        )
    }

    private fun fillInferredExpectedDurations(frames: List<FrameSample>): List<FrameSample> =
        frames.mapIndexed { index, frame ->
            if (frame.expectedDurationNs != null) {
                frame
            } else {
                val current = frame.intendedVsyncNs
                val next = frames.getOrNull(index + 1)?.takeIf { it.windowId == frame.windowId }?.intendedVsyncNs
                val previous = frames.getOrNull(index - 1)?.takeIf { it.windowId == frame.windowId }?.intendedVsyncNs
                val interval =
                    positiveDifference(next, current)
                        ?.takeIf(::validRefreshInterval)
                        ?: positiveDifference(current, previous)?.takeIf(::validRefreshInterval)
                if (interval == null) {
                    frame
                } else {
                    frame.copy(
                        expectedDurationNs = interval,
                        expectedDurationSource = ExpectedDurationSource.INFERRED_VSYNC,
                    )
                }
            }
        }

    private fun List<Long>.distinctNear(): List<Long> =
        sorted().fold(mutableListOf()) { groups, value ->
            if (groups.none { existing ->
                    kotlin.math.abs(existing - value) <= existing / REFRESH_INTERVAL_TOLERANCE_DIVISOR
                }
            ) {
                groups += value
            }
            groups
        }

    private fun validRefreshInterval(value: Long): Boolean = value in MIN_REFRESH_INTERVAL_NS..MAX_REFRESH_INTERVAL_NS

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
        const val REFRESH_INTERVAL_TOLERANCE_DIVISOR = 20
        val KNOWN_COLUMNS =
            setOf(
                "Flags",
                "IntendedVsync",
                "Vsync",
                "OldestInputEvent",
                "NewestInputEvent",
                "HandleInputStart",
                "AnimationStart",
                "PerformTraversalsStart",
                "DrawStart",
                "FrameDeadline",
                "FrameInterval",
                "SyncQueued",
                "SyncStart",
                "IssueDrawCommandsStart",
                "SwapBuffers",
                "FrameCompleted",
                "GpuCompleted",
                "SwapBuffersCompleted",
                "DisplayPresentTime",
                "DequeueBufferDuration",
                "QueueBufferDuration",
            )
    }
}
