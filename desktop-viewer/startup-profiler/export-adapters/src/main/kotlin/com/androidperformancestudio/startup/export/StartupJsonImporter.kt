package com.androidperformancestudio.startup.export

import com.androidperformancestudio.startup.analysis.StartupAnalysisResult
import com.androidperformancestudio.startup.analysis.StartupAnalyzer
import com.androidperformancestudio.startup.model.EvidenceConfidence
import com.androidperformancestudio.startup.model.PlatformLaunchMetrics
import com.androidperformancestudio.startup.model.StartupMilestone
import com.androidperformancestudio.startup.model.StartupMilestoneKind
import com.androidperformancestudio.startup.model.StartupRawEvidence
import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.model.StartupSource
import com.androidperformancestudio.startup.model.StartupType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

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
    val milestones: List<StartupMilestoneDocument> = emptyList(),
    val rawEvidence: StartupRawEvidenceDocument,
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
        )

    private companion object {
        const val IMPORTED_SESSION_ID = "imported"
    }
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
