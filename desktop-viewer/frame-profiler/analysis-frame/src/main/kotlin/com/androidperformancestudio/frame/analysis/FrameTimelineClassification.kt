package com.androidperformancestudio.frame.analysis

import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource

public const val FRAME_TIMELINE_JANK_TAG: String = "frameTimelineJankTag"
public const val FRAME_TIMELINE_PRESENT_TYPE: String = "frameTimelinePresentType"

public enum class FrameTimelineStatus {
    GOOD,
    HIGH_LATENCY,
    SELF_JANK,
    OTHER_JANK,
    DROPPED,
    UNKNOWN,
}

public fun FrameSample.frameTimelineStatus(): FrameTimelineStatus {
    val tag = states[FRAME_TIMELINE_JANK_TAG]
    return when {
        tag != null -> frameTimelineTagStatus(tag)
        source == FrameSource.PERFETTO ->
            when (platformJankRuleId) {
                "None", "On time" -> FrameTimelineStatus.GOOD
                "Buffer Stuffing", "SurfaceFlinger Stuffing" -> FrameTimelineStatus.HIGH_LATENCY
                else -> FrameTimelineStatus.UNKNOWN
            }
        else -> nonPerfettoFrameTimelineStatus()
    }
}

private fun frameTimelineTagStatus(tag: String): FrameTimelineStatus =
    when (tag) {
        "No Jank" -> FrameTimelineStatus.GOOD
        "Buffer Stuffing", "SurfaceFlinger Stuffing" -> FrameTimelineStatus.HIGH_LATENCY
        "Self Jank" -> FrameTimelineStatus.SELF_JANK
        "Other Jank" -> FrameTimelineStatus.OTHER_JANK
        "Dropped Frame" -> FrameTimelineStatus.DROPPED
        else -> FrameTimelineStatus.UNKNOWN
    }

private fun FrameSample.nonPerfettoFrameTimelineStatus(): FrameTimelineStatus {
    val duration = resolvedDurationNs()
    val budget = knownExpectedDurationNs()
    return when {
        platformJank == true || jankStatsJank == true -> FrameTimelineStatus.UNKNOWN
        platformJank == false || jankStatsJank == false -> FrameTimelineStatus.GOOD
        !eligibleForJank || platformJankTypes.isNotEmpty() || jankStatsReasons.isNotEmpty() ->
            FrameTimelineStatus.UNKNOWN
        duration != null && budget != null && duration <= budget -> FrameTimelineStatus.GOOD
        else -> FrameTimelineStatus.UNKNOWN
    }
}
