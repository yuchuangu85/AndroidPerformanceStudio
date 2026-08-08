@file:Suppress("MaxLineLength")

package com.androidperformancestudio.startup.analysis

import com.androidperformancestudio.startup.model.CompilationMode
import com.androidperformancestudio.startup.model.EvidenceConfidence
import com.androidperformancestudio.startup.model.PlatformLaunchMetrics
import com.androidperformancestudio.startup.model.StartupCompilationEvidence
import com.androidperformancestudio.startup.model.StartupEnvironmentEvidence
import com.androidperformancestudio.startup.model.StartupMetricEvidence
import com.androidperformancestudio.startup.model.StartupMilestone
import com.androidperformancestudio.startup.model.StartupMilestoneKind
import com.androidperformancestudio.startup.model.StartupProfileSource
import com.androidperformancestudio.startup.model.StartupRawEvidence
import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.model.StartupRunContext
import com.androidperformancestudio.startup.model.StartupSource
import com.androidperformancestudio.startup.model.StartupType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun `does not combine phase boundaries from different clock domains`() {
        val run =
            comparableRun(
                "mixed-clock",
                100,
                milestones =
                    listOf(
                        milestone(StartupMilestoneKind.ACTIVITY_CREATED, 2_000_000),
                        StartupMilestone(
                            StartupMilestoneKind.ACTIVITY_RESUMED,
                            5_000_000,
                            source = StartupSource.EVENT_LOG,
                            confidence = EvidenceConfidence.EXACT,
                        ),
                    ),
            )

        assertEquals(emptyList(), StartupAnalyzer().addPhases(run).phases)
    }

    @Test
    fun `keeps TTID missing instead of falling back to agent first frame`() {
        val run =
            comparableRun(
                "run",
                null,
                milestones =
                    listOf(
                        milestone(StartupMilestoneKind.PROCESS_START, 1_000_000),
                        milestone(StartupMilestoneKind.FIRST_FRAME, 101_000_000),
                    ),
            )

        val analysis = StartupAnalyzer().analyze(listOf(run))

        assertEquals(1, analysis.firstFrame.missingCount)
        assertEquals(100.0, analysis.agentFirstFrame.medianMs)
    }

    @Test
    fun `marks MAD outliers without removing them and compares compatible cohorts with BCa`() {
        val analyzer = StartupAnalyzer()
        val baseline = analyzer.analyze(listOf(100, 101, 102, 103).mapIndexed { index, value -> comparableRun("b$index", value.toLong()) })
        val current = analyzer.analyze(listOf(130, 131, 132, 500).mapIndexed { index, value -> comparableRun("c$index", value.toLong()) })

        val comparison = analyzer.compare(current, baseline)

        assertEquals(StartupComparisonStatus.REGRESSION, comparison.status)
        assertEquals(4, current.firstFrame.count)
        assertTrue(
            current.runs
                .last()
                .diagnostics
                .contains("MAD_OUTLIER_TTID"),
        )
        assertTrue(current.firstFrame.p95LowResolution)
    }

    @Test
    fun `requires a declared speed profile artifact source for comparison`() {
        val analyzer = StartupAnalyzer()
        val runs =
            listOf(100L, 101L, 102L).mapIndexed { index, value ->
                comparableRun("profile-$index", value).copy(
                    compilationEvidence =
                        StartupCompilationEvidence(
                            CompilationMode.SPEED_PROFILE,
                            compilerFilterAfter = "speed-profile",
                            profileStateAfter = "bg-dexopt",
                            verified = true,
                        ),
                )
            }
        val analysis = analyzer.analyze(runs)
        val declared =
            analyzer.analyze(
                runs.map { run ->
                    run.copy(
                        compilationEvidence =
                            run.compilationEvidence?.copy(
                                profileSource = StartupProfileSource.BASELINE_PROFILE_PLUGIN,
                                profileSourceDeclared = true,
                            ),
                    )
                },
            )

        assertEquals(StartupComparisonStatus.INCOMPATIBLE, analyzer.compare(analysis, analysis).status)
        assertEquals(StartupComparisonStatus.NO_CHANGE, analyzer.compare(declared, declared).status)
    }

    private fun milestone(
        kind: StartupMilestoneKind,
        timestamp: Long,
    ) = StartupMilestone(kind, timestamp, source = StartupSource.AGENT, confidence = EvidenceConfidence.EXACT)

    private fun comparableRun(
        id: String,
        ttidMs: Long?,
        milestones: List<StartupMilestone> = emptyList(),
    ) = StartupRun(
        id = id,
        sessionId = "session",
        iteration = 1,
        requestedType = StartupType.COLD,
        observedType = StartupType.COLD,
        platform = PlatformLaunchMetrics(totalTimeMs = ttidMs, displayedTimeMs = ttidMs),
        milestones = milestones,
        rawEvidence = StartupRawEvidence(""),
        context = StartupRunContext("device", "dev.sample", "dev.sample/.MainActivity"),
        ttidEvidence = StartupMetricEvidence(StartupSource.EVENT_LOG, EvidenceConfidence.EXACT),
        compilationEvidence =
            StartupCompilationEvidence(
                CompilationMode.SPEED,
                compilerFilterAfter = "speed",
                profileStateAfter = "install",
                verified = true,
            ),
        environmentEvidence = StartupEnvironmentEvidence("Pixel", 35, false, 80, true, 0, Instant.EPOCH),
    )
}
