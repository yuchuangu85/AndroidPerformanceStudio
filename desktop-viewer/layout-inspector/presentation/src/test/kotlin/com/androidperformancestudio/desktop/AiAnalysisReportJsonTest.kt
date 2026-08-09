package com.androidperformancestudio.desktop

import com.androidperformancestudio.analysis.AiAnalysisReport
import com.androidperformancestudio.analysis.AiAnalysisProvenance
import com.androidperformancestudio.analysis.AiEvidenceReference
import com.androidperformancestudio.analysis.AiFinding
import com.androidperformancestudio.analysis.AiSourceCandidateReference
import com.androidperformancestudio.analysis.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AiAnalysisReportJsonTest {
    @Test
    fun `round trips ai analysis report`() {
        val report = AiAnalysisReport(
            model = "gpt-test",
            summary = "Two risks found",
            findings = listOf(
                AiFinding(
                    ruleId = "ai.accessibility.touch-target",
                    severity = Severity.WARNING,
                    nodeId = "save",
                    title = "Small touch target",
                    message = "Button is likely too small",
                    recommendation = "Increase min height to 48dp",
                    confidence = 0.82f,
                    performanceEvidenceIds = listOf("evidence"),
                    sourceCandidateIds = listOf("candidate"),
                ),
            ),
            provenance = AiAnalysisProvenance(
                sessionId = "session",
                provider = "OpenAI Responses",
                scope = "selected node",
                promptVersion = "v1",
                payloadPolicyVersion = "minimal-v1",
                sourceSnapshotIds = listOf("snapshot"),
                buildEvidenceBundleIds = emptyList(),
                evidence = listOf(AiEvidenceReference("evidence", "layout", "summary", "a".repeat(64))),
                sourceCandidates = listOf(
                    AiSourceCandidateReference(
                        id = "candidate",
                        relativePath = "src/A.kt",
                        startLine = 2,
                        endLine = 4,
                        resolutionConfidence = "PROBABLE",
                        contentHash = "b".repeat(64),
                        workspaceId = "old-workspace",
                        snapshotId = "old-snapshot",
                        providerKind = "LOCAL",
                        repositoryIdentity = "sample",
                        revision = "abc123",
                    ),
                ),
            ),
        )

        val restored = AiAnalysisReportJson().decode(AiAnalysisReportJson().encode(report))

        assertEquals(report, restored)
    }
}
