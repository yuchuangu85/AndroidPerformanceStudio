@file:Suppress("MaxLineLength")

package com.androidperformancestudio.frame.export

import com.androidperformancestudio.frame.analysis.FrameAnalysisResult
import java.nio.file.Files
import java.nio.file.Path

public class FrameCsvExporter {
    public fun export(
        result: FrameAnalysisResult,
        output: Path,
    ) {
        output.parent?.let(Files::createDirectories)
        Files.newBufferedWriter(output).use { writer ->
            writer.appendLine(HEADER)
            result.frames.forEach { frame ->
                val sample = frame.sample
                writer.appendLine(
                    listOf(
                        sample.frameId,
                        sample.source,
                        sample.intendedVsyncNs,
                        sample.resolvedDurationNs(),
                        sample.expectedDurationNs,
                        sample.expectedDurationSource,
                        frame.verdict,
                        frame.severity,
                        frame.missedVsyncCount,
                        frame.bottleneckStage,
                        frame.jankTypes.joinToString("|"),
                    ).joinToString(",") { value -> value.toString().escapeCsv() },
                )
            }
        }
    }

    private fun String.escapeCsv(): String = if (contains(',') || contains('"') || contains('\n')) "\"${replace("\"", "\"\"")}\"" else this

    private companion object {
        const val HEADER =
            "frame_id,source,intended_vsync_ns,duration_ns,expected_duration_ns,expected_source," +
                "verdict,severity,missed_vsync_count,bottleneck_stage,jank_types"
    }
}
