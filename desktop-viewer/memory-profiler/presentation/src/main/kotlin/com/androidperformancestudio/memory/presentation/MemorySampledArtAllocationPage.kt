@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.art_sampled_analysis_failed
import com.androidperformancestudio.memory.presentation.generated.resources.art_sampled_analysis_unavailable
import com.androidperformancestudio.memory.presentation.generated.resources.art_sampled_callsite
import com.androidperformancestudio.memory.presentation.generated.resources.art_sampled_evidence_boundary
import com.androidperformancestudio.memory.presentation.generated.resources.art_sampled_import_details
import com.androidperformancestudio.memory.presentation.generated.resources.art_sampled_no_samples
import com.androidperformancestudio.memory.presentation.generated.resources.art_sampled_recording
import com.androidperformancestudio.memory.presentation.generated.resources.art_sampled_summary
import com.androidperformancestudio.memory.presentation.generated.resources.art_sampled_trace_details
import com.androidperformancestudio.memory.presentation.generated.resources.art_sampled_trace_source
import com.androidperformancestudio.memory.presentation.generated.resources.art_sampled_trace_unavailable
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

/** Displays only allocation counts and call stacks verified by Trace Processor. */
@Composable
public fun MemorySampledArtAllocationPage(
    trace: MemorySampledArtTrace?,
    analysis: MemorySampledArtAnalysis?,
    isRecording: Boolean,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).testTag("memory-art-sampled-page"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            localizedStringResource(Res.string.art_sampled_trace_source, language),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            localizedStringResource(Res.string.art_sampled_evidence_boundary, language),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            isRecording -> Text(localizedStringResource(Res.string.art_sampled_recording, language))
            trace == null -> Text(localizedStringResource(Res.string.art_sampled_trace_unavailable, language))
            else -> {
                if (trace.origin == MemorySampledArtOrigin.IMPORT) {
                    Text(localizedStringResource(Res.string.art_sampled_import_details, language, trace.fileSizeBytes))
                } else {
                    Text(
                        localizedStringResource(
                            Res.string.art_sampled_trace_details,
                            language,
                            trace.packageName.orEmpty(),
                            trace.pid ?: 0,
                            trace.sdkLevel ?: 0,
                            trace.samplingIntervalBytes ?: 0,
                            trace.durationMs ?: 0L,
                            trace.fileSizeBytes,
                        ),
                    )
                }
                Text(trace.source)
                Text(trace.file, modifier = Modifier.testTag("memory-art-sampled-trace-file"))
                SampledArtAnalysisContent(analysis, language)
            }
        }
    }
}

@Composable
private fun SampledArtAnalysisContent(
    analysis: MemorySampledArtAnalysis?,
    language: UiLanguage,
) {
    when (analysis) {
        is MemorySampledArtAnalysis.Analyzed -> SampledArtSamples(analysis, language)
        is MemorySampledArtAnalysis.Unavailable ->
            Text(
                localizedStringResource(Res.string.art_sampled_analysis_unavailable, language, analysis.reason),
                modifier = Modifier.testTag("memory-art-sampled-analysis-unavailable"),
            )
        is MemorySampledArtAnalysis.Failed ->
            Text(
                localizedStringResource(Res.string.art_sampled_analysis_failed, language, analysis.reason),
                modifier = Modifier.testTag("memory-art-sampled-analysis-failed"),
            )
        null -> Text(localizedStringResource(Res.string.art_sampled_analysis_unavailable, language, ""))
    }
}

@Composable
private fun SampledArtSamples(
    analysis: MemorySampledArtAnalysis.Analyzed,
    language: UiLanguage,
) {
    if (analysis.sampleRows == 0L) {
        Text(
            localizedStringResource(Res.string.art_sampled_no_samples, language),
            modifier = Modifier.testTag("memory-art-sampled-empty"),
        )
        return
    }
    Text(
        localizedStringResource(
            Res.string.art_sampled_summary,
            language,
            analysis.sampleRows,
            analysis.sampledObjectCount,
            analysis.sampledBytes,
        ),
        modifier = Modifier.testTag("memory-art-sampled-summary"),
    )
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(analysis.callSites, key = MemorySampledArtCallSite::callsiteId) { site ->
            Text(
                localizedStringResource(
                    Res.string.art_sampled_callsite,
                    language,
                    site.sampledBytes,
                    site.sampledObjectCount,
                    site.callStack.lastOrNull() ?: "#${site.callsiteId}",
                ),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (site.callStack.size > 1) {
                Text(
                    site.callStack.joinToString(" → "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
