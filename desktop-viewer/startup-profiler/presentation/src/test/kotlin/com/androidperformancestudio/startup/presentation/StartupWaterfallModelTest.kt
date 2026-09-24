package com.androidperformancestudio.startup.presentation

import com.androidperformancestudio.startup.analysis.StartupAnalyzer
import com.androidperformancestudio.startup.model.EvidenceConfidence
import com.androidperformancestudio.startup.model.PlatformLaunchMetrics
import com.androidperformancestudio.startup.model.StartupMilestone
import com.androidperformancestudio.startup.model.StartupMilestoneKind
import com.androidperformancestudio.startup.model.StartupPhase
import com.androidperformancestudio.startup.model.StartupRawEvidence
import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.model.StartupSource
import com.androidperformancestudio.startup.model.StartupType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StartupWaterfallModelTest {
    @Test
    fun `analyzer generated phases retain exact milestone offsets in the waterfall`() {
        val run =
            run(
                milestones =
                    listOf(
                        milestone(StartupMilestoneKind.PROCESS_START, 1_000_000, StartupSource.AGENT),
                        milestone(StartupMilestoneKind.INITIALIZER_ENTER, 3_000_000, StartupSource.AGENT),
                        milestone(StartupMilestoneKind.AGENT_READY, 6_000_000, StartupSource.AGENT),
                    ),
                phases = emptyList(),
            )

        val waterfall = StartupAnalyzer().addPhases(run).waterfallModel()

        assertEquals(
            2,
            waterfall.groups
                .single()
                .segments.size,
        )
        assertEquals(1_000_000, waterfall.groups.single().originNs)
        assertEquals(5_000_000, waterfall.groups.single().extentNs)
        assertEquals(
            listOf(2_000_000L, 3_000_000L),
            waterfall.groups
                .single()
                .segments
                .map { it.phase.durationNs },
        )
        assertEquals(0, waterfall.unplottedPhaseCount)
    }

    @Test
    fun `only matching same-source milestone intervals become waterfall segments`() {
        val run =
            run(
                milestones =
                    listOf(
                        milestone(StartupMilestoneKind.PROCESS_START, 1_000, StartupSource.AGENT),
                        milestone(StartupMilestoneKind.INITIALIZER_ENTER, 2_000, StartupSource.AGENT),
                        milestone(StartupMilestoneKind.AGENT_READY, 4_000, StartupSource.AGENT),
                        milestone(StartupMilestoneKind.ACTIVITY_CREATED, 10_000, StartupSource.EVENT_LOG),
                    ),
                phases =
                    listOf(
                        phase(StartupMilestoneKind.PROCESS_START, StartupMilestoneKind.INITIALIZER_ENTER, 1_000),
                        phase(StartupMilestoneKind.INITIALIZER_ENTER, StartupMilestoneKind.AGENT_READY, 2_000),
                        phase(StartupMilestoneKind.AGENT_READY, StartupMilestoneKind.ACTIVITY_CREATED, 6_000),
                    ),
            )

        val waterfall = run.waterfallModel()

        assertEquals(1, waterfall.groups.size)
        assertEquals(StartupSource.AGENT, waterfall.groups.single().source)
        assertEquals(1_000, waterfall.groups.single().originNs)
        assertEquals(3_000, waterfall.groups.single().extentNs)
        assertEquals(
            listOf(1_000L, 2_000L),
            waterfall.groups
                .single()
                .segments
                .map { it.phase.durationNs },
        )
        assertEquals(1, waterfall.unplottedPhaseCount)
    }

    @Test
    fun `imported phase with missing or inconsistent timestamps is not drawn`() {
        val start = milestone(StartupMilestoneKind.PROCESS_START, 1_000, StartupSource.AGENT)
        val end = milestone(StartupMilestoneKind.FIRST_FRAME, 2_000, StartupSource.AGENT)
        val phase = phase(StartupMilestoneKind.PROCESS_START, StartupMilestoneKind.FIRST_FRAME, 900)

        assertTrue(run(listOf(start, end), listOf(phase)).waterfallModel().groups.isEmpty())
        assertEquals(1, run(listOf(start), listOf(phase)).waterfallModel().unplottedPhaseCount)
        assertTrue(
            run(listOf(start, end.copy(elapsedRealtimeNs = Long.MAX_VALUE)), listOf(phase.copy(durationNs = Long.MAX_VALUE)))
                .waterfallModel()
                .groups
                .isEmpty(),
        )
    }

    @Test
    fun `independent sources retain separate origins instead of a fabricated shared timeline`() {
        val run =
            run(
                milestones =
                    listOf(
                        milestone(StartupMilestoneKind.PROCESS_START, 1_000, StartupSource.AGENT),
                        milestone(StartupMilestoneKind.INITIALIZER_ENTER, 2_000, StartupSource.AGENT),
                        milestone(StartupMilestoneKind.ACTIVITY_CREATED, 1_000_000, StartupSource.EVENT_LOG),
                        milestone(StartupMilestoneKind.ACTIVITY_RESUMED, 1_500_000, StartupSource.EVENT_LOG),
                    ),
                phases =
                    listOf(
                        phase(StartupMilestoneKind.PROCESS_START, StartupMilestoneKind.INITIALIZER_ENTER, 1_000),
                        phase(StartupMilestoneKind.ACTIVITY_CREATED, StartupMilestoneKind.ACTIVITY_RESUMED, 500_000),
                    ),
            )

        val waterfall = run.waterfallModel()

        assertEquals(0, waterfall.unplottedPhaseCount)
        assertEquals(
            mapOf(StartupSource.AGENT to 1_000L, StartupSource.EVENT_LOG to 1_000_000L),
            waterfall.groups.associate { it.source to it.originNs },
        )
    }

    @Test
    fun `zero duration remains a zero duration interval rather than receiving invented time`() {
        val waterfall =
            run(
                milestones =
                    listOf(
                        milestone(StartupMilestoneKind.PROCESS_START, 1_000, StartupSource.AGENT),
                        milestone(StartupMilestoneKind.INITIALIZER_ENTER, 1_000, StartupSource.AGENT),
                    ),
                phases = listOf(phase(StartupMilestoneKind.PROCESS_START, StartupMilestoneKind.INITIALIZER_ENTER, 0)),
            ).waterfallModel()

        assertEquals(0, waterfall.groups.single().extentNs)
        assertEquals(
            0,
            waterfall.groups
                .single()
                .segments
                .single()
                .phase.durationNs,
        )
    }

    private fun run(
        milestones: List<StartupMilestone>,
        phases: List<StartupPhase>,
    ): StartupRun =
        StartupRun(
            id = "run-1",
            sessionId = "session-1",
            iteration = 1,
            requestedType = StartupType.COLD,
            observedType = StartupType.COLD,
            platform = PlatformLaunchMetrics(totalTimeMs = 10),
            milestones = milestones,
            phases = phases,
            rawEvidence = StartupRawEvidence("Status: ok"),
        )

    private fun milestone(
        kind: StartupMilestoneKind,
        timestampNs: Long,
        source: StartupSource,
    ): StartupMilestone = StartupMilestone(kind, timestampNs, source = source, confidence = EvidenceConfidence.EXACT)

    private fun phase(
        start: StartupMilestoneKind,
        end: StartupMilestoneKind,
        durationNs: Long,
    ): StartupPhase = StartupPhase("${start.name}-${end.name}", start, end, durationNs, EvidenceConfidence.EXACT)
}
