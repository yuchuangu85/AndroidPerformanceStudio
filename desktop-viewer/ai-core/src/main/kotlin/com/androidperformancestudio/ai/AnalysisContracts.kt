package com.androidperformancestudio.ai

import java.time.Instant
import java.util.UUID

@JvmInline
public value class AnalysisSessionId(public val value: String) {
    public companion object {
        public fun create(): AnalysisSessionId = AnalysisSessionId(UUID.randomUUID().toString())
    }
}

@JvmInline
public value class AnalysisFindingId(public val value: String)

public enum class ProfilerKind {
    LAYOUT_INSPECTOR,
    SIMPLEPERF,
}

public enum class AnalysisScopeKind {
    CURRENT_SELECTION,
    REPORT_SUMMARY,
}

public data class AnalysisScope(
    val kind: AnalysisScopeKind,
    val description: String,
)

public data class PerformanceEvidence(
    val id: String,
    val kind: String,
    val summary: String,
    val structuredPayload: String,
)

public data class AiSourceCandidate(
    val id: String,
    val relativePath: String,
    val symbol: String?,
    val resolutionConfidence: String,
    val reasons: List<String>,
    val sourceSnippet: String?,
)

public data class AnalysisRequest(
    val sessionId: AnalysisSessionId,
    val originProfiler: ProfilerKind,
    val scope: AnalysisScope,
    val evidence: List<PerformanceEvidence>,
    val sourceCandidates: List<AiSourceCandidate>,
    val promptVersion: String,
    val payloadPolicyVersion: String,
)

public enum class AnalysisSeverity {
    INFO,
    WARNING,
    ERROR,
}

public data class AnalysisFinding(
    val id: AnalysisFindingId,
    val severity: AnalysisSeverity,
    val title: String,
    val explanation: String,
    val recommendation: String,
    val analysisConfidence: Float,
    val performanceEvidenceIds: List<String>,
    val sourceCandidateIds: List<String>,
)

public data class AnalysisResult(
    val sessionId: AnalysisSessionId,
    val model: String,
    val summary: String,
    val findings: List<AnalysisFinding>,
)

public enum class AnalysisSessionStatus {
    RUNNING,
    SUCCEEDED,
    CANCELLED,
    FAILED,
}

public data class AnalysisSession(
    val id: AnalysisSessionId,
    val originProfiler: ProfilerKind,
    val scope: AnalysisScope,
    val model: String?,
    val promptVersion: String,
    val payloadPolicyVersion: String,
    val sourceSnapshotIds: List<String>,
    val buildEvidenceBundleIds: List<String>,
    val status: AnalysisSessionStatus,
    val createdAt: Instant,
    val parentSessionId: AnalysisSessionId? = null,
    val summary: String? = null,
    val errorMessage: String? = null,
)

public fun interface AiAnalysisGateway {
    public suspend fun analyze(request: AnalysisRequest): AnalysisResult
}
