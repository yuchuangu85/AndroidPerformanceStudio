@file:Suppress(
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "NestedBlockDepth",
    "TooManyFunctions",
)

package com.androidperformancestudio.frame.analysis

import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.JankType
import kotlin.math.ceil

public enum class FrameDeadlineVerdict {
    MET,
    MISSED,
    UNKNOWN,
}

public enum class JankSeverity {
    SMOOTH,
    MINOR,
    MAJOR,
    SEVERE,
    FROZEN,
    UNKNOWN,
}

public data class AnalyzedFrame(
    val sample: FrameSample,
    val deadlineVerdict: FrameDeadlineVerdict,
    val severity: JankSeverity,
    val missedVsyncCount: Int?,
    val platformJankTypes: Set<JankType>,
    val largestReportedStage: String?,
)

public data class FrameSummary(
    val totalFrames: Int = 0,
    val deadlineClassifiedFrames: Int = 0,
    val deadlineMissFrames: Int = 0,
    val deadlineUnknownFrames: Int = 0,
    val deadlineMissRate: Double? = null,
    val platformClassifiedFrames: Int = 0,
    val platformJankFrames: Int = 0,
    val platformUnknownFrames: Int = 0,
    val platformJankRate: Double? = null,
    val p50DurationNs: Long? = null,
    val p95DurationNs: Long? = null,
    val p99DurationNs: Long? = null,
    val worstDurationNs: Long? = null,
)

public data class DeadlineMissCluster(
    val id: Int,
    val firstFrameId: Long,
    val lastFrameId: Long,
    val deadlineMissFrameIds: List<Long>,
    val durationNs: Long,
    val worstSeverity: JankSeverity,
    val windowId: String?,
    val activityName: String?,
    val dominantReportedStage: String?,
)

public data class FrameAnalysisResult(
    val frames: List<AnalyzedFrame>,
    val summary: FrameSummary,
    val clusters: List<DeadlineMissCluster>,
)

public class FrameJankAnalyzer(
    private val maxSmoothGap: Int = DEFAULT_MAX_SMOOTH_GAP,
) {
    init {
        require(maxSmoothGap >= 0) { "maxSmoothGap must not be negative" }
    }

    public fun analyze(samples: List<FrameSample>): FrameAnalysisResult {
        val analyzed = samples.sortedBy { it.intendedVsyncNs ?: it.frameId }.map(::analyzeFrame)
        return FrameAnalysisResult(
            frames = analyzed,
            summary = summarize(analyzed),
            clusters = cluster(analyzed),
        )
    }

    public fun analyzeFrame(sample: FrameSample): AnalyzedFrame {
        if (!sample.eligibleForJank) return unknown(sample)

        val duration = sample.resolvedDurationNs()
        val expected = sample.expectedDurationNs?.takeIf { it > 0L }
        val verdict =
            if (duration != null && expected != null) {
                if (duration > expected) FrameDeadlineVerdict.MISSED else FrameDeadlineVerdict.MET
            } else {
                FrameDeadlineVerdict.UNKNOWN
            }
        val missedVsyncCount =
            if (duration != null && expected != null) {
                (ceil(duration.toDouble() / expected).toInt() - 1).coerceAtLeast(0)
            } else {
                null
            }
        val largestReportedStage =
            sample.stages
                .values()
                .maxByOrNull { it.second }
                ?.first
        return AnalyzedFrame(
            sample = sample,
            deadlineVerdict = verdict,
            severity = severity(verdict, duration, expected, missedVsyncCount),
            missedVsyncCount = missedVsyncCount,
            platformJankTypes =
                sample.platformJankTypes +
                    if (sample.platformJank == true) setOf(JankType.PLATFORM_REPORTED) else emptySet(),
            largestReportedStage = largestReportedStage,
        )
    }

    private fun summarize(frames: List<AnalyzedFrame>): FrameSummary {
        val durations = frames.mapNotNull { it.sample.resolvedDurationNs() }.sorted()
        val deadlineClassified = frames.count { it.deadlineVerdict != FrameDeadlineVerdict.UNKNOWN }
        val deadlineMisses = frames.count { it.deadlineVerdict == FrameDeadlineVerdict.MISSED }
        val platformClassified = frames.count { it.sample.platformJank != null }
        val platformJank = frames.count { it.sample.platformJank == true }
        return FrameSummary(
            totalFrames = frames.size,
            deadlineClassifiedFrames = deadlineClassified,
            deadlineMissFrames = deadlineMisses,
            deadlineUnknownFrames = frames.size - deadlineClassified,
            deadlineMissRate = deadlineClassified.takeIf { it > 0 }?.let { deadlineMisses.toDouble() / it },
            platformClassifiedFrames = platformClassified,
            platformJankFrames = platformJank,
            platformUnknownFrames = frames.size - platformClassified,
            platformJankRate = platformClassified.takeIf { it > 0 }?.let { platformJank.toDouble() / it },
            p50DurationNs = durations.percentile(0.50),
            p95DurationNs = durations.percentile(0.95),
            p99DurationNs = durations.percentile(0.99),
            worstDurationNs = durations.lastOrNull(),
        )
    }

    private fun cluster(frames: List<AnalyzedFrame>): List<DeadlineMissCluster> {
        val result = mutableListOf<DeadlineMissCluster>()
        var cursor = 0
        while (cursor < frames.size) {
            val firstIndex = frames.indexOfFirstFrom(cursor) { it.deadlineVerdict == FrameDeadlineVerdict.MISSED }
            if (firstIndex < 0) break
            val first = frames[firstIndex]
            val jankFrames = mutableListOf(first)
            var scan = firstIndex + 1
            var smoothGap = 0
            while (scan < frames.size) {
                val candidate = frames[scan]
                if (!candidate.sameContext(first) || candidate.deadlineVerdict == FrameDeadlineVerdict.UNKNOWN) break
                if (candidate.deadlineVerdict == FrameDeadlineVerdict.MISSED) {
                    jankFrames += candidate
                    smoothGap = 0
                } else {
                    smoothGap += 1
                    if (smoothGap > maxSmoothGap) break
                }
                scan += 1
            }
            val last = jankFrames.last()
            result +=
                DeadlineMissCluster(
                    id = result.size,
                    firstFrameId = first.sample.frameId,
                    lastFrameId = last.sample.frameId,
                    deadlineMissFrameIds = jankFrames.map { it.sample.frameId },
                    durationNs = clusterDuration(first.sample, last.sample),
                    worstSeverity = jankFrames.maxBy { it.severity.ordinal }.severity,
                    windowId = first.sample.windowId,
                    activityName = first.sample.activityName,
                    dominantReportedStage =
                        jankFrames
                            .mapNotNull(AnalyzedFrame::largestReportedStage)
                            .groupingBy { it }
                            .eachCount()
                            .maxByOrNull { it.value }
                            ?.key,
                )
            cursor = lastIndexOf(frames, last) + 1
        }
        return result
    }

    private fun severity(
        verdict: FrameDeadlineVerdict,
        durationNs: Long?,
        expectedNs: Long?,
        missedVsyncCount: Int?,
    ): JankSeverity =
        when {
            verdict == FrameDeadlineVerdict.UNKNOWN -> JankSeverity.UNKNOWN
            verdict == FrameDeadlineVerdict.MET -> JankSeverity.SMOOTH
            durationNs != null && durationNs >= FROZEN_FRAME_NS -> JankSeverity.FROZEN
            expectedNs == null || missedVsyncCount == null || missedVsyncCount <= 1 -> JankSeverity.MINOR
            missedVsyncCount <= 3 -> JankSeverity.MAJOR
            else -> JankSeverity.SEVERE
        }

    private fun unknown(sample: FrameSample): AnalyzedFrame =
        AnalyzedFrame(
            sample = sample,
            deadlineVerdict = FrameDeadlineVerdict.UNKNOWN,
            severity = JankSeverity.UNKNOWN,
            missedVsyncCount = null,
            platformJankTypes = sample.platformJankTypes,
            largestReportedStage = null,
        )

    private fun List<Long>.percentile(fraction: Double): Long? {
        if (isEmpty()) return null
        val index = (ceil(size * fraction).toInt() - 1).coerceIn(indices)
        return this[index]
    }

    private fun AnalyzedFrame.sameContext(other: AnalyzedFrame): Boolean =
        sample.windowId == other.sample.windowId && sample.activityName == other.sample.activityName

    private fun clusterDuration(
        first: FrameSample,
        last: FrameSample,
    ): Long {
        val start = first.intendedVsyncNs
        val end = last.presentNs ?: last.frameCompletedNs
        return if (start != null && end != null && end >= start) {
            end - start
        } else {
            last.resolvedDurationNs() ?: 0L
        }
    }

    private fun <T> List<T>.indexOfFirstFrom(
        start: Int,
        predicate: (T) -> Boolean,
    ): Int {
        for (index in start..lastIndex) if (predicate(this[index])) return index
        return -1
    }

    private fun <T> lastIndexOf(
        items: List<T>,
        item: T,
    ): Int = items.lastIndexOf(item)

    private companion object {
        const val DEFAULT_MAX_SMOOTH_GAP = 2
        const val FROZEN_FRAME_NS = 700_000_000L
    }
}
