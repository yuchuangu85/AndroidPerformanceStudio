package com.androidperformancestudio.perfetto.analysis

/** Domain-neutral interval extracted from a Trace Processor result. */
public enum class TraceEvidenceDomain {
    CPU_SCHEDULING,
    CPU_WAKEUP,
    RUN_QUEUE,
    BINDER_CLIENT,
    BINDER_SERVER,
    MAIN_THREAD_BLOCKED,
    FRAME_TIMELINE,
    SURFACE_FLINGER,
}

public data class TraceInterval(
    val id: String,
    val startNs: Long,
    val durationNs: Long,
    val domain: TraceEvidenceDomain,
    val processName: String? = null,
    val threadName: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(startNs >= 0) { "trace interval start must not be negative" }
        require(durationNs >= 0) { "trace interval duration must not be negative" }
        require(durationNs <= Long.MAX_VALUE - startNs) { "trace interval end must fit in a signed 64-bit timestamp" }
    }

    public val endNs: Long get() = startNs + durationNs
}

public enum class AttributionConfidence { EXACT, BOUNDED_OVERLAP, INFERRED, UNAVAILABLE }

public data class IntervalAttribution(
    val primaryId: String,
    val supportingId: String,
    val primaryDomain: TraceEvidenceDomain,
    val supportingDomain: TraceEvidenceDomain,
    val overlapNs: Long,
    val confidence: AttributionConfidence,
) {
    init {
        require(overlapNs >= 0) { "interval overlap must not be negative" }
    }
}

public data class FrameRootCauseAttribution(
    val frameId: String,
    val frame: TraceInterval,
    val contributors: List<IntervalAttribution>,
    val dominantDomain: TraceEvidenceDomain?,
    val evidenceLimitations: List<String> = emptyList(),
)

/**
 * Correlates independently collected CPU, Binder and FrameTimeline evidence by interval overlap.
 * It deliberately reports attribution as bounded evidence, not causality: a Binder call that
 * overlaps a jank frame is a candidate contributor, while scheduling slices describe why a
 * thread did not run rather than proving which code caused the frame miss.
 */
public class CrossDomainAttributionAnalyzer(
    private val minimumOverlapNs: Long = 1L,
) {
    init {
        require(minimumOverlapNs > 0) { "minimumOverlapNs must be positive" }
    }

    public fun correlate(
        primary: List<TraceInterval>,
        supporting: List<TraceInterval>,
    ): List<IntervalAttribution> =
        primary.flatMap { left ->
            supporting.mapNotNull { right ->
                val overlap = overlapNs(left, right)
                if (overlap < minimumOverlapNs) return@mapNotNull null
                IntervalAttribution(
                    primaryId = left.id,
                    supportingId = right.id,
                    primaryDomain = left.domain,
                    supportingDomain = right.domain,
                    overlapNs = overlap,
                    confidence = confidence(left, right),
                )
            }
        }

    public fun attributeFrames(
        frames: List<TraceInterval>,
        scheduling: List<TraceInterval> = emptyList(),
        binder: List<TraceInterval> = emptyList(),
        blocked: List<TraceInterval> = emptyList(),
        surfaceFlinger: List<TraceInterval> = emptyList(),
    ): List<FrameRootCauseAttribution> {
        // Prefer explicit IPC/blocking evidence over generic CPU slices when overlap ties.
        val all = binder + blocked + scheduling + surfaceFlinger
        return frames.map { frame ->
            val contributors = correlate(listOf(frame), all).sortedByDescending { it.overlapNs }
            val domain = contributors.firstOrNull()?.supportingDomain
            FrameRootCauseAttribution(
                frameId = frame.id,
                frame = frame,
                contributors = contributors,
                dominantDomain = domain,
                evidenceLimitations = if (contributors.isEmpty()) listOf("No supporting interval overlapped this frame.") else emptyList(),
            )
        }
    }

    private fun overlapNs(
        left: TraceInterval,
        right: TraceInterval,
    ): Long = (minOf(left.endNs, right.endNs) - maxOf(left.startNs, right.startNs)).coerceAtLeast(0L)

    private fun confidence(
        left: TraceInterval,
        right: TraceInterval,
    ): AttributionConfidence =
        when {
            left.attributes["frameTimelineVsyncId"] != null &&
                left.attributes["frameTimelineVsyncId"] == right.attributes["frameTimelineVsyncId"] -> AttributionConfidence.EXACT
            left.processName != null &&
                right.processName != null &&
                left.processName == right.processName -> AttributionConfidence.BOUNDED_OVERLAP
            else -> AttributionConfidence.INFERRED
        }
}
