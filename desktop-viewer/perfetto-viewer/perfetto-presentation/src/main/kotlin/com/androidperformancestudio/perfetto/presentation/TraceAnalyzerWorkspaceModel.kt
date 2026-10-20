package com.androidperformancestudio.perfetto.presentation

enum class TraceInsightSeverity {
    CRITICAL,
    WARNING,
    INFO,
    OPPORTUNITY,
}

enum class TraceInsightCategory {
    FRAME,
    BINDER,
    LONG_TASK,
    RENDERING,
    CPU,
    MEMORY,
    INPUT,
    OTHER,
}

enum class TraceInsightFilter {
    ALL,
    CRITICAL,
    WARNING,
    INFO,
}

enum class TraceDetailTab {
    RESULT,
    QUERY,
    SEARCH,
}

data class TraceWorkspaceArtifact(
    val artifactId: String,
    val displayName: String,
    val locationLabel: String,
    val completenessLabel: String,
    val limitations: List<String> = emptyList(),
    val viewerAvailable: Boolean = true,
)

data class TraceWorkspaceSession(
    val sessionId: String,
    val title: String,
    val capturedAtLabel: String,
    val durationLabel: String,
    val artifact: TraceWorkspaceArtifact? = null,
)

data class TraceInsightEvidence(
    val queryId: String,
    val artifactId: String,
    val title: String,
    val resultRowsSummary: String? = null,
    val limitations: List<String> = emptyList(),
    val error: String? = null,
    val timestampNanos: Long? = null,
    val severity: TraceInsightSeverity = TraceInsightSeverity.INFO,
    val category: TraceInsightCategory = TraceInsightCategory.OTHER,
    /** Localized headline aggregate derived from the real result rows, e.g. "12 / 840 janky frames". */
    val aggregate: String? = null,
)

data class TraceDiagnosticPresetOption(
    val id: String,
    val title: String,
    val queryIds: List<String>,
)

/** The rows a diagnostic really returned, plus the read-only query that produced them. */
data class TraceResultTable(
    val queryId: String,
    val artifactId: String,
    val title: String,
    val description: String,
    val sql: String,
    val schemaVersion: String,
    val columns: List<String>,
    val rows: List<List<String>>,
    val rowTimestampsNanos: List<Long?> = rows.map { null },
) {
    init {
        require(rowTimestampsNanos.size == rows.size) { "every result row needs a timestamp slot" }
    }
}

data class TraceSearchHit(
    val timestampNanos: Long,
    val durationNanos: Long?,
    val name: String,
    val threadLabel: String,
)

data class TraceWorkspaceBookmark(
    val bookmarkId: String,
    val label: String,
    val timestampNanos: Long,
)

data class TraceBookmarkRequest(
    val label: String,
    val timestampNanos: Long,
    val sourceQueryId: String? = null,
)

data class TraceAnalyzerWorkspaceState(
    val sessions: List<TraceWorkspaceSession> = emptyList(),
    val selectedSessionId: String? = null,
    val searchQuery: String = "",
    val activeTrace: TraceWorkspaceArtifact? = null,
    val insights: List<TraceInsightEvidence> = emptyList(),
    val selectedQueryId: String? = null,
    val captureVisible: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val presets: List<TraceDiagnosticPresetOption> = emptyList(),
    val selectedPresetId: String? = null,
    val presetRunning: Boolean = false,
    val insightFilter: TraceInsightFilter = TraceInsightFilter.ALL,
    val detailTab: TraceDetailTab = TraceDetailTab.RESULT,
    val resultTable: TraceResultTable? = null,
    val traceSearchQuery: String = "",
    val searchedTerm: String? = null,
    val searchHits: List<TraceSearchHit> = emptyList(),
    val searchRunning: Boolean = false,
    val searchError: String? = null,
    val bookmarks: List<TraceWorkspaceBookmark> = emptyList(),
)

data class TraceAnalyzerWorkspaceActions(
    val onSearchChange: (String) -> Unit = {},
    val onSelectSession: (String) -> Unit = {},
    val onOpenSession: (String) -> Unit = {},
    val onDeleteSession: (String) -> Unit = {},
    val onToggleCapture: () -> Unit = {},
    val onRunDiagnostic: (String) -> Unit = {},
    val onOpenInTimeline: (TraceInsightEvidence) -> Unit = {},
    val onOpenViewer: (TraceWorkspaceArtifact) -> Unit = {},
    val onExport: (TraceWorkspaceArtifact) -> Unit = {},
    val onSelectPreset: (String) -> Unit = {},
    val onRunPreset: () -> Unit = {},
    val onInsightFilterChange: (TraceInsightFilter) -> Unit = {},
    val onSelectDetailTab: (TraceDetailTab) -> Unit = {},
    val onViewInsightResult: (String) -> Unit = {},
    val onTraceSearchChange: (String) -> Unit = {},
    val onRunTraceSearch: () -> Unit = {},
    val onOpenTimestamp: (Long) -> Unit = {},
    val onAddBookmark: (TraceBookmarkRequest) -> Unit = {},
    val onDeleteBookmark: (String) -> Unit = {},
)

data class TraceWorkspaceSummary(
    val category: TraceInsightCategory,
    val value: String?,
    val severity: TraceInsightSeverity?,
    val sourceTitle: String? = null,
)

private const val NANOS_PER_MILLI = 1_000_000.0
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val MILLIS_PER_SECOND = 1_000.0

private val SUMMARY_CATEGORIES =
    listOf(
        TraceInsightCategory.FRAME,
        TraceInsightCategory.BINDER,
        TraceInsightCategory.LONG_TASK,
        TraceInsightCategory.RENDERING,
    )

fun filterTraceWorkspaceSessions(
    sessions: List<TraceWorkspaceSession>,
    searchQuery: String,
): List<TraceWorkspaceSession> {
    val query = searchQuery.trim()
    if (query.isEmpty()) return sessions
    return sessions.filter { session ->
        sequenceOf(
            session.title,
            session.capturedAtLabel,
            session.durationLabel,
            session.artifact?.artifactId,
            session.artifact?.displayName,
        ).filterNotNull().any { value -> value.contains(query, ignoreCase = true) }
    }
}

/** Each summary card shows the most severe successful evidence in its category, never a placeholder metric. */
fun buildTraceWorkspaceSummaries(insights: List<TraceInsightEvidence>): List<TraceWorkspaceSummary> =
    SUMMARY_CATEGORIES.map { category ->
        val evidence =
            insights
                .filter { insight -> insight.category == category && insight.error == null && insight.summaryValue() != null }
                .minByOrNull { it.severity.ordinal }
        TraceWorkspaceSummary(
            category = category,
            value = evidence?.summaryValue(),
            severity = evidence?.severity,
            sourceTitle = evidence?.title,
        )
    }

private fun TraceInsightEvidence.summaryValue(): String? =
    aggregate?.takeIf(String::isNotBlank) ?: resultRowsSummary?.takeIf(String::isNotBlank)

fun canOpenInsightInTimeline(
    insight: TraceInsightEvidence,
    activeTrace: TraceWorkspaceArtifact?,
): Boolean =
    insight.timestampNanos != null &&
        insight.error == null &&
        activeTrace?.viewerAvailable == true &&
        activeTrace.artifactId == insight.artifactId

/** Insights belonging to the selected preset (all insights when no preset is known), then the severity filter. */
fun visibleTraceInsights(
    insights: List<TraceInsightEvidence>,
    presets: List<TraceDiagnosticPresetOption>,
    selectedPresetId: String?,
    filter: TraceInsightFilter,
): List<TraceInsightEvidence> {
    val preset = presets.firstOrNull { it.id == selectedPresetId }
    val inPreset = preset?.let { option -> insights.filter { it.queryId in option.queryIds } } ?: insights
    return inPreset.filter { it.matches(filter) }
}

fun traceInsightCounts(insights: List<TraceInsightEvidence>): Map<TraceInsightFilter, Int> =
    TraceInsightFilter.entries.associateWith { filter -> insights.count { it.matches(filter) } }

private fun TraceInsightEvidence.matches(filter: TraceInsightFilter): Boolean =
    when (filter) {
        TraceInsightFilter.ALL -> true
        TraceInsightFilter.CRITICAL -> severity == TraceInsightSeverity.CRITICAL
        TraceInsightFilter.WARNING -> severity == TraceInsightSeverity.WARNING
        TraceInsightFilter.INFO -> severity == TraceInsightSeverity.INFO || severity == TraceInsightSeverity.OPPORTUNITY
    }

/** Locatable insights in trace order; these are anchors into Perfetto, not a synthetic timeline. */
fun traceTimelineAnchors(
    insights: List<TraceInsightEvidence>,
    activeTrace: TraceWorkspaceArtifact?,
): List<TraceInsightEvidence> = insights.filter { canOpenInsightInTimeline(it, activeTrace) }.sortedBy { it.timestampNanos }

/** Trace timestamps are absolute trace-clock values, so they are shown in seconds without a fabricated origin. */
fun formatTraceTimestamp(timestampNanos: Long): String = "%.6f s".format(java.util.Locale.ROOT, timestampNanos / NANOS_PER_SECOND)

fun formatTraceDurationNanos(durationNanos: Long?): String {
    if (durationNanos == null || durationNanos < 0) return "—"
    val millis = durationNanos / NANOS_PER_MILLI
    return if (millis >= MILLIS_PER_SECOND) {
        "%.3f s".format(java.util.Locale.ROOT, millis / MILLIS_PER_SECOND)
    } else {
        "%.3f ms".format(java.util.Locale.ROOT, millis)
    }
}
