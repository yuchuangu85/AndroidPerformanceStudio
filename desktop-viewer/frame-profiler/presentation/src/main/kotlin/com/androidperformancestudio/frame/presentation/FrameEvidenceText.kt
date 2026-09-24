package com.androidperformancestudio.frame.presentation

import com.androidperformancestudio.frame.analysis.AnalyzedFrame
import com.androidperformancestudio.frame.presentation.generated.resources.Res
import com.androidperformancestudio.frame.presentation.generated.resources.budget
import com.androidperformancestudio.frame.presentation.generated.resources.budget_source
import com.androidperformancestudio.frame.presentation.generated.resources.duration
import com.androidperformancestudio.frame.presentation.generated.resources.frame
import com.androidperformancestudio.frame.presentation.generated.resources.frame_timeline_vsync_id
import com.androidperformancestudio.frame.presentation.generated.resources.jank_types
import com.androidperformancestudio.frame.presentation.generated.resources.largest_reported_stage
import com.androidperformancestudio.frame.presentation.generated.resources.missed_vsync
import com.androidperformancestudio.frame.presentation.generated.resources.platform_jank
import com.androidperformancestudio.frame.presentation.generated.resources.session_id
import com.androidperformancestudio.frame.presentation.generated.resources.source
import com.androidperformancestudio.frame.presentation.generated.resources.stage_breakdown
import com.androidperformancestudio.frame.presentation.generated.resources.state_detail
import com.androidperformancestudio.frame.presentation.generated.resources.state_labels
import com.androidperformancestudio.frame.presentation.generated.resources.verdict
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

internal fun AnalyzedFrame.copyEvidenceText(language: UiLanguage): String {
    val lines = mutableListOf<String>()

    fun add(
        label: String,
        value: String,
    ) {
        lines += "$label: ${value.escapeEvidenceField()}"
    }

    add(localizedStringResource(Res.string.session_id, language), sample.sessionId)
    add(localizedStringResource(Res.string.source, language), sample.source.name)
    add(localizedStringResource(Res.string.frame, language), "#${sample.frameId}")
    add(localizedStringResource(Res.string.frame_timeline_vsync_id, language), sample.frameTimelineVsyncId?.toString() ?: "—")
    add(localizedStringResource(Res.string.duration, language), sample.resolvedDurationNs().formatMillis())
    add(localizedStringResource(Res.string.budget, language), sample.displayBudget())
    add(localizedStringResource(Res.string.budget_source, language), sample.expectedDurationSource.name)
    add(localizedStringResource(Res.string.verdict, language), deadlineVerdict.name)
    add(localizedStringResource(Res.string.missed_vsync, language), missedVsyncCount?.toString() ?: "—")
    add(localizedStringResource(Res.string.platform_jank, language), sample.platformJank?.toString() ?: "—")
    add(localizedStringResource(Res.string.jank_types, language), platformJankTypes.joinToString { it.name }.ifEmpty { "—" })
    add(localizedStringResource(Res.string.largest_reported_stage, language), largestReportedStage ?: "—")

    lines += "${localizedStringResource(Res.string.stage_breakdown, language)}:"
    val stages = sample.stages.values()
    if (stages.isEmpty()) {
        lines += "  —"
    } else {
        stages.forEach { (name, duration) -> lines += "  ${name.escapeEvidenceField()}: ${duration.formatMillis()}" }
    }

    lines += "${localizedStringResource(Res.string.state_labels, language)}:"
    if (sample.states.isEmpty()) {
        lines += "  —"
    } else {
        sample.states.toSortedMap().forEach { (key, value) ->
            lines +=
                "  ${localizedStringResource(Res.string.state_detail, language, key.escapeEvidenceField())}: ${value.escapeEvidenceField()}"
        }
    }
    return lines.joinToString("\n")
}

private fun String.escapeEvidenceField(): String =
    buildString {
        this@escapeEvidenceField.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.isISOControl()) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
    }
