@file:Suppress("LongMethod", "MaxLineLength")

package com.androidperformancestudio.startup.export

import com.androidperformancestudio.startup.analysis.StartupAnalysisResult
import java.nio.file.Files
import java.nio.file.Path

public class StartupCsvExporter {
    public fun export(
        analysis: StartupAnalysisResult,
        output: Path,
    ) {
        output.toAbsolutePath().parent?.let(Files::createDirectories)
        val content =
            buildString {
                appendLine(
                    "iteration,runId,requestedType,observedType,totalTimeMs,thisTimeMs,waitTimeMs,displayedTimeMs,fullyDrawnTimeMs,agentAvailable,warnings,ttidSource,ttidUnavailableReason,ttfdSource,ttfdUnavailableReason,agentFirstFrameSource,agentFirstFrameUnavailableReason,compilerFilter,compilationVerified,profileSource,profileSourceDeclared,deviceModel,apiLevel,emulator,batteryPercent,charging,thermalStatus,traceFile,traceCaptured,traceTruncated,traceFailure,diagnostics",
                )
                analysis.runs.forEach { run ->
                    appendLine(
                        listOf(
                            run.iteration,
                            run.id,
                            run.requestedType,
                            run.observedType,
                            run.platform.totalTimeMs.orEmpty(),
                            run.platform.thisTimeMs.orEmpty(),
                            run.platform.waitTimeMs.orEmpty(),
                            run.platform.displayedTimeMs.orEmpty(),
                            run.platform.fullyDrawnTimeMs.orEmpty(),
                            run.rawEvidence.agentAvailable,
                            run.warnings.joinToString(" | "),
                            run.ttidEvidence.source
                                ?.name
                                .orEmpty(),
                            run.ttidEvidence.unavailableReason.orEmpty(),
                            run.ttfdEvidence.source
                                ?.name
                                .orEmpty(),
                            run.ttfdEvidence.unavailableReason.orEmpty(),
                            run.agentFirstFrameEvidence.source
                                ?.name
                                .orEmpty(),
                            run.agentFirstFrameEvidence.unavailableReason.orEmpty(),
                            run.compilationEvidence?.compilerFilterAfter.orEmpty(),
                            run.compilationEvidence
                                ?.verified
                                ?.toString()
                                .orEmpty(),
                            run.compilationEvidence
                                ?.profileSource
                                ?.name
                                .orEmpty(),
                            run.compilationEvidence
                                ?.profileSourceDeclared
                                ?.toString()
                                .orEmpty(),
                            run.environmentEvidence?.deviceModel.orEmpty(),
                            run.environmentEvidence
                                ?.apiLevel
                                ?.toString()
                                .orEmpty(),
                            run.environmentEvidence
                                ?.emulator
                                ?.toString()
                                .orEmpty(),
                            run.environmentEvidence
                                ?.batteryPercent
                                ?.toString()
                                .orEmpty(),
                            run.environmentEvidence
                                ?.charging
                                ?.toString()
                                .orEmpty(),
                            run.environmentEvidence
                                ?.thermalStatus
                                ?.toString()
                                .orEmpty(),
                            run.traceEvidence?.file.orEmpty(),
                            run.traceEvidence
                                ?.captured
                                ?.toString()
                                .orEmpty(),
                            run.traceEvidence
                                ?.truncated
                                ?.toString()
                                .orEmpty(),
                            run.traceEvidence?.failureReason.orEmpty(),
                            run.diagnostics.joinToString(" | "),
                        ).joinToString(",") { it.toString().csv() },
                    )
                }
            }
        Files.writeString(output, content)
    }
}

private fun Long?.orEmpty(): String = this?.toString().orEmpty()

private fun String.csv(): String = "\"${replace("\"", "\"\"")}\""
