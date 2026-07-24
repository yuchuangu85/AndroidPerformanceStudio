@file:Suppress("MagicNumber", "MaxLineLength")

package com.androidperformancestudio.startup.analysis

import com.androidperformancestudio.startup.model.EvidenceConfidence
import com.androidperformancestudio.startup.model.StartupMilestone
import com.androidperformancestudio.startup.model.StartupMilestoneKind
import com.androidperformancestudio.startup.model.StartupPhase
import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.model.StartupStatistics
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

public data class StartupAnalysisResult(
    val runs: List<StartupRun>,
    val totalTime: StartupStatistics,
    val firstFrame: StartupStatistics,
    val fullyDrawn: StartupStatistics,
    val warnings: List<String>,
)

public class StartupAnalyzer {
    public fun addPhases(run: StartupRun): StartupRun = run.copy(phases = phases(run.milestones))

    public fun analyze(runs: List<StartupRun>): StartupAnalysisResult {
        require(runs.isNotEmpty()) { "At least one startup run is required" }
        val analyzed = runs.map(::addPhases)
        return StartupAnalysisResult(
            runs = analyzed,
            totalTime = statistics(analyzed.map { it.platform.totalTimeMs?.toDouble() }),
            firstFrame = statistics(analyzed.map { it.firstFrameDurationMs() }),
            fullyDrawn = statistics(analyzed.map { it.platform.fullyDrawnTimeMs?.toDouble() }),
            warnings = analyzed.flatMap(StartupRun::warnings).distinct(),
        )
    }

    public fun statistics(values: List<Double?>): StartupStatistics {
        val present = values.filterNotNull().sorted()
        if (present.isEmpty()) {
            return StartupStatistics(values.size - present.size, values.size, null, null, null, null, null, null, null, null)
        }
        val mean = present.average()
        val median = percentile(present, 0.5)
        val deviations = present.map { kotlin.math.abs(it - median) }.sorted()
        return StartupStatistics(
            count = present.size,
            missingCount = values.size - present.size,
            minimumMs = present.first(),
            maximumMs = present.last(),
            medianMs = median,
            meanMs = mean,
            p90Ms = percentile(present, 0.90),
            p95Ms = percentile(present, 0.95),
            standardDeviationMs = sqrt(present.sumOf { (it - mean).pow(2) } / present.size),
            medianAbsoluteDeviationMs = percentile(deviations, 0.5),
        )
    }

    private fun phases(milestones: List<StartupMilestone>): List<StartupPhase> =
        PHASE_DEFINITIONS.mapNotNull { definition ->
            val start = milestones.firstOrNull { it.kind == definition.start && it.elapsedRealtimeNs != null }
            val end = milestones.firstOrNull { it.kind == definition.end && it.elapsedRealtimeNs != null }
            val startNs = start?.elapsedRealtimeNs
            val endNs = end?.elapsedRealtimeNs
            if (startNs == null || endNs == null || endNs < startNs) {
                null
            } else {
                StartupPhase(
                    name = definition.name,
                    start = definition.start,
                    end = definition.end,
                    durationNs = endNs - startNs,
                    confidence = minConfidence(start.confidence, end.confidence),
                )
            }
        }

    private fun StartupRun.firstFrameDurationMs(): Double? {
        platform.displayedTimeMs?.let { return it.toDouble() }
        val process = milestones.firstOrNull { it.kind == StartupMilestoneKind.PROCESS_START }?.elapsedRealtimeNs
        val frame =
            milestones
                .firstOrNull {
                    it.kind == StartupMilestoneKind.FIRST_FRAME || it.kind == StartupMilestoneKind.FIRST_DRAW_CALLBACK
                }?.elapsedRealtimeNs
        return if (process != null && frame != null && frame >= process) (frame - process) / NANOS_PER_MILLISECOND else null
    }

    private fun percentile(
        sorted: List<Double>,
        percentile: Double,
    ): Double = sorted[(ceil(percentile * sorted.size).toInt() - 1).coerceIn(sorted.indices)]

    private fun minConfidence(
        left: EvidenceConfidence,
        right: EvidenceConfidence,
    ): EvidenceConfidence = if (left.ordinal >= right.ordinal) left else right

    private data class PhaseDefinition(
        val name: String,
        val start: StartupMilestoneKind,
        val end: StartupMilestoneKind,
    )

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000.0
        val PHASE_DEFINITIONS =
            listOf(
                PhaseDefinition("Process bootstrap", StartupMilestoneKind.PROCESS_START, StartupMilestoneKind.INITIALIZER_ENTER),
                PhaseDefinition("Agent initialization", StartupMilestoneKind.INITIALIZER_ENTER, StartupMilestoneKind.AGENT_READY),
                PhaseDefinition("Activity create", StartupMilestoneKind.ACTIVITY_PRE_CREATE, StartupMilestoneKind.ACTIVITY_CREATED),
                PhaseDefinition("Activity to resumed", StartupMilestoneKind.ACTIVITY_CREATED, StartupMilestoneKind.ACTIVITY_RESUMED),
                PhaseDefinition("Resumed to first frame", StartupMilestoneKind.ACTIVITY_RESUMED, StartupMilestoneKind.FIRST_FRAME),
                PhaseDefinition("First frame to fully drawn", StartupMilestoneKind.FIRST_FRAME, StartupMilestoneKind.FULLY_DRAWN),
            )
    }
}
