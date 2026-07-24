@file:Suppress("MaxLineLength")

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
                    "iteration,runId,requestedType,observedType,totalTimeMs,thisTimeMs,waitTimeMs,displayedTimeMs,fullyDrawnTimeMs,agentAvailable,warnings",
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
                        ).joinToString(",") { it.toString().csv() },
                    )
                }
            }
        Files.writeString(output, content)
    }
}

private fun Long?.orEmpty(): String = this?.toString().orEmpty()

private fun String.csv(): String = "\"${replace("\"", "\"\"")}\""
