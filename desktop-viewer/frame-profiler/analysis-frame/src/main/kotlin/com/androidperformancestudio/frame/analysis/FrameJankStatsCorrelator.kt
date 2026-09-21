package com.androidperformancestudio.frame.analysis

import com.androidperformancestudio.frame.model.FrameSample
import kotlin.math.abs

/** A JankStats observation captured by the optional AndroidX agent. */
public data class JankStatsObservation(
    val frameId: Long? = null,
    val frameTimelineVsyncId: Long? = null,
    val timestampNs: Long? = null,
    val isJank: Boolean,
    val reasons: Set<String> = emptySet(),
    val ruleId: String? = null,
    val ruleVersion: String? = null,
    val activityName: String? = null,
    val fragmentName: String? = null,
    val pageName: String? = null,
    val interactionState: String? = null,
    val renderThreadName: String? = null,
)

public data class FrameJankCorrelation(
    val frame: FrameSample,
    val jankStats: JankStatsObservation?,
    val matchedBy: JankStatsMatch,
    val timeDeltaNs: Long? = null,
)

public enum class JankStatsMatch { FRAME_ID, VSYNC_ID, TIMESTAMP, UNMATCHED }

/**
 * Joins JankStats (app-side) and FrameTimeline (platform-side) without treating a timestamp
 * coincidence as an exact identity. IDs win; timestamp matching is bounded and reported as such.
 */
public class FrameJankStatsCorrelator(
    private val timestampToleranceNs: Long = 2_000_000L,
) {
    init {
        require(timestampToleranceNs >= 0) { "timestampToleranceNs must not be negative" }
    }

    public fun correlate(
        timeline: List<FrameSample>,
        observations: List<JankStatsObservation>,
    ): List<FrameJankCorrelation> {
        val unused = observations.indices.toMutableSet()
        return timeline.map { frame ->
            val exactIndex =
                unused.firstOrNull { index ->
                    observations[index].frameId?.let { it == frame.frameId } == true
                }
            val vsyncIndex =
                exactIndex ?: frame.frameTimelineVsyncId?.let { id ->
                    unused.firstOrNull { index -> observations[index].frameTimelineVsyncId == id }
                }
            val timestampIndex =
                vsyncIndex ?: frameTimestamp(frame)?.let { timestamp ->
                    unused
                        .asSequence()
                        .filter { index -> observations[index].timestampNs != null }
                        .minByOrNull { index -> abs(observations[index].timestampNs!! - timestamp) }
                        ?.takeIf { index -> abs(observations[index].timestampNs!! - timestamp) <= timestampToleranceNs }
                }
            val observationIndex = timestampIndex
            val observation = observationIndex?.let(observations::get)
            if (observationIndex != null) unused.remove(observationIndex)
            val match =
                when {
                    exactIndex != null -> JankStatsMatch.FRAME_ID
                    vsyncIndex != null -> JankStatsMatch.VSYNC_ID
                    timestampIndex != null -> JankStatsMatch.TIMESTAMP
                    else -> JankStatsMatch.UNMATCHED
                }
            val enriched = observation?.let { merge(frame, it) } ?: frame
            FrameJankCorrelation(
                frame = enriched,
                jankStats = observation,
                matchedBy = match,
                timeDeltaNs = observation?.timestampNs?.let { frameTimestamp(frame)?.minus(it) },
            )
        }
    }

    public fun mergeIntoSamples(
        timeline: List<FrameSample>,
        observations: List<JankStatsObservation>,
    ): List<FrameSample> = correlate(timeline, observations).map(FrameJankCorrelation::frame)

    private fun merge(
        frame: FrameSample,
        observation: JankStatsObservation,
    ): FrameSample =
        frame.copy(
            activityName = frame.activityName ?: observation.activityName,
            fragmentName = frame.fragmentName ?: observation.fragmentName,
            pageName = frame.pageName ?: observation.pageName,
            interactionState = frame.interactionState ?: observation.interactionState,
            renderThreadName = frame.renderThreadName ?: observation.renderThreadName,
            jankStatsJank = observation.isJank,
            jankStatsReasons = observation.reasons,
            jankStatsRuleId = observation.ruleId,
            jankStatsRuleVersion = observation.ruleVersion,
            states =
                buildMap {
                    putAll(frame.states)
                    observation.activityName?.let { put("activity", it) }
                    observation.fragmentName?.let { put("fragment", it) }
                    observation.pageName?.let { put("page", it) }
                    observation.interactionState?.let { put("interaction", it) }
                },
        )

    private fun frameTimestamp(frame: FrameSample): Long? = frame.actualVsyncNs ?: frame.intendedVsyncNs
}
