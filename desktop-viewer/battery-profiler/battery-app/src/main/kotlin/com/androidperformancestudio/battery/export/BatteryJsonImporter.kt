@file:Suppress("MaxLineLength", "ktlint:standard:max-line-length")

package com.androidperformancestudio.battery.export

import com.androidperformancestudio.battery.analysis.BatteryAnalysisResult
import com.androidperformancestudio.battery.analysis.BatteryAnalyzer
import com.androidperformancestudio.battery.model.AttributionScope
import com.androidperformancestudio.battery.model.BatteryRunDelta
import com.androidperformancestudio.battery.model.EnergyEstimate
import com.androidperformancestudio.battery.model.EnergyEvidenceKind
import com.androidperformancestudio.battery.model.EvidenceConfidence
import com.androidperformancestudio.battery.model.NetworkUsage
import com.androidperformancestudio.battery.model.ResourceTimer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

public class BatteryJsonImporter(
    private val analyzer: BatteryAnalyzer = BatteryAnalyzer(),
) {
    public fun import(input: Path): BatteryAnalysisResult {
        val document = JSON.decodeFromString<BatteryAnalysisDocument>(Files.readString(input))
        require(document.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported battery analysis schema version: ${document.schemaVersion}"
        }
        require(document.runs.isNotEmpty()) { "Battery analysis does not contain any runs." }
        val runs = document.runs.map { it.toModel(document.sessionId) }
        return BatteryAnalysisResult(
            runs = runs,
            wakelockDurationMs = analyzer.statistics(runs.map { run -> run.wakelocks.sumOf(ResourceTimer::durationMs).toDouble() }),
            wakeupAlarmCount = analyzer.statistics(runs.map { run -> run.alarms.sumOf(ResourceTimer::count).toDouble() }),
            jobDurationMs = analyzer.statistics(runs.map { run -> run.jobs.sumOf(ResourceTimer::durationMs).toDouble() }),
            sensorDurationMs = analyzer.statistics(runs.map { run -> run.sensors.sumOf(ResourceTimer::durationMs).toDouble() }),
            networkBytes = analyzer.statistics(runs.map { run -> run.network.totalBytes.toDouble() }),
            energyMah = analyzer.statistics(runs.map { run -> run.energy.sumOf { it.energyMah ?: 0.0 }.takeIf { it > 0 } }),
            warnings = document.warnings,
        )
    }

    private companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class BatteryAnalysisDocument(
    val schemaVersion: Int,
    val sessionId: String,
    val runs: List<BatteryRunDocument>,
    val warnings: List<String> = emptyList(),
)

@Serializable
private data class BatteryRunDocument(
    val runId: String,
    val iteration: Int,
    val durationMs: Long,
    val networkBytes: Long,
    val wakelocks: List<ResourceTimerDocument> = emptyList(),
    val alarms: List<ResourceTimerDocument> = emptyList(),
    val jobs: List<ResourceTimerDocument> = emptyList(),
    val sensors: List<ResourceTimerDocument> = emptyList(),
    val energy: List<EnergyEstimateDocument> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    fun toModel(sessionId: String): BatteryRunDelta =
        BatteryRunDelta(
            runId = runId,
            sessionId = sessionId,
            iteration = iteration,
            durationMs = durationMs,
            wakelocks = wakelocks.map(ResourceTimerDocument::toModel),
            alarms = alarms.map(ResourceTimerDocument::toModel),
            jobs = jobs.map(ResourceTimerDocument::toModel),
            sensors = sensors.map(ResourceTimerDocument::toModel),
            network = NetworkUsage(wifiRxBytes = networkBytes),
            energy = energy.map(EnergyEstimateDocument::toModel),
            history = emptyList(),
            warnings = warnings,
        )
}

@Serializable
private data class ResourceTimerDocument(
    val name: String,
    val durationMs: Long,
    val count: Long,
    val confidence: String,
) {
    fun toModel(): ResourceTimer =
        ResourceTimer(
            name = name,
            durationMs = durationMs,
            count = count,
            confidence = EvidenceConfidence.valueOf(confidence),
        )
}

@Serializable
private data class EnergyEstimateDocument(
    val component: String,
    val energyMah: Double? = null,
    val energyUws: Long? = null,
    val source: String,
    val scope: String,
    val confidence: String,
) {
    fun toModel(): EnergyEstimate =
        EnergyEstimate(
            component = component,
            energyMah = energyMah,
            energyUws = energyUws,
            source = EnergyEvidenceKind.valueOf(source),
            attributionScope = AttributionScope.valueOf(scope),
            confidence = EvidenceConfidence.valueOf(confidence),
        )
}
