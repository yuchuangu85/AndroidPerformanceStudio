package com.androidperformancestudio.benchmark.model

import java.nio.file.Path
import java.time.Instant
import java.util.UUID

public enum class EvidenceConfidence { EXACT, DERIVED, INFERRED, PARTIAL, UNKNOWN }

public enum class MetricDirection { LOWER_IS_BETTER, HIGHER_IS_BETTER, UNKNOWN }

public enum class RegressionClassification { REGRESSED, IMPROVED, STABLE, INCONCLUSIVE, INCOMPATIBLE }

public enum class BaselinePolicy { PINNED, BRANCH_HEAD, ROLLING_MEDIAN, RELEASE_TAG }

public data class BenchmarkDevice(
    val model: String?,
    val brand: String?,
    val apiLevel: Int?,
    val osVersion: String?,
    val abi: String?,
    val fingerprint: String?,
    val cpuCoreCount: Int?,
    val physicalDevice: Boolean?,
)

public data class BenchmarkBuild(
    val targetPackage: String?,
    val versionName: String?,
    val versionCode: Long?,
    val variant: String?,
    val gitCommit: String?,
    val gitBranch: String?,
)

public data class BenchmarkMetric(
    val name: String,
    val unit: String,
    val direction: MetricDirection,
    val samples: List<Double>,
    val minimum: Double?,
    val median: Double?,
    val maximum: Double?,
    val confidence: EvidenceConfidence,
    val sourceFields: Map<String, String> = emptyMap(),
) {
    public fun representativeValue(): Double? = median ?: samples.sorted().medianOrNull()
}

public data class BenchmarkCase(
    val className: String,
    val testName: String,
    val packageName: String?,
    val compilationMode: String?,
    val startupMode: String?,
    val iterationCount: Int?,
    val metrics: List<BenchmarkMetric>,
    val traceArtifacts: List<Path>,
) {
    public val identity: String get() = "$className#$testName"
}

public data class BenchmarkRun(
    val id: String = UUID.randomUUID().toString(),
    val sourceFile: Path,
    val benchmarkDataVersion: Int?,
    val benchmarkLibraryVersion: String?,
    val device: BenchmarkDevice,
    val build: BenchmarkBuild,
    val importedAt: Instant = Instant.now(),
    val cases: List<BenchmarkCase>,
    val warnings: List<String> = emptyList(),
)

public data class CompatibilityIssue(
    val field: String,
    val baseline: String?,
    val current: String?,
    val hard: Boolean,
)

public data class MetricComparison(
    val caseIdentity: String,
    val metricName: String,
    val unit: String,
    val baselineValue: Double?,
    val currentValue: Double?,
    val absoluteDelta: Double?,
    val relativeDeltaPercent: Double?,
    val classification: RegressionClassification,
    val confidence: EvidenceConfidence,
    val reasons: List<String>,
)

public data class RegressionReport(
    val baselineRunId: String,
    val currentRunId: String,
    val createdAt: Instant,
    val comparisons: List<MetricComparison>,
    val compatibilityIssues: List<CompatibilityIssue>,
) {
    public val regressionCount: Int get() = comparisons.count { it.classification == RegressionClassification.REGRESSED }
}

public data class RegressionPolicy(
    val relativeThresholdPercent: Double? = null,
    val absoluteThreshold: Double? = null,
    val minimumSampleCount: Int = 3,
    val noiseBandMadMultiplier: Double = 3.0,
    val requireSameDevice: Boolean = true,
)

public fun List<Double>.medianOrNull(): Double? {
    if (isEmpty()) return null
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
}
