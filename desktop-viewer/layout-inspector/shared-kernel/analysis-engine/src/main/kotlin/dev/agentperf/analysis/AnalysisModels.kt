package dev.agentperf.analysis

data class AnalysisConfig(
    val maxDepth: Int = 10,
    val maxChildrenPerNode: Int = 10,
    val minOverlappingSiblings: Int = 3,
    val minSiblingOverlapRatio: Float = 0.8f,
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
    val arguments: Map<String, String> = emptyMap(),
)

data class AnalysisReport(
    val metrics: LayoutMetrics,
    val findings: List<Finding>,
)

data class AiFinding(
    val ruleId: String,
    val severity: Severity,
    val nodeId: String,
    val title: String,
    val message: String,
    val recommendation: String,
    val confidence: Float,
)

data class AiAnalysisReport(
    val model: String,
    val summary: String,
    val findings: List<AiFinding> = emptyList(),
)
