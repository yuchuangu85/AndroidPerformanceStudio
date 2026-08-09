package com.androidperformancestudio.analysis

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
    val performanceEvidenceIds: List<String> = emptyList(),
    val sourceCandidateIds: List<String> = emptyList(),
)

data class AiAnalysisReport(
    val model: String,
    val summary: String,
    val findings: List<AiFinding> = emptyList(),
    val provenance: AiAnalysisProvenance? = null,
)

data class AiAnalysisProvenance(
    val sessionId: String,
    val provider: String,
    val scope: String,
    val promptVersion: String,
    val payloadPolicyVersion: String,
    val sourceSnapshotIds: List<String>,
    val buildEvidenceBundleIds: List<String>,
    val evidence: List<AiEvidenceReference>,
    val sourceCandidates: List<AiSourceCandidateReference>,
)

data class AiEvidenceReference(
    val id: String,
    val kind: String,
    val summary: String,
    val payloadHash: String,
)

data class AiSourceCandidateReference(
    val id: String,
    val relativePath: String,
    val startLine: Int?,
    val endLine: Int?,
    val resolutionConfidence: String,
    val contentHash: String?,
    val workspaceId: String? = null,
    val snapshotId: String? = null,
    val providerKind: String? = null,
    val repositoryIdentity: String? = null,
    val revision: String? = null,
)
