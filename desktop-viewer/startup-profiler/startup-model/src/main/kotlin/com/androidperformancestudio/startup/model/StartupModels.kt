package com.androidperformancestudio.startup.model

import java.time.Instant

public enum class StartupType {
    COLD,
    WARM,
    HOT,
    UNKNOWN,
}

public enum class StartupSource {
    AM_START,
    AGENT,
    EVENT_LOG,
}

public enum class EvidenceConfidence {
    EXACT,
    ESTIMATED,
    INFERRED,
    UNAVAILABLE,
}

public enum class CompilationMode(
    public val commandValue: String?,
) {
    CURRENT(null),
    RESET("reset"),
    VERIFY("verify"),
    SPEED_PROFILE("speed-profile"),
    SPEED("speed"),
}

public enum class StartupCapabilityLevel {
    FULL_AGENT,
    HOST_ENHANCED,
    PLATFORM_ONLY,
}

public enum class StartupMilestoneKind {
    PROCESS_START,
    INITIALIZER_ENTER,
    AGENT_READY,
    ACTIVITY_PRE_CREATE,
    ACTIVITY_CREATED,
    ACTIVITY_STARTED,
    ACTIVITY_RESUMED,
    FIRST_FRAME,
    FIRST_DRAW_CALLBACK,
    FULLY_DRAWN,
}

public data class StartupDevice(
    val serial: String,
    val name: String,
    val online: Boolean = true,
    val apiLevel: Int? = null,
)

public data class StartupTarget(
    val packageName: String,
    val componentName: String,
    val debuggable: Boolean,
)

public data class StartupExperimentConfig(
    val requestedType: StartupType = StartupType.COLD,
    val compilationMode: CompilationMode = CompilationMode.CURRENT,
    val warmupRuns: Int = 0,
    val measuredRuns: Int = 5,
    val timeoutSeconds: Int = 30,
) {
    init {
        require(warmupRuns in 0..MAX_RUNS) { "warmupRuns must be between 0 and $MAX_RUNS" }
        require(measuredRuns in 1..MAX_RUNS) { "measuredRuns must be between 1 and $MAX_RUNS" }
        require(timeoutSeconds in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS) {
            "timeoutSeconds must be between $MIN_TIMEOUT_SECONDS and $MAX_TIMEOUT_SECONDS"
        }
    }

    private companion object {
        const val MAX_RUNS = 100
        const val MIN_TIMEOUT_SECONDS = 5
        const val MAX_TIMEOUT_SECONDS = 300
    }
}

public data class StartupSession(
    val id: String,
    val deviceSerial: String,
    val packageName: String,
    val componentName: String,
    val requestedType: StartupType,
    val compilationMode: CompilationMode,
    val warmupRuns: Int,
    val measuredRuns: Int,
    val createdAt: Instant,
)

public data class PlatformLaunchMetrics(
    val status: String? = null,
    val launchState: String? = null,
    val activity: String? = null,
    val thisTimeMs: Long? = null,
    val totalTimeMs: Long? = null,
    val waitTimeMs: Long? = null,
    val displayedTimeMs: Long? = null,
    val fullyDrawnTimeMs: Long? = null,
    val complete: Boolean = false,
)

public data class StartupMilestone(
    val kind: StartupMilestoneKind,
    val elapsedRealtimeNs: Long? = null,
    val durationMs: Long? = null,
    val source: StartupSource,
    val confidence: EvidenceConfidence,
    val activityName: String? = null,
    val processId: Int? = null,
    val processName: String? = null,
)

public data class StartupPhase(
    val name: String,
    val start: StartupMilestoneKind,
    val end: StartupMilestoneKind,
    val durationNs: Long,
    val confidence: EvidenceConfidence,
)

public data class StartupRawEvidence(
    val amStartOutput: String,
    val eventLogOutput: String? = null,
    val compilationOutput: String? = null,
    val agentAvailable: Boolean = false,
)

public data class StartupRun(
    val id: String,
    val sessionId: String,
    val iteration: Int,
    val requestedType: StartupType,
    val observedType: StartupType,
    val platform: PlatformLaunchMetrics,
    val milestones: List<StartupMilestone> = emptyList(),
    val phases: List<StartupPhase> = emptyList(),
    val warnings: List<String> = emptyList(),
    val rawEvidence: StartupRawEvidence,
    val processIdBefore: Int? = null,
    val processIdAfter: Int? = null,
)

public data class StartupStatistics(
    val count: Int,
    val missingCount: Int,
    val minimumMs: Double?,
    val maximumMs: Double?,
    val medianMs: Double?,
    val meanMs: Double?,
    val p90Ms: Double?,
    val p95Ms: Double?,
    val standardDeviationMs: Double?,
    val medianAbsoluteDeviationMs: Double?,
)
