package com.androidperformancestudio.benchmark.presentation

import com.androidperformancestudio.benchmark.model.BenchmarkReportEntry
import com.androidperformancestudio.benchmark.model.BenchmarkRun
import com.androidperformancestudio.benchmark.model.CompatibilityIssue
import com.androidperformancestudio.benchmark.model.EvidenceConfidence
import com.androidperformancestudio.benchmark.model.MetricComparison
import com.androidperformancestudio.benchmark.model.RegressionClassification
import com.androidperformancestudio.benchmark.model.RegressionPolicy
import com.androidperformancestudio.benchmark.model.RegressionReport
import com.androidperformancestudio.benchmark.model.ReleaseGatePolicy
import com.androidperformancestudio.benchmark.model.ReleaseGateResult

/** One stored run offered by the baseline/current selectors. */
public data class BenchmarkRunOption(
    val id: String,
    val label: String,
    val detail: String,
    val sourcePath: String,
    val available: Boolean,
)

/**
 * Row severity derived from the verdict plus its evidence confidence. The implementation plan
 * (§2.3, §15.4) forbids an invented performance score, so severity never comes from an unmeasured
 * weight: an exact-confidence regression is high, a weaker regression is low, and incompatible
 * evidence stays incompatible instead of being folded into a regression.
 */
public enum class RegressionSeverity { HIGH, LOW, NONE, UNKNOWN, INCOMPATIBLE }

public fun severityOf(comparison: MetricComparison): RegressionSeverity =
    when (comparison.classification) {
        RegressionClassification.REGRESSED ->
            if (comparison.confidence == EvidenceConfidence.EXACT) {
                RegressionSeverity.HIGH
            } else {
                RegressionSeverity.LOW
            }
        RegressionClassification.IMPROVED,
        RegressionClassification.STABLE,
        -> RegressionSeverity.NONE
        RegressionClassification.INCONCLUSIVE -> RegressionSeverity.UNKNOWN
        RegressionClassification.INCOMPATIBLE -> RegressionSeverity.INCOMPATIBLE
    }

/** One point of the trend chart; the label is the build label or the import date. */
public data class BenchmarkTrendPoint(
    val label: String,
    val value: Double,
    val sampleCount: Int,
    val comparable: Boolean,
)

public data class BenchmarkTrend(
    val metricKey: String,
    val metricLabel: String,
    val unit: String,
    val points: List<BenchmarkTrendPoint>,
    val environmentVaries: Boolean,
    val minimumSampleCount: Int,
    val hasLowSamplePoints: Boolean,
)

/** A case/metric pair that has stored history and can be plotted. */
public data class BenchmarkMetricOption(
    val key: String,
    val caseIdentity: String,
    val metricName: String,
    val unit: String,
)

public data class BenchmarkReportsState(
    val runOptions: List<BenchmarkRunOption> = emptyList(),
    val baselineId: String? = null,
    val currentId: String? = null,
    val baseline: BenchmarkRun? = null,
    val current: BenchmarkRun? = null,
    val report: RegressionReport? = null,
    val gate: ReleaseGateResult? = null,
    val regressionPolicy: RegressionPolicy = RegressionPolicy(),
    val gatePolicy: ReleaseGatePolicy = ReleaseGatePolicy(),
    val metrics: List<BenchmarkMetricOption> = emptyList(),
    val selectedMetricKey: String? = null,
    val trend: BenchmarkTrend? = null,
    val query: String = "",
    val severityFilter: RegressionSeverity? = null,
    val classificationFilter: RegressionClassification? = null,
    val selectedComparisonKey: String? = null,
    val recentReports: List<BenchmarkReportEntry> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val completedDurationSeconds: Double? = null,
    val error: String? = null,
) {
    public val comparisons: List<MetricComparison> get() = report?.comparisons.orEmpty()

    public val comparedCount: Int get() = comparisons.size

    public val regressionCount: Int
        get() = comparisons.count { it.classification == RegressionClassification.REGRESSED }

    public val improvementCount: Int
        get() = comparisons.count { it.classification == RegressionClassification.IMPROVED }

    public val stableCount: Int
        get() = comparisons.count { it.classification == RegressionClassification.STABLE }

    public val selectedComparison: MetricComparison?
        get() = comparisons.firstOrNull { comparisonKey(it) == selectedComparisonKey }

    /** Table rows after the free-text query and the severity/classification filters. */
    public fun visibleComparisons(): List<MetricComparison> =
        comparisons.filter { comparison ->
            val matchesQuery =
                query.isBlank() ||
                    comparison.caseIdentity.contains(query, ignoreCase = true) ||
                    comparison.metricName.contains(query, ignoreCase = true)
            val matchesSeverity = severityFilter == null || severityOf(comparison) == severityFilter
            val matchesClassification =
                classificationFilter == null || comparison.classification == classificationFilter
            matchesQuery && matchesSeverity && matchesClassification
        }
}

/** Sample count of the current run for [comparison]; this is the evidence the analyzer gated on. */
public fun BenchmarkReportsState.sampleCount(comparison: MetricComparison): Int? =
    current?.cases
        ?.firstOrNull { it.identity == comparison.caseIdentity }
        ?.metrics
        ?.firstOrNull { it.name == comparison.metricName }
        ?.samples
        ?.size

public fun BenchmarkReportsState.traceArtifacts(comparison: MetricComparison): List<String> =
    current?.cases
        ?.firstOrNull { it.identity == comparison.caseIdentity }
        ?.traceArtifacts
        .orEmpty()
        .map { it.toString() }

/** Run-level issues plus the case-level issues that belong to the inspected comparison. */
public fun BenchmarkReportsState.compatibilityIssues(comparison: MetricComparison? = null): List<CompatibilityIssue> {
    val issues = report?.compatibilityIssues.orEmpty()
    val inspected = comparison ?: selectedComparison ?: return issues
    return issues.filter { !it.field.startsWith("case.") || it.field.contains(inspected.caseIdentity) }
}

public fun comparisonKey(comparison: MetricComparison): String = comparison.caseIdentity + "#" + comparison.metricName
