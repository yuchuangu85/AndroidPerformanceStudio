@file:Suppress("LongMethod", "MagicNumber", "MaxLineLength")

package com.androidperformancestudio.frame.export

import com.androidperformancestudio.frame.analysis.FrameAnalysisResult
import java.nio.file.Files
import java.nio.file.Path

public class FrameJsonExporter {
    public fun export(
        result: FrameAnalysisResult,
        output: Path,
    ) {
        output.parent?.let(Files::createDirectories)
        Files.newBufferedWriter(output).use { writer ->
            writer.appendLine("{")
            writer.appendLine("  \"schemaVersion\": 1,")
            writer.appendLine("  \"summary\": {")
            writer.appendLine("    \"totalFrames\": ${result.summary.totalFrames},")
            writer.appendLine("    \"classifiedFrames\": ${result.summary.classifiedFrames},")
            writer.appendLine("    \"jankFrames\": ${result.summary.jankFrames},")
            writer.appendLine("    \"unknownFrames\": ${result.summary.unknownFrames},")
            writer.appendLine("    \"jankRate\": ${result.summary.jankRate},")
            writer.appendLine("    \"p50DurationNs\": ${result.summary.p50DurationNs.jsonValue()},")
            writer.appendLine("    \"p95DurationNs\": ${result.summary.p95DurationNs.jsonValue()},")
            writer.appendLine("    \"p99DurationNs\": ${result.summary.p99DurationNs.jsonValue()},")
            writer.appendLine("    \"worstDurationNs\": ${result.summary.worstDurationNs.jsonValue()}")
            writer.appendLine("  },")
            writer.appendLine("  \"frames\": [")
            result.frames.forEachIndexed { index, frame ->
                val sample = frame.sample
                writer.appendLine("    {")
                writer.appendLine("      \"frameId\": ${sample.frameId},")
                writer.appendLine("      \"source\": ${sample.source.name.jsonString()},")
                writer.appendLine("      \"packageName\": ${sample.packageName.jsonValue()},")
                writer.appendLine("      \"processId\": ${sample.processId.jsonValue()},")
                writer.appendLine("      \"activityName\": ${sample.activityName.jsonValue()},")
                writer.appendLine("      \"windowId\": ${sample.windowId.jsonValue()},")
                writer.appendLine("      \"intendedVsyncNs\": ${sample.intendedVsyncNs.jsonValue()},")
                writer.appendLine("      \"actualVsyncNs\": ${sample.actualVsyncNs.jsonValue()},")
                writer.appendLine("      \"frameCompletedNs\": ${sample.frameCompletedNs.jsonValue()},")
                writer.appendLine("      \"durationNs\": ${sample.resolvedDurationNs().jsonValue()},")
                writer.appendLine("      \"expectedDurationNs\": ${sample.expectedDurationNs.jsonValue()},")
                writer.appendLine("      \"expectedDurationSource\": ${sample.expectedDurationSource.name.jsonString()},")
                writer.appendLine("      \"verdict\": ${frame.verdict.name.jsonString()},")
                writer.appendLine("      \"severity\": ${frame.severity.name.jsonString()},")
                writer.appendLine("      \"missedVsyncCount\": ${frame.missedVsyncCount.jsonValue()},")
                writer.appendLine("      \"bottleneckStage\": ${frame.bottleneckStage.jsonValue()},")
                writer.appendLine("      \"jankTypes\": ${frame.jankTypes.map { it.name }.jsonArray()},")
                writer.appendLine("      \"platformJank\": ${sample.platformJank.jsonValue()},")
                writer.appendLine("      \"eligibleForJank\": ${sample.eligibleForJank},")
                writer.appendLine("      \"droppedBeforeSample\": ${sample.droppedBeforeSample},")
                writer.appendLine("      \"states\": ${sample.states.jsonObject()},")
                writer.appendLine("      \"stagesNs\": ${sample.stages.values().toMap().jsonObject()}")
                writer.append("    }")
                writer.appendLine(if (index == result.frames.lastIndex) "" else ",")
            }
            writer.appendLine("  ],")
            writer.appendLine("  \"clusters\": [")
            result.clusters.forEachIndexed { index, cluster ->
                writer.appendLine("    {")
                writer.appendLine("      \"id\": ${cluster.id},")
                writer.appendLine("      \"firstFrameId\": ${cluster.firstFrameId},")
                writer.appendLine("      \"lastFrameId\": ${cluster.lastFrameId},")
                writer.appendLine("      \"jankFrameIds\": ${cluster.jankFrameIds.joinToString(prefix = "[", postfix = "]")},")
                writer.appendLine("      \"durationNs\": ${cluster.durationNs},")
                writer.appendLine("      \"worstSeverity\": ${cluster.worstSeverity.name.jsonString()},")
                writer.appendLine("      \"windowId\": ${cluster.windowId.jsonValue()},")
                writer.appendLine("      \"activityName\": ${cluster.activityName.jsonValue()},")
                writer.appendLine("      \"dominantStage\": ${cluster.dominantStage.jsonValue()}")
                writer.append("    }")
                writer.appendLine(if (index == result.clusters.lastIndex) "" else ",")
            }
            writer.appendLine("  ]")
            writer.appendLine("}")
        }
    }
}

private fun Any?.jsonValue(): String =
    when (this) {
        null -> "null"
        is String -> jsonString()
        is Boolean, is Number -> toString()
        else -> toString().jsonString()
    }

private fun String.jsonString(): String =
    buildString {
        append('"')
        this@jsonString.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

private fun List<String>.jsonArray(): String = joinToString(prefix = "[", postfix = "]") { it.jsonString() }

private fun Map<String, *>.jsonObject(): String =
    entries.joinToString(prefix = "{", postfix = "}") { (key, value) -> "${key.jsonString()}: ${value.jsonValue()}" }
