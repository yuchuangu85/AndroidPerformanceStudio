@file:Suppress("LongMethod")

package com.androidperformancestudio.startup.export

import com.androidperformancestudio.startup.analysis.StartupAnalysisResult
import com.androidperformancestudio.startup.model.StartupStatistics
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

public class StartupJsonExporter {
    public fun export(
        analysis: StartupAnalysisResult,
        output: Path,
    ) {
        output.toAbsolutePath().parent?.let(Files::createDirectories)
        val root =
            buildJsonObject {
                put("schemaVersion", 1)
                put(
                    "summary",
                    buildJsonObject {
                        put("totalTime", analysis.totalTime.toJson())
                        put("firstFrame", analysis.firstFrame.toJson())
                        put("fullyDrawn", analysis.fullyDrawn.toJson())
                        put("agentFirstFrame", analysis.agentFirstFrame.toJson())
                    },
                )
                put("warnings", buildJsonArray { analysis.warnings.forEach(::add) })
                put(
                    "runs",
                    buildJsonArray {
                        analysis.runs.forEach { run ->
                            add(
                                buildJsonObject {
                                    put("iteration", run.iteration)
                                    put("runId", run.id)
                                    put("requestedType", run.requestedType.name)
                                    put("observedType", run.observedType.name)
                                    put("totalTimeMs", run.platform.totalTimeMs)
                                    put("thisTimeMs", run.platform.thisTimeMs)
                                    put("waitTimeMs", run.platform.waitTimeMs)
                                    put("displayedTimeMs", run.platform.displayedTimeMs)
                                    put("fullyDrawnTimeMs", run.platform.fullyDrawnTimeMs)
                                    put("agentAvailable", run.rawEvidence.agentAvailable)
                                    put("diagnostics", buildJsonArray { run.diagnostics.forEach(::add) })
                                    run.context?.let { context ->
                                        put(
                                            "context",
                                            buildJsonObject {
                                                put("deviceSerial", context.deviceSerial)
                                                put("packageName", context.packageName)
                                                put("componentName", context.componentName)
                                            },
                                        )
                                    }
                                    put(
                                        "metricEvidence",
                                        buildJsonObject {
                                            put("ttid", run.ttidEvidence.toJson())
                                            put("ttfd", run.ttfdEvidence.toJson())
                                            put("agentFirstFrame", run.agentFirstFrameEvidence.toJson())
                                        },
                                    )
                                    run.compilationEvidence?.let { evidence ->
                                        put(
                                            "compilationEvidence",
                                            buildJsonObject {
                                                put("requestedMode", evidence.requestedMode.name)
                                                put("compilerFilterBefore", evidence.compilerFilterBefore)
                                                put("compilerFilterAfter", evidence.compilerFilterAfter)
                                                put("profileStateBefore", evidence.profileStateBefore)
                                                put("profileStateAfter", evidence.profileStateAfter)
                                                put("preparationOutput", evidence.preparationOutput)
                                                put("verified", evidence.verified)
                                                put("failureReason", evidence.failureReason)
                                                put("profileSource", evidence.profileSource.name)
                                                put("profileSourceDeclared", evidence.profileSourceDeclared)
                                            },
                                        )
                                    }
                                    run.environmentEvidence?.let { evidence ->
                                        put(
                                            "environmentEvidence",
                                            buildJsonObject {
                                                put("deviceModel", evidence.deviceModel)
                                                put("apiLevel", evidence.apiLevel)
                                                put("emulator", evidence.emulator)
                                                put("batteryPercent", evidence.batteryPercent)
                                                put("charging", evidence.charging)
                                                put("thermalStatus", evidence.thermalStatus)
                                                put("capturedAt", evidence.capturedAt?.toString())
                                                put("failures", buildJsonArray { evidence.failures.forEach(::add) })
                                            },
                                        )
                                    }
                                    run.traceEvidence?.let { evidence ->
                                        put(
                                            "traceEvidence",
                                            buildJsonObject {
                                                put("file", evidence.file)
                                                put("captured", evidence.captured)
                                                put("truncated", evidence.truncated)
                                                put("failureReason", evidence.failureReason)
                                            },
                                        )
                                    }
                                    put("warnings", buildJsonArray { run.warnings.forEach(::add) })
                                    put(
                                        "milestones",
                                        buildJsonArray {
                                            run.milestones.forEach { milestone ->
                                                add(
                                                    buildJsonObject {
                                                        put("kind", milestone.kind.name)
                                                        put("elapsedRealtimeNs", milestone.elapsedRealtimeNs)
                                                        put("durationMs", milestone.durationMs)
                                                        put("source", milestone.source.name)
                                                        put("confidence", milestone.confidence.name)
                                                        put("activity", milestone.activityName)
                                                    },
                                                )
                                            }
                                        },
                                    )
                                    put(
                                        "phases",
                                        buildJsonArray {
                                            run.phases.forEach { phase ->
                                                add(
                                                    buildJsonObject {
                                                        put("name", phase.name)
                                                        put("start", phase.start.name)
                                                        put("end", phase.end.name)
                                                        put("durationNs", phase.durationNs)
                                                        put("confidence", phase.confidence.name)
                                                    },
                                                )
                                            }
                                        },
                                    )
                                    put(
                                        "rawEvidence",
                                        buildJsonObject {
                                            put("amStartOutput", run.rawEvidence.amStartOutput)
                                            put("eventLogOutput", run.rawEvidence.eventLogOutput)
                                            put("compilationOutput", run.rawEvidence.compilationOutput)
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }
        Files.writeString(output, JSON.encodeToString(root))
    }

    private fun StartupStatistics.toJson() =
        buildJsonObject {
            put("count", count)
            put("missingCount", missingCount)
            put("minimumMs", minimumMs)
            put("maximumMs", maximumMs)
            put("medianMs", medianMs)
            put("meanMs", meanMs)
            put("p90Ms", p90Ms)
            put("p95Ms", p95Ms)
            put("standardDeviationMs", standardDeviationMs)
            put("medianAbsoluteDeviationMs", medianAbsoluteDeviationMs)
            put("p90LowResolution", p90LowResolution)
            put("p95LowResolution", p95LowResolution)
        }

    private fun com.androidperformancestudio.startup.model.StartupMetricEvidence.toJson() =
        buildJsonObject {
            put("source", source?.name)
            put("confidence", confidence.name)
            put("unavailableReason", unavailableReason)
        }

    private companion object {
        val JSON = Json { prettyPrint = true }
    }
}
