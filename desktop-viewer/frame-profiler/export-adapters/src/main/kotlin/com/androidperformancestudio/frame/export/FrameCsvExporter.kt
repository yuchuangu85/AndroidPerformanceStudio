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
                        frame.deadlineVerdict,
                        sample.platformJank,
                        sample.platformJankRuleId,
                        sample.platformJankRuleVersion,
                        frame.severity,
                        frame.missedVsyncCount,
                        frame.largestReportedStage,
                        frame.platformJankTypes.joinToString("|"),
                        sample.frameTimelineVsyncId,
                    ).joinToString(",") { value -> value.toString().escapeCsv() },
                )
            }
        }
    }

    private fun String.escapeCsv(): String = if (contains(',') || contains('"') || contains('\n')) "\"${replace("\"", "\"\"")}\"" else this

    private companion object {
        const val HEADER =
            "frame_id,source,intended_vsync_ns,duration_ns,expected_duration_ns,expected_source," +
                "deadline_verdict,platform_jank_signal,platform_jank_rule_id,platform_jank_rule_version," +
                "severity,missed_vsync_count,largest_reported_stage," +
                "platform_jank_types,frame_timeline_vsync_id"
    }
}
