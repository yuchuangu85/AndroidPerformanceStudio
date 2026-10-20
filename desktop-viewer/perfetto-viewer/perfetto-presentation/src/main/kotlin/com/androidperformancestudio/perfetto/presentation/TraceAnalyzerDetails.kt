@file:Suppress("FunctionNaming")

package com.androidperformancestudio.perfetto.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.perfetto_presentation.generated.resources.Res
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_artifact_id
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_bookmark
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_description
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_diagnostic_result
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_duration
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_empty_result
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_go
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_no_rows
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_query_id
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_read_only_query
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_rows_shown
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_running
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_schema
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_search_hint
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_search_hits
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_search_no_hits
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_tab_query
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_tab_result
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_tab_search
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_thread
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_timestamp
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioTokens

private const val TIMESTAMP_COLUMN_WEIGHT = 1.4f
private const val NAME_COLUMN_WEIGHT = 2.4f
private val ROW_ACTIONS_WIDTH = 116.dp

private fun searchColumnWeight(index: Int): Float =
    when (index) {
        0 -> TIMESTAMP_COLUMN_WEIGHT
        2 -> NAME_COLUMN_WEIGHT
        else -> 1f
    }

/** Diagnostic Result region: real rows, the read-only query, and slice search hits. */
@Composable
@Suppress("ktlint:standard:function-naming")
internal fun TraceDetailPanel(
    state: TraceAnalyzerWorkspaceState,
    actions: TraceAnalyzerWorkspaceActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    WorkspaceCard(
        title = localizedStringResource(Res.string.trace_workspace_diagnostic_result, language),
        modifier = modifier.testTag("trace-detail-panel"),
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TraceDetailTab.entries.forEach { tab ->
                    PerfettoCompactButton(
                        text = localizedStringResource(tab.label(), language),
                        onClick = { actions.onSelectDetailTab(tab) },
                        selected = state.detailTab == tab,
                        modifier = Modifier.testTag("trace-detail-tab-${tab.name.lowercase()}"),
                    )
                }
            }
        },
    ) {
        val viewerAvailable = state.activeTrace?.viewerAvailable == true
        when (state.detailTab) {
            TraceDetailTab.RESULT -> ResultTab(state.resultTable, viewerAvailable, actions, language)
            TraceDetailTab.QUERY -> QueryTab(state.resultTable, language)
            TraceDetailTab.SEARCH -> SearchTab(state, viewerAvailable, actions, language)
        }
    }
}

private fun TraceDetailTab.label() =
    when (this) {
        TraceDetailTab.RESULT -> Res.string.trace_workspace_tab_result
        TraceDetailTab.QUERY -> Res.string.trace_workspace_tab_query
        TraceDetailTab.SEARCH -> Res.string.trace_workspace_tab_search
    }

@Composable
@Suppress("ktlint:standard:function-naming")
private fun ResultTab(
    table: TraceResultTable?,
    viewerAvailable: Boolean,
    actions: TraceAnalyzerWorkspaceActions,
    language: UiLanguage,
) {
    if (table == null) {
        EmptyMessage(localizedStringResource(Res.string.trace_workspace_empty_result, language))
        return
    }
    Column(modifier = Modifier.fillMaxSize().padding(8.dp).testTag("trace-result-table")) {
        Text(
            text = "${table.title}  ·  ${localizedStringResource(Res.string.trace_workspace_rows_shown, language, table.rows.size)}",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = ViewerTypography.secondary.fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (table.rows.isEmpty()) {
            EmptyMessage(localizedStringResource(Res.string.trace_workspace_no_rows, language))
            return@Column
        }
        TableRow(cells = table.columns, header = true)
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(table.rows) { index, row ->
                val timestamp = table.rowTimestampsNanos[index]
                TableRow(
                    cells = row,
                    modifier = Modifier.testTag("trace-result-row-$index"),
                ) {
                    RowActions(
                        timestamp = timestamp,
                        viewerAvailable = viewerAvailable,
                        tagSuffix = "result-$index",
                        bookmark = timestamp?.let { TraceBookmarkRequest(table.title, it, table.queryId) },
                        actions = actions,
                        language = language,
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun QueryTab(
    table: TraceResultTable?,
    language: UiLanguage,
) {
    if (table == null) {
        EmptyMessage(localizedStringResource(Res.string.trace_workspace_empty_result, language))
        return
    }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(8.dp)
                .testTag("trace-query-tab"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EvidenceLine(localizedStringResource(Res.string.trace_workspace_query_id, language), table.queryId)
        EvidenceLine(localizedStringResource(Res.string.trace_workspace_artifact_id, language), table.artifactId)
        EvidenceLine(localizedStringResource(Res.string.trace_workspace_schema, language), table.schemaVersion)
        EvidenceLine(localizedStringResource(Res.string.trace_workspace_description, language), table.description)
        Text(
            text = localizedStringResource(Res.string.trace_workspace_read_only_query, language),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = ViewerTypography.label.fontSize,
        )
        SelectionContainer {
            Text(
                text = table.sql,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .horizontalScroll(rememberScrollState())
                        .padding(StudioTokens.compactContentPadding)
                        .testTag("trace-query-sql"),
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = ViewerTypography.label.fontSize,
            )
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun SearchTab(
    state: TraceAnalyzerWorkspaceState,
    viewerAvailable: Boolean,
    actions: TraceAnalyzerWorkspaceActions,
    language: UiLanguage,
) {
    val term = state.searchedTerm
    when {
        state.searchError != null ->
            EvidenceLine(
                localizedStringResource(Res.string.trace_workspace_tab_search, language),
                state.searchError,
                MaterialTheme.colorScheme.error,
            )
        state.searchRunning -> EmptyMessage(localizedStringResource(Res.string.trace_workspace_running, language))
        term == null -> EmptyMessage(localizedStringResource(Res.string.trace_workspace_search_hint, language))
        state.searchHits.isEmpty() -> EmptyMessage(localizedStringResource(Res.string.trace_workspace_search_no_hits, language, term))
        else ->
            Column(modifier = Modifier.fillMaxSize().padding(8.dp).testTag("trace-search-results")) {
                Text(
                    text = localizedStringResource(Res.string.trace_workspace_search_hits, language, state.searchHits.size, term),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = ViewerTypography.secondary.fontSize,
                    fontWeight = FontWeight.SemiBold,
                )
                TableRow(
                    cells =
                        listOf(
                            localizedStringResource(Res.string.trace_workspace_timestamp, language),
                            localizedStringResource(Res.string.trace_workspace_duration, language),
                            localizedStringResource(Res.string.trace_workspace_tab_search, language),
                            localizedStringResource(Res.string.trace_workspace_thread, language),
                        ),
                    header = true,
                    searchLayout = true,
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    itemsIndexed(state.searchHits) { index, hit ->
                        TableRow(
                            cells =
                                listOf(
                                    formatTraceTimestamp(hit.timestampNanos),
                                    formatTraceDurationNanos(hit.durationNanos),
                                    hit.name,
                                    hit.threadLabel,
                                ),
                            modifier = Modifier.testTag("trace-search-hit-$index"),
                            searchLayout = true,
                        ) {
                            RowActions(
                                timestamp = hit.timestampNanos,
                                viewerAvailable = viewerAvailable,
                                tagSuffix = "search-$index",
                                bookmark = TraceBookmarkRequest(hit.name, hit.timestampNanos),
                                actions = actions,
                                language = language,
                            )
                        }
                    }
                }
            }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun TableRow(
    cells: List<String>,
    modifier: Modifier = Modifier,
    header: Boolean = false,
    searchLayout: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (header) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { index, cell ->
            Text(
                text = cell,
                modifier = Modifier.weight(if (searchLayout) searchColumnWeight(index) else 1f),
                color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                fontSize = ViewerTypography.label.fontSize,
                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(modifier = Modifier.width(ROW_ACTIONS_WIDTH), contentAlignment = Alignment.CenterEnd) { trailing?.invoke() }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming", "LongParameterList")
private fun RowActions(
    timestamp: Long?,
    viewerAvailable: Boolean,
    tagSuffix: String,
    bookmark: TraceBookmarkRequest?,
    actions: TraceAnalyzerWorkspaceActions,
    language: UiLanguage,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PerfettoCompactButton(
            text = localizedStringResource(Res.string.trace_workspace_go, language),
            onClick = { timestamp?.let(actions.onOpenTimestamp) },
            enabled = timestamp != null && viewerAvailable,
            modifier = Modifier.testTag("open-timestamp-$tagSuffix"),
        )
        PerfettoCompactButton(
            text = localizedStringResource(Res.string.trace_workspace_bookmark, language),
            onClick = { bookmark?.let(actions.onAddBookmark) },
            enabled = bookmark != null,
            modifier = Modifier.testTag("add-bookmark-$tagSuffix"),
        )
    }
}
