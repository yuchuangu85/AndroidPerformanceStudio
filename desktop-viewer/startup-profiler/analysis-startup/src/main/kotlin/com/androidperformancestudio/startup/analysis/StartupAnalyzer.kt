@file:Suppress(
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "LongMethod",
    "MagicNumber",
    "MaxLineLength",
    "ReturnCount",
    "TooManyFunctions",
)

package com.androidperformancestudio.startup.analysis

import com.androidperformancestudio.startup.model.EvidenceConfidence
import com.androidperformancestudio.startup.model.StartupMilestone
import com.androidperformancestudio.startup.model.StartupMilestoneKind
import com.androidperformancestudio.startup.model.StartupPhase
import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.model.StartupStatistics
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

public data class StartupAnalysisResult(
    val runs: List<StartupRun>,
    val totalTime: StartupStatistics,
    val firstFrame: StartupStatistics,
    val fullyDrawn: StartupStatistics,
    val warnings: List<String>,
    val agentFirstFrame: StartupStatistics = StartupStatistics(0, 0, null, null, null, null, null, null, null, null),
)

public enum class StartupComparisonStatus {
    INCOMPATIBLE,
    INSUFFICIENT,
    REGRESSION,
    IMPROVEMENT,
    NO_CHANGE,
    INCONCLUSIVE,
}

public data class StartupComparison(
    val status: StartupComparisonStatus,
    val metric: String = "TTID",
    val medianDifferenceMs: Double? = null,
    val percentDifference: Double? = null,
    val confidenceIntervalLowMs: Double? = null,
    val confidenceIntervalHighMs: Double? = null,
    val practicalThresholdMs: Double? = null,
    val reasons: List<String> = emptyList(),
    val phaseDifferences: List<StartupPhaseDifference> = emptyList(),
)

public data class StartupPhaseDifference(
    val name: String,
    val medianDifferenceMs: Double,
    val confidence: EvidenceConfidence,
)

public class StartupAnalyzer {
    public fun addPhases(run: StartupRun): StartupRun = run.copy(phases = phases(run.milestones))

    public fun analyze(runs: List<StartupRun>): StartupAnalysisResult {
        require(runs.isNotEmpty()) { "At least one startup run is required" }
        val phased = runs.map(::addPhases)
        val analyzed = annotateOutliers(phased)
        return StartupAnalysisResult(
            runs = analyzed,
            totalTime = statistics(analyzed.map { it.platform.totalTimeMs?.toDouble() }),
            firstFrame = statistics(analyzed.map { it.platform.displayedTimeMs?.toDouble() }),
            fullyDrawn = statistics(analyzed.map { it.platform.fullyDrawnTimeMs?.toDouble() }),
            warnings = analyzed.flatMap(StartupRun::warnings).distinct(),
            agentFirstFrame = statistics(analyzed.map { it.agentFirstFrameDurationMs() }),
        )
    }

    public fun compare(
        current: StartupAnalysisResult,
        baseline: StartupAnalysisResult,
        practicalThresholdPercent: Double = DEFAULT_PRACTICAL_THRESHOLD_PERCENT,
    ): StartupComparison {
        val reasons = comparabilityReasons(current.runs, baseline.runs)
        if (reasons.isNotEmpty()) return StartupComparison(StartupComparisonStatus.INCOMPATIBLE, reasons = reasons)
        val currentValues = current.runs.mapNotNull { it.platform.displayedTimeMs?.toDouble() }
        val baselineValues = baseline.runs.mapNotNull { it.platform.displayedTimeMs?.toDouble() }
        if (currentValues.size < MIN_COMPARISON_SAMPLES || baselineValues.size < MIN_COMPARISON_SAMPLES) {
            return StartupComparison(
                StartupComparisonStatus.INSUFFICIENT,
                reasons = listOf("At least $MIN_COMPARISON_SAMPLES TTID samples are required in each cohort."),
            )
        }
        val baselineMedian = median(baselineValues)
        if (baselineMedian <= 0.0) {
            return StartupComparison(
                StartupComparisonStatus.INSUFFICIENT,
                reasons = listOf("Baseline TTID median must be positive."),
            )
        }
        val difference = median(currentValues) - baselineMedian
        val threshold = baselineMedian * practicalThresholdPercent / 100.0
        val interval = bcaMedianDifference(currentValues, baselineValues)
        val status =
            when {
                interval.first > threshold -> StartupComparisonStatus.REGRESSION
                interval.second < -threshold -> StartupComparisonStatus.IMPROVEMENT
                interval.first >= -threshold && interval.second <= threshold -> StartupComparisonStatus.NO_CHANGE
                else -> StartupComparisonStatus.INCONCLUSIVE
            }
        return StartupComparison(
            status = status,
            medianDifferenceMs = difference,
            percentDifference = difference / baselineMedian * 100.0,
            confidenceIntervalLowMs = interval.first,
            confidenceIntervalHighMs = interval.second,
            practicalThresholdMs = threshold,
            phaseDifferences = comparablePhaseDifferences(current.runs, baseline.runs),
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
            p90LowResolution = present.size < MIN_P90_SAMPLES,
            p95LowResolution = present.size < MIN_P95_SAMPLES,
        )
    }

    private fun annotateOutliers(runs: List<StartupRun>): List<StartupRun> {
        val metrics =
            listOf(
                "TOTAL_TIME" to runs.map { it.platform.totalTimeMs?.toDouble() },
                "TTID" to runs.map { it.platform.displayedTimeMs?.toDouble() },
                "TTFD" to runs.map { it.platform.fullyDrawnTimeMs?.toDouble() },
            )
        val flags = Array(runs.size) { mutableListOf<String>() }
        metrics.forEach { (name, values) ->
            val present = values.filterNotNull()
            if (present.size < MIN_COMPARISON_SAMPLES) return@forEach
            val center = median(present)
            val mad = median(present.map { kotlin.math.abs(it - center) })
            values.forEachIndexed { index, value ->
                if (value != null &&
                    ((mad == 0.0 && value != center) || (mad > 0.0 && kotlin.math.abs(value - center) > MAD_OUTLIER_MULTIPLIER * mad))
                ) {
                    flags[index] += "MAD_OUTLIER_$name"
                }
            }
        }
        return runs.mapIndexed { index, run -> run.copy(diagnostics = (run.diagnostics + flags[index]).distinct()) }
    }

    private fun comparabilityReasons(
        current: List<StartupRun>,
        baseline: List<StartupRun>,
    ): List<String> =
        buildList {
            val all = current + baseline
            if (all.any { it.context == null } ||
                all.map { it.context }.distinct().size != 1
            ) {
                add("Device or target identity differs or is missing.")
            }
            if (all.map { it.requestedType }.distinct().size != 1 ||
                all.map { it.observedType }.distinct().size != 1 ||
                all.any { it.observedType == com.androidperformancestudio.startup.model.StartupType.UNKNOWN } ||
                all.any { it.observedType != it.requestedType }
            ) {
                add("Requested or observed startup mode differs.")
            }
            val compilation = all.map { it.compilationEvidence }
            if (compilation.any { it?.verified != true } ||
                compilation
                    .map {
                        listOf(
                            it?.requestedMode,
                            it?.compilerFilterAfter,
                            it?.profileStateAfter,
                            it?.profileSource,
                        )
                    }.distinct()
                    .size !=
                1
            ) {
                add("Verified startup compilation state differs or is missing.")
            }
            if (compilation.any {
                    it?.requestedMode == com.androidperformancestudio.startup.model.CompilationMode.SPEED_PROFILE &&
                        it.profileSourceDeclared != true
                }
            ) {
                add("speed-profile samples do not have a declared Profile artifact source.")
            }
            val environment = all.map { it.environmentEvidence }
            if (environment.any {
                    it == null ||
                        it.deviceModel == null ||
                        it.apiLevel == null ||
                        it.emulator == null ||
                        it.batteryPercent == null ||
                        it.charging == null ||
                        it.thermalStatus == null ||
                        it.capturedAt == null ||
                        it.failures.isNotEmpty()
                } ||
                environment.map { Triple(it?.deviceModel, it?.apiLevel, it?.emulator) }.distinct().size != 1
            ) {
                add("Startup environment identity differs or is missing.")
            }
            if (environment.any { it?.emulator == true }) {
                add(
                    "Emulator runs are diagnostic only and are excluded from regression decisions.",
                )
            }
            if (environment.any { evidence ->
                    val battery = evidence?.batteryPercent
                    battery != null && battery < LOW_BATTERY_PERCENT && evidence.charging != true
                }
            ) {
                add("At least one run was captured on low battery while not charging.")
            }
            if (environment.any { (it?.thermalStatus ?: 0) >= THERMAL_STATUS_SEVERE }) add("At least one run was thermally constrained.")
            if (all.any { it.platform.displayedTimeMs == null }) add("TTID availability differs or is incomplete.")
            if (all.any { it.ttidEvidence.source == null || it.ttidEvidence.confidence == EvidenceConfidence.UNAVAILABLE } ||
                all.map { it.ttidEvidence.source to it.ttidEvidence.confidence }.distinct().size != 1
            ) {
                add("TTID evidence source or confidence differs or is unavailable.")
            }
        }

    private fun comparablePhaseDifferences(
        current: List<StartupRun>,
        baseline: List<StartupRun>,
    ): List<StartupPhaseDifference> {
        val names =
            current
                .flatMap { it.phases }
                .map { it.name }
                .toSet()
                .intersect(baseline.flatMap { it.phases }.map { it.name }.toSet())
        return names.mapNotNull { name ->
            val currentPhases = current.mapNotNull { run -> run.phases.singleOrNull { it.name == name } }
            val baselinePhases = baseline.mapNotNull { run -> run.phases.singleOrNull { it.name == name } }
            if (currentPhases.size != current.size || baselinePhases.size != baseline.size) return@mapNotNull null
            val confidence = (currentPhases + baselinePhases).maxBy { it.confidence.ordinal }.confidence
            if (confidence == EvidenceConfidence.UNAVAILABLE) return@mapNotNull null
            StartupPhaseDifference(
                name,
                median(currentPhases.map { it.durationNs / NANOS_PER_MILLISECOND }) -
                    median(baselinePhases.map { it.durationNs / NANOS_PER_MILLISECOND }),
                confidence,
            )
        }
    }

    private fun bcaMedianDifference(
        current: List<Double>,
        baseline: List<Double>,
    ): Pair<Double, Double> {
        val observed = median(current) - median(baseline)
        val random = Random(0)
        val bootstrap =
            List(BOOTSTRAP_SAMPLES) {
                median(List(current.size) { current[random.nextInt(current.size)] }) -
                    median(List(baseline.size) { baseline[random.nextInt(baseline.size)] })
            }.sorted()
        val less = bootstrap.count { it < observed }.toDouble().coerceIn(0.5, BOOTSTRAP_SAMPLES - 0.5) / BOOTSTRAP_SAMPLES
        val z0 = inverseNormal(less)
        val jackknife =
            current.indices.map { index -> median(current.filterIndexed { i, _ -> i != index }) - median(baseline) } +
                baseline.indices.map { index -> median(current) - median(baseline.filterIndexed { i, _ -> i != index }) }
        val jackknifeMean = jackknife.average()
        val numerator = jackknife.sumOf { (jackknifeMean - it).pow(3) }
        val denominator = 6.0 * jackknife.sumOf { (jackknifeMean - it).pow(2) }.pow(1.5)
        val acceleration = if (denominator == 0.0) 0.0 else numerator / denominator

        fun adjusted(alpha: Double): Double {
            val z = inverseNormal(alpha)
            return normalCdf(z0 + (z0 + z) / (1.0 - acceleration * (z0 + z)))
        }
        return interpolatedPercentile(bootstrap, adjusted(0.025)) to interpolatedPercentile(bootstrap, adjusted(0.975))
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private fun interpolatedPercentile(
        sorted: List<Double>,
        quantile: Double,
    ): Double {
        val position = quantile.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lower = position.toInt()
        val fraction = position - lower
        return sorted[lower] + (sorted[(lower + 1).coerceAtMost(sorted.lastIndex)] - sorted[lower]) * fraction
    }

    // Acklam's inverse-normal approximation; sufficient for deterministic confidence limits.
    private fun inverseNormal(probability: Double): Double {
        val p = probability.coerceIn(1e-12, 1.0 - 1e-12)
        val a =
            doubleArrayOf(
                -39.69683028665376,
                220.9460984245205,
                -275.9285104469687,
                138.357751867269,
                -30.66479806614716,
                2.506628277459239,
            )
        val b = doubleArrayOf(-54.47609879822406, 161.5858368580409, -155.6989798598866, 66.80131188771972, -13.28068155288572)
        val c =
            doubleArrayOf(
                -0.007784894002430293,
                -0.3223964580411365,
                -2.400758277161838,
                -2.549732539343734,
                4.374664141464968,
                2.938163982698783,
            )
        val d = doubleArrayOf(0.007784695709041462, 0.3224671290700398, 2.445134137142996, 3.754408661907416)
        if (p < 0.02425) {
            val q = sqrt(-2.0 * ln(p))
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0)
        }
        if (p > 0.97575) return -inverseNormal(1.0 - p)
        val q = p - 0.5
        val r = q * q
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
            (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0)
    }

    private fun normalCdf(value: Double): Double {
        val x = kotlin.math.abs(value)
        val t = 1.0 / (1.0 + 0.2316419 * x)
        val density = exp(-x * x / 2.0) / sqrt(2.0 * Math.PI)
        val tail = density * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
        return if (value >= 0) 1.0 - tail else tail
    }

    private fun phases(milestones: List<StartupMilestone>): List<StartupPhase> =
        PHASE_DEFINITIONS.mapNotNull { definition ->
            val start = milestones.firstOrNull { it.kind == definition.start && it.elapsedRealtimeNs != null }
            val end = milestones.firstOrNull { it.kind == definition.end && it.elapsedRealtimeNs != null }
            val startNs = start?.elapsedRealtimeNs
            val endNs = end?.elapsedRealtimeNs
            if (startNs == null || endNs == null || endNs < startNs || start.source != end.source) {
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

    private fun StartupRun.agentFirstFrameDurationMs(): Double? {
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
        const val MIN_P90_SAMPLES = 10
        const val MIN_P95_SAMPLES = 20
        const val MIN_COMPARISON_SAMPLES = 3
        const val BOOTSTRAP_SAMPLES = 2_000
        const val MAD_OUTLIER_MULTIPLIER = 3.0
        const val DEFAULT_PRACTICAL_THRESHOLD_PERCENT = 5.0
        const val THERMAL_STATUS_SEVERE = 3
        const val LOW_BATTERY_PERCENT = 15
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
