@file:Suppress("LongMethod", "MagicNumber")

package com.androidperformancestudio.startup.export

import com.androidperformancestudio.startup.analysis.StartupAnalyzer
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
import com.androidperformancestudio.startup.model.StartupTraceEvidence
import com.androidperformancestudio.startup.model.StartupType
import java.nio.file.Path
import java.time.Instant

/** Writes a Kotlin-exported Startup Profiler report for Electron compatibility tests. */
public object ElectronStartupJsonFixtureWriter {
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 1) { "Expected exactly one fixture output path" }

        val run = fixtureRun()

        StartupJsonExporter().export(
            analysis = StartupAnalyzer().analyze(listOf(run)),
            output = Path.of(args.single()).toAbsolutePath(),
        )
    }

    private fun fixtureRun(): StartupRun =
        StartupRun(
            id = "kotlin-startup-run-1",
            sessionId = "kotlin-startup-session",
            iteration = 1,
            requestedType = StartupType.COLD,
            observedType = StartupType.WARM,
            platform =
                PlatformLaunchMetrics(
                    status = "ok",
                    launchState = "WARM",
                    activity = "com.example.performance/.MainActivity",
                    thisTimeMs = 430,
                    totalTimeMs = 470,
                    waitTimeMs = 485,
                    displayedTimeMs = 420,
                    fullyDrawnTimeMs = 760,
                    complete = true,
                ),
            milestones =
                listOf(
                    StartupMilestone(
                        kind = StartupMilestoneKind.PROCESS_START,
                        elapsedRealtimeNs = 1_000_000_000,
                        source = StartupSource.EVENT_LOG,
                        confidence = EvidenceConfidence.EXACT,
                        processId = 4242,
                        processName = "com.example.performance",
                    ),
                    StartupMilestone(
                        kind = StartupMilestoneKind.ACTIVITY_PRE_CREATE,
                        elapsedRealtimeNs = 1_090_000_000,
                        source = StartupSource.AGENT,
                        confidence = EvidenceConfidence.EXACT,
                        activityName = "com.example.performance.MainActivity",
                        processId = 4242,
                    ),
                    StartupMilestone(
                        kind = StartupMilestoneKind.FIRST_FRAME,
                        elapsedRealtimeNs = 1_420_000_000,
                        durationMs = 420,
                        source = StartupSource.EVENT_LOG,
                        confidence = EvidenceConfidence.EXACT,
                        activityName = "com.example.performance.MainActivity",
                    ),
                    StartupMilestone(
                        kind = StartupMilestoneKind.FULLY_DRAWN,
                        elapsedRealtimeNs = 1_760_000_000,
                        durationMs = 760,
                        source = StartupSource.EVENT_LOG,
                        confidence = EvidenceConfidence.EXACT,
                        activityName = "com.example.performance.MainActivity",
                    ),
                ),
            warnings = listOf("Requested cold launch was observed as warm."),
            rawEvidence =
                StartupRawEvidence(
                    amStartOutput =
                        """
                        Status: ok
                        LaunchState: WARM
                        Activity: com.example.performance/.MainActivity
                        TotalTime: 470
                        """.trimIndent(),
                    eventLogOutput =
                        """
                        Displayed com.example.performance/.MainActivity: +420ms
                        Fully drawn com.example.performance/.MainActivity: +760ms
                        """.trimIndent(),
                    compilationOutput = "Package compiled with speed-profile before capture.",
                    agentAvailable = true,
                ),
            processIdBefore = 4001,
            processIdAfter = 4242,
            context =
                StartupRunContext(
                    deviceSerial = "kotlin-fixture-device",
                    packageName = "com.example.performance",
                    componentName = "com.example.performance/.MainActivity",
                ),
            ttidEvidence = StartupMetricEvidence(StartupSource.EVENT_LOG, EvidenceConfidence.EXACT),
            ttfdEvidence = StartupMetricEvidence(StartupSource.EVENT_LOG, EvidenceConfidence.EXACT),
            agentFirstFrameEvidence = StartupMetricEvidence(StartupSource.AGENT, EvidenceConfidence.EXACT),
            compilationEvidence =
                StartupCompilationEvidence(
                    requestedMode = CompilationMode.SPEED_PROFILE,
                    compilerFilterBefore = "verify",
                    compilerFilterAfter = "speed-profile",
                    profileStateBefore = "No profile",
                    profileStateAfter = "Profile-guided",
                    preparationOutput = "Success",
                    verified = true,
                    profileSource = StartupProfileSource.BASELINE_PROFILE_PLUGIN,
                    profileSourceDeclared = true,
                ),
            environmentEvidence =
                StartupEnvironmentEvidence(
                    deviceModel = "Kotlin Fixture Device",
                    apiLevel = 36,
                    emulator = true,
                    batteryPercent = 87,
                    charging = false,
                    thermalStatus = 0,
                    capturedAt = Instant.parse("2026-09-14T00:00:00Z"),
                ),
            traceEvidence =
                StartupTraceEvidence(
                    file = "kotlin-startup-run-1.perfetto-trace",
                    captured = true,
                    truncated = false,
                ),
            diagnostics = listOf("Startup Agent and platform timing sources agreed within the fixture."),
        )
}
