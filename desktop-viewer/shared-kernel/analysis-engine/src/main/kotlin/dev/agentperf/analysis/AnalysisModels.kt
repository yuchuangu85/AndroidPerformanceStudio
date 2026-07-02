package dev.agentperf.analysis

data class AnalysisConfig(
    val maxDepth: Int = 12,
    val maxChildrenPerNode: Int = 20,
)

enum class Severity {
    INFO,
    WARNING,
    ERROR,
}

data class LayoutMetrics(
    val nodeCount: Int,
    val maxDepth: Int,
    val widestLevel: Int,
)

data class Finding(
    val ruleId: String,
    val severity: Severity,
    val nodeId: String,
    val message: String,
)

data class AnalysisReport(
    val metrics: LayoutMetrics,
    val findings: List<Finding>,
)
