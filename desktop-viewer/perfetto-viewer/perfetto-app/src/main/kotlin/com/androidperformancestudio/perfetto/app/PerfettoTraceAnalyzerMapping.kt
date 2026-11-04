package com.androidperformancestudio.perfetto.app

import com.androidperformancestudio.perfetto.analysis.DiagnosticFinding
import com.androidperformancestudio.perfetto.analysis.DiagnosticFindingLevel
import com.androidperformancestudio.perfetto.analysis.DiagnosticMetric
import com.androidperformancestudio.perfetto.analysis.DiagnosticMetricKind
import com.androidperformancestudio.perfetto.analysis.DiagnosticPreset
import com.androidperformancestudio.perfetto.analysis.DiagnosticQuery
import com.androidperformancestudio.perfetto.analysis.DiagnosticResult
import com.androidperformancestudio.perfetto.analysis.TraceSliceSearch
import com.androidperformancestudio.perfetto.presentation.TraceDiagnosticPresetOption
import com.androidperformancestudio.perfetto.presentation.TraceInsightSeverity
import com.androidperformancestudio.perfetto.presentation.TraceResultTable
import com.androidperformancestudio.perfetto.presentation.TraceSearchHit
import com.androidperformancestudio.perfetto.presentation.TraceWorkspaceBookmark
import com.androidperformancestudio.perfetto.storage.TraceBookmark
import com.androidperformancestudio.perfetto_app.generated.resources.Res
import com.androidperformancestudio.perfetto_app.generated.resources.trace_metric_janky_frames
import com.androidperformancestudio.perfetto_app.generated.resources.trace_metric_max_blocked
import com.androidperformancestudio.perfetto_app.generated.resources.trace_metric_max_latency
import com.androidperformancestudio.perfetto_app.generated.resources.trace_metric_max_wakeup
import com.androidperformancestudio.perfetto_app.generated.resources.trace_metric_no_data
import com.androidperformancestudio.perfetto_app.generated.resources.trace_metric_peak_rss
import com.androidperformancestudio.perfetto_app.generated.resources.trace_metric_subject
import com.androidperformancestudio.perfetto_app.generated.resources.trace_metric_top_cpu
import com.androidperformancestudio.perfetto_app.generated.resources.trace_metric_top_frequency
import com.androidperformancestudio.perfetto_app.generated.resources.trace_metric_top_state
import com.androidperformancestudio.perfetto_app.generated.resources.trace_preset_all
import com.androidperformancestudio.perfetto_app.generated.resources.trace_preset_binder
import com.androidperformancestudio.perfetto_app.generated.resources.trace_preset_core
import com.androidperformancestudio.perfetto_app.generated.resources.trace_preset_io
import com.androidperformancestudio.perfetto_app.generated.resources.trace_preset_jank
import com.androidperformancestudio.perfetto_app.generated.resources.trace_preset_memory
import com.androidperformancestudio.perfetto_app.generated.resources.trace_preset_power
import com.androidperformancestudio.perfetto_app.generated.resources.trace_preset_scheduling
import com.androidperformancestudio.platform.perfetto.TraceQuerySchema
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.util.Locale

private const val MISSING_VALUE = "—"

internal fun DiagnosticFindingLevel.workspaceSeverity(): TraceInsightSeverity =
    when (this) {
        DiagnosticFindingLevel.CRITICAL -> TraceInsightSeverity.CRITICAL
        DiagnosticFindingLevel.WARNING -> TraceInsightSeverity.WARNING
        DiagnosticFindingLevel.INFO, DiagnosticFindingLevel.NO_DATA -> TraceInsightSeverity.INFO
    }

/** Whole numbers stay whole; fractional aggregates keep the query's own millisecond precision to one decimal. */
internal fun formatMetricNumber(value: Double): String =
    if (value % 1.0 == 0.0 && kotlin.math.abs(value) < Long.MAX_VALUE) {
        value.toLong().toString()
    } else {
        String.format(Locale.ROOT, "%.1f", value)
    }

internal fun DiagnosticFinding.aggregateText(language: UiLanguage): String {
    if (level == DiagnosticFindingLevel.NO_DATA) return localizedStringResource(Res.string.trace_metric_no_data, language)
    val headline = metric.headline(language).orEmpty()
    val subject = metric.subject
    return if (headline.isEmpty() || subject == null) {
        headline
    } else {
        localizedStringResource(Res.string.trace_metric_subject, language, headline, subject)
    }
}

private fun DiagnosticMetric.headline(language: UiLanguage): String? {
    val number = formatMetricNumber(value)
    val resource =
        when (kind) {
            DiagnosticMetricKind.JANKY_FRAMES -> Res.string.trace_metric_janky_frames
            DiagnosticMetricKind.MAX_LATENCY_MS -> Res.string.trace_metric_max_latency
            DiagnosticMetricKind.MAX_BLOCKED_MS -> Res.string.trace_metric_max_blocked
            DiagnosticMetricKind.MAX_WAKEUP_US -> Res.string.trace_metric_max_wakeup
            DiagnosticMetricKind.PEAK_RSS_KB -> Res.string.trace_metric_peak_rss
            DiagnosticMetricKind.TOP_CPU_MS -> Res.string.trace_metric_top_cpu
            DiagnosticMetricKind.TOP_FREQUENCY_MHZ -> Res.string.trace_metric_top_frequency
            DiagnosticMetricKind.TOP_STATE_MS -> Res.string.trace_metric_top_state
            // Row counts are already reported by the localized result-rows summary.
            DiagnosticMetricKind.ROWS -> null
        }
    return when {
        resource == null -> null
        kind == DiagnosticMetricKind.JANKY_FRAMES ->
            localizedStringResource(resource, language, number, total?.let(::formatMetricNumber) ?: MISSING_VALUE)
        else -> localizedStringResource(resource, language, number)
    }
}

internal fun DiagnosticPreset.workspaceOption(language: UiLanguage): TraceDiagnosticPresetOption {
    val resource =
        when (id) {
            "core" -> Res.string.trace_preset_core
            "jank" -> Res.string.trace_preset_jank
            "scheduling" -> Res.string.trace_preset_scheduling
            "binder" -> Res.string.trace_preset_binder
            "io" -> Res.string.trace_preset_io
            "memory" -> Res.string.trace_preset_memory
            "power" -> Res.string.trace_preset_power
            "all" -> Res.string.trace_preset_all
            else -> null
        }
    return TraceDiagnosticPresetOption(
        id = id,
        title = resource?.let { localizedStringResource(it, language) } ?: title,
        queryIds = queryIds,
    )
}

internal fun DiagnosticQuery.timelineTimestampColumn(): Int =
    columns.indexOfFirst { column -> column == "timestamp_ns" || column == "ts" || column.endsWith("_timestamp_ns") }

internal fun DiagnosticResult.workspaceTable(
    query: DiagnosticQuery,
    artifactId: String,
    title: String,
): TraceResultTable {
    val timestampColumn = query.timelineTimestampColumn()
    return TraceResultTable(
        queryId = query.id,
        artifactId = artifactId,
        title = title,
        description = query.description,
        sql = query.sql,
        schemaVersion = TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION,
        columns = columns,
        rows = rows.map { row -> row.values.map { it ?: MISSING_VALUE } },
        rowTimestampsNanos =
            rows.map { row ->
                if (timestampColumn < 0) {
                    null
                } else {
                    row.values
                        .getOrNull(timestampColumn)
                        ?.toLongOrNull()
                        ?.takeIf { it >= 0 }
                }
            },
    )
}

/** Search hits keep only rows with a real timestamp; thread and process are joined when both are known. */
internal fun DiagnosticResult.searchHits(): List<TraceSearchHit> {
    fun column(name: String) = TraceSliceSearch.columns.indexOf(name)
    return rows.mapNotNull { row ->
        val values = row.values
        val timestamp = values.getOrNull(column("timestamp_ns"))?.toLongOrNull() ?: return@mapNotNull null
        TraceSearchHit(
            timestampNanos = timestamp,
            durationNanos = values.getOrNull(column("duration_ns"))?.toLongOrNull(),
            name = values.getOrNull(column("slice_name")) ?: MISSING_VALUE,
            threadLabel =
                listOfNotNull(values.getOrNull(column("thread_name")), values.getOrNull(column("process_name")))
                    .filter(String::isNotBlank)
                    .joinToString(" · ")
                    .ifBlank { MISSING_VALUE },
        )
    }
}

internal fun TraceBookmark.workspaceBookmark(): TraceWorkspaceBookmark = TraceWorkspaceBookmark(id, label, timestampNanos)
