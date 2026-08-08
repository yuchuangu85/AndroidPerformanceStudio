@file:Suppress("MaxLineLength")

package com.androidperformancestudio.startup.export

import com.androidperformancestudio.startup.analysis.StartupAnalysisResult
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

public class StartupJsonImporter(
    private val analyzer: StartupAnalyzer = StartupAnalyzer(),
) {
    public fun import(input: Path): StartupAnalysisResult {
        val document = JSON.decodeFromString<StartupAnalysisDocument>(Files.readString(input))
        require(document.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported Startup Profiler schema version: ${document.schemaVersion}"
        }
        require(document.runs.isNotEmpty()) { "Startup analysis does not contain any runs." }
        return analyzer
            .analyze(document.runs.map(StartupRunDocument::toModel))
            .copy(warnings = document.warnings)
    }

    private companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class StartupAnalysisDocument(
    val schemaVersion: Int,
    val warnings: List<String> = emptyList(),
    val runs: List<StartupRunDocument>,
)

@Serializable
private data class StartupRunDocument(
    val iteration: Int,
    val runId: String,
    val requestedType: String,
    val observedType: String,
    val totalTimeMs: Long? = null,
    val thisTimeMs: Long? = null,
    val waitTimeMs: Long? = null,
    val displayedTimeMs: Long? = null,
    val fullyDrawnTimeMs: Long? = null,
    val agentAvailable: Boolean = false,
    val warnings: List<String> = emptyList(),
    val diagnostics: List<String> = emptyList(),
    val milestones: List<StartupMilestoneDocument> = emptyList(),
    val rawEvidence: StartupRawEvidenceDocument,
    val context: StartupRunContextDocument? = null,
    val metricEvidence: StartupMetricEvidenceContainerDocument? = null,
    val compilationEvidence: StartupCompilationEvidenceDocument? = null,
    val environmentEvidence: StartupEnvironmentEvidenceDocument? = null,
    val traceEvidence: StartupTraceEvidenceDocument? = null,
) {
    fun toModel(): StartupRun =
        StartupRun(
            id = runId,
            sessionId = IMPORTED_SESSION_ID,
            iteration = iteration,
            requestedType = StartupType.valueOf(requestedType),
            observedType = StartupType.valueOf(observedType),
            platform =
                PlatformLaunchMetrics(
                    thisTimeMs = thisTimeMs,
                    totalTimeMs = totalTimeMs,
                    waitTimeMs = waitTimeMs,
                    displayedTimeMs = displayedTimeMs,
                    fullyDrawnTimeMs = fullyDrawnTimeMs,
                ),
            milestones = milestones.map(StartupMilestoneDocument::toModel),
            warnings = warnings,
            rawEvidence = rawEvidence.toModel(agentAvailable),
            context = context?.toModel(),
            ttidEvidence =
                metricEvidence?.ttid?.toModel()
                    ?: legacyMetricEvidence(displayedTimeMs, StartupSource.EVENT_LOG, "No displayed event was observed."),
            ttfdEvidence =
                metricEvidence?.ttfd?.toModel()
                    ?: legacyMetricEvidence(
                        fullyDrawnTimeMs,
                        StartupSource.EVENT_LOG,
                        "The app did not call reportFullyDrawn() during this run.",
                    ),
            agentFirstFrameEvidence = metricEvidence?.agentFirstFrame?.toModel() ?: StartupMetricEvidence(),
            compilationEvidence = compilationEvidence?.toModel(),
            environmentEvidence = environmentEvidence?.toModel(),
            traceEvidence = traceEvidence?.toModel(),
            diagnostics = diagnostics,
        )

    private companion object {
        const val IMPORTED_SESSION_ID = "imported"
    }
}

private fun legacyMetricEvidence(
    value: Long?,
    source: StartupSource,
    reason: String,
): StartupMetricEvidence =
    if (value == null) {
        StartupMetricEvidence(unavailableReason = reason)
    } else {
        StartupMetricEvidence(source, EvidenceConfidence.EXACT)
    }

@Serializable
private data class StartupRunContextDocument(
    val deviceSerial: String,
    val packageName: String,
    val componentName: String,
) {
    fun toModel() = StartupRunContext(deviceSerial, packageName, componentName)
}

@Serializable
private data class StartupMetricEvidenceContainerDocument(
    val ttid: StartupSingleMetricEvidenceDocument? = null,
    val ttfd: StartupSingleMetricEvidenceDocument? = null,
    val agentFirstFrame: StartupSingleMetricEvidenceDocument? = null,
)

@Serializable
private data class StartupSingleMetricEvidenceDocument(
    val source: String? = null,
    val confidence: String = EvidenceConfidence.UNAVAILABLE.name,
    val unavailableReason: String? = null,
) {
    fun toModel() =
        StartupMetricEvidence(
            source = source?.let(StartupSource::valueOf),
            confidence = EvidenceConfidence.valueOf(confidence),
            unavailableReason = unavailableReason,
        )
}

@Serializable
private data class StartupCompilationEvidenceDocument(
    val requestedMode: String,
    val compilerFilterBefore: String? = null,
    val compilerFilterAfter: String? = null,
    val profileStateBefore: String? = null,
    val profileStateAfter: String? = null,
    val preparationOutput: String? = null,
    val verified: Boolean = false,
    val failureReason: String? = null,
    val profileSource: String = StartupProfileSource.UNVERIFIED.name,
    val profileSourceDeclared: Boolean = false,
) {
    fun toModel() =
        StartupCompilationEvidence(
            CompilationMode.valueOf(requestedMode),
            compilerFilterBefore,
            compilerFilterAfter,
            profileStateBefore,
            profileStateAfter,
            preparationOutput,
            verified,
            failureReason,
            StartupProfileSource.valueOf(profileSource),
            profileSourceDeclared,
        )
}

@Serializable
private data class StartupEnvironmentEvidenceDocument(
    val deviceModel: String? = null,
    val apiLevel: Int? = null,
    val emulator: Boolean? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val thermalStatus: Int? = null,
    val capturedAt: String? = null,
    val failures: List<String> = emptyList(),
) {
    fun toModel() =
        StartupEnvironmentEvidence(
            deviceModel,
            apiLevel,
            emulator,
            batteryPercent,
            charging,
            thermalStatus,
            capturedAt?.let(Instant::parse),
            failures,
        )
}

@Serializable
private data class StartupTraceEvidenceDocument(
    val file: String? = null,
    val captured: Boolean = false,
    val truncated: Boolean = false,
    val failureReason: String? = null,
) {
    fun toModel() = StartupTraceEvidence(file, captured, truncated, failureReason)
}

@Serializable
private data class StartupMilestoneDocument(
    val kind: String,
    val elapsedRealtimeNs: Long? = null,
    val durationMs: Long? = null,
    val source: String,
    val confidence: String,
    val activity: String? = null,
) {
    fun toModel(): StartupMilestone =
        StartupMilestone(
            kind = StartupMilestoneKind.valueOf(kind),
            elapsedRealtimeNs = elapsedRealtimeNs,
            durationMs = durationMs,
            source = StartupSource.valueOf(source),
            confidence = EvidenceConfidence.valueOf(confidence),
            activityName = activity,
        )
}

@Serializable
private data class StartupRawEvidenceDocument(
    val amStartOutput: String,
    val eventLogOutput: String? = null,
    val compilationOutput: String? = null,
) {
    fun toModel(agentAvailable: Boolean): StartupRawEvidence =
        StartupRawEvidence(
            amStartOutput = amStartOutput,
            eventLogOutput = eventLogOutput,
            compilationOutput = compilationOutput,
            agentAvailable = agentAvailable,
        )
}
