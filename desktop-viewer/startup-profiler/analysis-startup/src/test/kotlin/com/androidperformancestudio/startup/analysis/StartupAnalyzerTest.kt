package com.androidperformancestudio.startup.analysis

import com.androidperformancestudio.startup.model.EvidenceConfidence
import com.androidperformancestudio.startup.model.PlatformLaunchMetrics
import com.androidperformancestudio.startup.model.StartupMilestone
import com.androidperformancestudio.startup.model.StartupMilestoneKind
import com.androidperformancestudio.startup.model.StartupRawEvidence
import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.model.StartupSource
import com.androidperformancestudio.startup.model.StartupType
import kotlin.test.Test
import kotlin.test.assertEquals

class StartupAnalyzerTest {
    @Test
    fun `calculates distribution without dropping missing samples`() {
        val statistics = StartupAnalyzer().statistics(listOf(100.0, null, 300.0, 200.0))

        assertEquals(3, statistics.count)
        assertEquals(1, statistics.missingCount)
        assertEquals(200.0, statistics.medianMs)
        assertEquals(300.0, statistics.p90Ms)
    }

    @Test
    fun `creates phases only from same-domain milestone timestamps`() {
        val run =
            StartupRun(
                id = "run",
                sessionId = "session",
                iteration = 1,
                requestedType = StartupType.COLD,
                observedType = StartupType.COLD,
                platform = PlatformLaunchMetrics(totalTimeMs = 100),
                milestones =
                    listOf(
                        milestone(StartupMilestoneKind.ACTIVITY_CREATED, 2_000_000),
                        milestone(StartupMilestoneKind.ACTIVITY_RESUMED, 5_000_000),
                    ),
                rawEvidence = StartupRawEvidence(""),
            )

        val analyzed = StartupAnalyzer().addPhases(run)

        assertEquals(1, analyzed.phases.size)
        assertEquals(3_000_000, analyzed.phases.single().durationNs)
    }

    private fun milestone(
        kind: StartupMilestoneKind,
        timestamp: Long,
    ) = StartupMilestone(kind, timestamp, source = StartupSource.AGENT, confidence = EvidenceConfidence.EXACT)
}
