@file:Suppress("LongMethod", "MagicNumber", "ReturnCount")

package com.androidperformancestudio.benchmark.analysis

import com.androidperformancestudio.benchmark.model.BenchmarkMetric
import com.androidperformancestudio.benchmark.model.BenchmarkRun
import com.androidperformancestudio.benchmark.model.CompatibilityIssue
import com.androidperformancestudio.benchmark.model.EvidenceConfidence
import com.androidperformancestudio.benchmark.model.MetricComparison
import com.androidperformancestudio.benchmark.model.MetricDirection
import com.androidperformancestudio.benchmark.model.RegressionClassification
import com.androidperformancestudio.benchmark.model.RegressionPolicy
import com.androidperformancestudio.benchmark.model.RegressionReport
import com.androidperformancestudio.benchmark.model.medianOrNull
import java.time.Instant
import kotlin.math.abs

public class RegressionAnalyzer {
    public fun compare(
        baseline: BenchmarkRun,
        current: BenchmarkRun,
        policy: RegressionPolicy,
    ): RegressionReport {
        val compatibility = compatibilityIssues(baseline, current, policy)
        val hardIssues = compatibility.filter { it.hard }
        val baselineMetrics = baseline.cases.flatMap { case -> case.metrics.map { (case.identity to it.name) to it } }.toMap()
        val comparisons =
            current.cases.flatMap { case ->
                case.metrics.map { metric ->
                    val baselineMetric = baselineMetrics[case.identity to metric.name]
                    compareMetric(case.identity, baselineMetric, metric, policy, hardIssues)
                }
            }
        return RegressionReport(baseline.id, current.id, Instant.now(), comparisons, compatibility)
    }

    public fun compatibilityIssues(
        baseline: BenchmarkRun,
        current: BenchmarkRun,
        policy: RegressionPolicy,
    ): List<CompatibilityIssue> =
        buildList {
            if (policy.requireSameDevice && baseline.device.model != current.device.model) {
                add(CompatibilityIssue("device.model", baseline.device.model, current.device.model, true))
            }
            if (baseline.device.apiLevel != current.device.apiLevel) {
                add(CompatibilityIssue("device.apiLevel", baseline.device.apiLevel?.toString(), current.device.apiLevel?.toString(), true))
            }
            if (baseline.device.abi != current.device.abi) {
                add(CompatibilityIssue("device.abi", baseline.device.abi, current.device.abi, true))
            }
            if (baseline.device.fingerprint != null &&
                current.device.fingerprint != null &&
                baseline.device.fingerprint != current.device.fingerprint
            ) {
                add(CompatibilityIssue("device.fingerprint", baseline.device.fingerprint, current.device.fingerprint, false))
            }
            if (baseline.build.variant != current.build.variant) {
                add(CompatibilityIssue("build.variant", baseline.build.variant, current.build.variant, true))
            }
        }

    private fun compareMetric(
        caseIdentity: String,
        baseline: BenchmarkMetric?,
        current: BenchmarkMetric,
        policy: RegressionPolicy,
        hardIssues: List<CompatibilityIssue>,
    ): MetricComparison {
        val reasons = mutableListOf<String>()
        if (hardIssues.isNotEmpty()) reasons += "Run environments are incompatible: ${hardIssues.joinToString { it.field }}"
        if (baseline == null) reasons += "No baseline metric with the same case and metric identity."
        if (baseline != null && baseline.unit != current.unit) reasons += "Metric units differ (${baseline.unit} vs ${current.unit})."
        if (current.direction == MetricDirection.UNKNOWN) reasons += "Metric direction is unknown and requires project configuration."
        val baselineValue = baseline?.representativeValue()
        val currentValue = current.representativeValue()
        val absoluteDelta = if (baselineValue != null && currentValue != null) currentValue - baselineValue else null
        val relativeDelta =
            if (absoluteDelta != null &&
                baselineValue != null &&
                baselineValue != 0.0
            ) {
                absoluteDelta / abs(baselineValue) * 100.0
            } else {
                null
            }
        val sampleConfidence =
            if (baseline != null &&
                baseline.samples.size >= policy.minimumSampleCount &&
                current.samples.size >= policy.minimumSampleCount
            ) {
                EvidenceConfidence.EXACT
            } else {
                EvidenceConfidence.PARTIAL
            }
        val noiseBand = baseline?.samples?.let(::mad)?.times(policy.noiseBandMadMultiplier) ?: 0.0
        val classification =
            when {
                hardIssues.isNotEmpty() || (baseline != null && baseline.unit != current.unit) -> RegressionClassification.INCOMPATIBLE
                baseline == null || baselineValue == null || currentValue == null -> RegressionClassification.INCONCLUSIVE
                current.direction == MetricDirection.UNKNOWN -> RegressionClassification.INCONCLUSIVE
                policy.relativeThresholdPercent == null && policy.absoluteThreshold == null -> RegressionClassification.INCONCLUSIVE
                sampleConfidence == EvidenceConfidence.PARTIAL -> {
                    reasons += "Insufficient raw samples for a high-confidence gate."
                    classifyDelta(absoluteDelta ?: 0.0, relativeDelta, current.direction, policy, noiseBand, allowGate = false)
                }
                else -> classifyDelta(absoluteDelta ?: 0.0, relativeDelta, current.direction, policy, noiseBand, allowGate = true)
            }
        return MetricComparison(
            caseIdentity,
            current.name,
            current.unit,
            baselineValue,
            currentValue,
            absoluteDelta,
            relativeDelta,
            classification,
            sampleConfidence,
            reasons,
        )
    }

    private fun classifyDelta(
        absoluteDelta: Double,
        relativeDelta: Double?,
        direction: MetricDirection,
        policy: RegressionPolicy,
        noiseBand: Double,
        allowGate: Boolean,
    ): RegressionClassification {
        val exceedsRelative = policy.relativeThresholdPercent?.let { relativeDelta != null && abs(relativeDelta) >= it } ?: true
        val exceedsAbsolute = policy.absoluteThreshold?.let { abs(absoluteDelta) >= it } ?: true
        if (!exceedsRelative || !exceedsAbsolute || abs(absoluteDelta) <= noiseBand) return RegressionClassification.STABLE
        if (!allowGate) return RegressionClassification.INCONCLUSIVE
        val worse =
            when (direction) {
                MetricDirection.LOWER_IS_BETTER -> absoluteDelta > 0
                MetricDirection.HIGHER_IS_BETTER -> absoluteDelta < 0
                MetricDirection.UNKNOWN -> return RegressionClassification.INCONCLUSIVE
            }
        return if (worse) RegressionClassification.REGRESSED else RegressionClassification.IMPROVED
    }

    private fun mad(values: List<Double>): Double {
        val median = values.medianOrNull() ?: return 0.0
        return values.map { abs(it - median) }.medianOrNull() ?: 0.0
    }
}
