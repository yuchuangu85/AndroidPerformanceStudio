@file:Suppress("FunctionNaming")

package com.androidperformancestudio.perfetto.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.perfetto_presentation.generated.resources.Res
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_active_artifact
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_aggregate
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_all_insights
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_artifact_id
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_capture
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_capture_unavailable
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_close_capture
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_completeness
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_delete
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_empty_insights
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_empty_sessions
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_error
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_export
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_filter_all
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_filter_critical
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_filter_info
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_filter_warning
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_insights
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_limitation
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_no_trace
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_no_trace_description
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_open
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_open_in_timeline
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_open_viewer
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_query_id
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_result
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_run_diagnostic
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_search_sessions
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_sessions
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_source_of_truth
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_summary_binder
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_summary_frames
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_summary_long_tasks
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_summary_rendering
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_unavailable
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_view_result
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_viewer
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioCard
import com.androidperformancestudio.ui.studio.StudioPanelHeader
import com.androidperformancestudio.ui.studio.StudioTokens

private const val WIDE_WORKSPACE_BREAKPOINT_DP = 1100

@Composable
@Suppress("LongParameterList", "ktlint:standard:function-naming")
fun TraceAnalyzerWorkspace(
    state: TraceAnalyzerWorkspaceState,
    actions: TraceAnalyzerWorkspaceActions,
    modifier: Modifier = Modifier,
    language: UiLanguage = UiLanguage.ENGLISH,
    captureContent: (@Composable () -> Unit)? = null,
) {
    val filteredSessions =
        remember(state.sessions, state.searchQuery) {
            filterTraceWorkspaceSessions(state.sessions, state.searchQuery)
        }
    val summaries = remember(state.insights) { buildTraceWorkspaceSummaries(state.insights) }
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .testTag("trace-analyzer-workspace"),
    ) {
        val isWide = maxWidth >= WIDE_WORKSPACE_BREAKPOINT_DP.dp
        if (isWide) {
            WideTraceWorkspace(
                state = state,
                actions = actions,
                filteredSessions = filteredSessions,
                summaries = summaries,
                language = language,
                captureContent = captureContent,
            )
        } else {
            NarrowTraceWorkspace(
                state = state,
                actions = actions,
                filteredSessions = filteredSessions,
                summaries = summaries,
                language = language,
                captureContent = captureContent,
            )
        }
    }
}

@Composable
@Suppress("LongParameterList", "ktlint:standard:function-naming")
private fun WideTraceWorkspace(
    state: TraceAnalyzerWorkspaceState,
    actions: TraceAnalyzerWorkspaceActions,
    filteredSessions: List<TraceWorkspaceSession>,
    summaries: List<TraceWorkspaceSummary>,
    language: UiLanguage,
    captureContent: (@Composable () -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(StudioTokens.compactContentPadding).testTag("trace-analyzer-wide"),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
    ) {
        TraceSummaryGrid(summaries, language, wide = true)
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
        ) {
            Column(
                modifier = Modifier.width(232.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
            ) {
                TraceSessionsPanel(
                    state = state,
                    sessions = filteredSessions,
                    actions = actions,
                    language = language,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                TraceBookmarksPanel(state, actions, language, Modifier.fillMaxWidth().height(180.dp))
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
            ) {
                TraceViewerOrCapturePanel(
                    state = state,
                    actions = actions,
                    language = language,
                    captureContent = captureContent,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                if (!state.captureVisible) {
                    TraceDetailPanel(state, actions, language, Modifier.fillMaxWidth().height(230.dp))
                }
            }
            TraceInsightsPanel(
                state = state,
                actions = actions,
                language = language,
                modifier = Modifier.width(312.dp).fillMaxHeight(),
            )
        }
        TraceWorkspaceStatus(state)
    }
}

@Composable
@Suppress("LongParameterList", "ktlint:standard:function-naming")
private fun NarrowTraceWorkspace(
    state: TraceAnalyzerWorkspaceState,
    actions: TraceAnalyzerWorkspaceActions,
    filteredSessions: List<TraceWorkspaceSession>,
    summaries: List<TraceWorkspaceSummary>,
    language: UiLanguage,
    captureContent: (@Composable () -> Unit)?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(StudioTokens.compactContentPadding)
                .testTag("trace-analyzer-narrow"),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
    ) {
        TraceSummaryGrid(summaries, language, wide = false)
        TraceSessionsPanel(
            state = state,
            sessions = filteredSessions,
            actions = actions,
            language = language,
            modifier = Modifier.fillMaxWidth().height(280.dp),
        )
        TraceBookmarksPanel(state, actions, language, Modifier.fillMaxWidth().height(180.dp))
        TraceViewerOrCapturePanel(
            state = state,
            actions = actions,
            language = language,
            captureContent = captureContent,
            modifier = Modifier.fillMaxWidth().height(360.dp),
        )
        if (!state.captureVisible) {
            TraceDetailPanel(state, actions, language, Modifier.fillMaxWidth().height(300.dp))
        }
        TraceInsightsPanel(
            state = state,
            actions = actions,
            language = language,
            modifier = Modifier.fillMaxWidth().height(420.dp),
        )
        TraceWorkspaceStatus(state)
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun TraceSummaryGrid(
    summaries: List<TraceWorkspaceSummary>,
    language: UiLanguage,
    wide: Boolean,
) {
    if (wide) {
        Row(
            modifier = Modifier.fillMaxWidth().testTag("trace-summary-grid"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            summaries.forEach { summary ->
                TraceSummaryCard(summary, language, Modifier.weight(1f))
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth().testTag("trace-summary-grid"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            summaries.chunked(2).forEach { rowSummaries ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowSummaries.forEach { summary ->
                        TraceSummaryCard(summary, language, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun TraceSummaryCard(
    summary: TraceWorkspaceSummary,
    language: UiLanguage,
    modifier: Modifier,
) {
    val colors = LocalViewerColors.current
    val accent = summary.severity?.color() ?: colors.secondaryText
    val label =
        when (summary.category) {
            TraceInsightCategory.FRAME -> localizedStringResource(Res.string.trace_workspace_summary_frames, language)
            TraceInsightCategory.BINDER -> localizedStringResource(Res.string.trace_workspace_summary_binder, language)
            TraceInsightCategory.LONG_TASK -> localizedStringResource(Res.string.trace_workspace_summary_long_tasks, language)
            TraceInsightCategory.RENDERING -> localizedStringResource(Res.string.trace_workspace_summary_rendering, language)
            else -> summary.category.name
        }
    val value = summary.value ?: localizedStringResource(Res.string.trace_workspace_unavailable, language)
    StudioCard(
        modifier =
            modifier
                .height(72.dp)
                .testTag("trace-summary-${summary.category.name.lowercase()}"),
        containerColor = if (summary.severity == null) colors.panel else accent.copy(alpha = 0.10f).compositeOver(colors.panel),
        borderColor = if (summary.severity == null) colors.border else accent.copy(alpha = 0.5f),
        contentPadding = 0.dp,
        contentSpacing = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(StudioTokens.compactContentPadding),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = ViewerTypography.secondary.fontSize,
                maxLines = 1,
            )
            Text(
                text = value,
                color = if (summary.value == null) MaterialTheme.colorScheme.onSurfaceVariant else accent,
                fontSize = ViewerTypography.bodyCompact.fontSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            summary.sourceTitle?.let { source ->
                Text(
                    text = source,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = ViewerTypography.label.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("trace-summary-source-${summary.category.name.lowercase()}"),
                )
            }
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun TraceSessionsPanel(
    state: TraceAnalyzerWorkspaceState,
    sessions: List<TraceWorkspaceSession>,
    actions: TraceAnalyzerWorkspaceActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    WorkspaceCard(
        title = localizedStringResource(Res.string.trace_workspace_sessions, language),
        modifier = modifier.testTag("trace-sessions-panel"),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PerfettoCompactTextField(
                value = state.searchQuery,
                onValueChange = actions.onSearchChange,
                placeholder = localizedStringResource(Res.string.trace_workspace_search_sessions, language),
                modifier = Modifier.fillMaxWidth().testTag("trace-session-search"),
            )
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .testTag("trace-session-list"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (sessions.isEmpty()) {
                    EmptyMessage(localizedStringResource(Res.string.trace_workspace_empty_sessions, language))
                }
                sessions.forEach { session ->
                    TraceSessionRow(
                        session = session,
                        selected =
                            session.sessionId == state.selectedSessionId ||
                                session.artifact?.artifactId == state.activeTrace?.artifactId,
                        actions = actions,
                        language = language,
                    )
                }
            }
            PerfettoCompactButton(
                text = localizedStringResource(Res.string.trace_workspace_capture, language),
                onClick = actions.onToggleCapture,
                selected = state.captureVisible,
                modifier = Modifier.fillMaxWidth().testTag("toggle-trace-capture"),
            )
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun TraceSessionRow(
    session: TraceWorkspaceSession,
    selected: Boolean,
    actions: TraceAnalyzerWorkspaceActions,
    language: UiLanguage,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    StudioCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { actions.onSelectSession(session.sessionId) }
                .testTag("trace-session-${session.sessionId}"),
        containerColor =
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        borderColor = borderColor,
        contentSpacing = 4.dp,
    ) {
        Text(
            text = session.title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = ViewerTypography.secondary.fontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${session.capturedAtLabel}  ·  ${session.durationLabel}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = ViewerTypography.label.fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PerfettoCompactButton(
                text = localizedStringResource(Res.string.trace_workspace_open, language),
                onClick = { actions.onOpenSession(session.sessionId) },
                modifier = Modifier.testTag("open-trace-session-${session.sessionId}"),
            )
            PerfettoCompactButton(
                text = localizedStringResource(Res.string.trace_workspace_delete, language),
                onClick = { actions.onDeleteSession(session.sessionId) },
                modifier = Modifier.testTag("delete-trace-session-${session.sessionId}"),
            )
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun TraceViewerOrCapturePanel(
    state: TraceAnalyzerWorkspaceState,
    actions: TraceAnalyzerWorkspaceActions,
    language: UiLanguage,
    captureContent: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (state.captureVisible) {
        WorkspaceCard(
            title = localizedStringResource(Res.string.trace_workspace_capture, language),
            modifier = modifier.testTag("trace-capture-panel"),
            action = {
                PerfettoCompactButton(
                    text = localizedStringResource(Res.string.trace_workspace_close_capture, language),
                    onClick = actions.onToggleCapture,
                    modifier = Modifier.testTag("close-trace-capture"),
                )
            },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (captureContent != null) {
                    captureContent()
                } else {
                    EmptyMessage(
                        text = localizedStringResource(Res.string.trace_workspace_capture_unavailable, language),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
            }
        }
    } else {
        TraceViewerPanel(state, actions, language, modifier)
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun TraceViewerPanel(
    state: TraceAnalyzerWorkspaceState,
    actions: TraceAnalyzerWorkspaceActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val artifact = state.activeTrace
    WorkspaceCard(
        title = localizedStringResource(Res.string.trace_workspace_viewer, language),
        modifier = modifier.testTag("trace-viewer-panel"),
    ) {
        if (artifact == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                PerfettoMark()
                Spacer(Modifier.height(12.dp))
                Text(
                    text = localizedStringResource(Res.string.trace_workspace_no_trace, language),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = MaterialTheme.typography.titleMedium.fontSize,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = localizedStringResource(Res.string.trace_workspace_no_trace_description, language),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = ViewerTypography.secondary.fontSize,
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(StudioTokens.compactContentPadding),
                verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
            ) {
                Text(
                    text = artifact.displayName,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = MaterialTheme.typography.titleLarge.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("active-trace-name"),
                )
                TraceEvidenceAnchors(state, actions, language)
                EvidenceLine(
                    localizedStringResource(Res.string.trace_workspace_artifact_id, language),
                    artifact.artifactId,
                )
                EvidenceLine(
                    localizedStringResource(Res.string.trace_workspace_active_artifact, language),
                    artifact.locationLabel,
                )
                EvidenceLine(
                    localizedStringResource(Res.string.trace_workspace_completeness, language),
                    artifact.completenessLabel,
                )
                if (artifact.limitations.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    artifact.limitations.forEach { limitation ->
                        EvidenceLine(
                            localizedStringResource(Res.string.trace_workspace_limitation, language),
                            limitation,
                            MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                StudioCard(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("perfetto-source-of-truth"),
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    contentSpacing = 4.dp,
                ) {
                    Text(
                        text = localizedStringResource(Res.string.trace_workspace_open_viewer, language),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = ViewerTypography.bodyCompact.fontSize,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = localizedStringResource(Res.string.trace_workspace_source_of_truth, language),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = ViewerTypography.secondary.fontSize,
                        lineHeight = ViewerTypography.secondary.lineHeight,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PerfettoCompactButton(
                        text = localizedStringResource(Res.string.trace_workspace_open_viewer, language),
                        onClick = { actions.onOpenViewer(artifact) },
                        enabled = artifact.viewerAvailable,
                        selected = artifact.viewerAvailable,
                        modifier = Modifier.testTag("open-perfetto-viewer"),
                    )
                    PerfettoCompactButton(
                        text = localizedStringResource(Res.string.trace_workspace_export, language),
                        onClick = { actions.onExport(artifact) },
                        modifier = Modifier.testTag("export-trace-artifact"),
                    )
                    PerfettoCompactButton(
                        text = localizedStringResource(Res.string.trace_workspace_capture, language),
                        onClick = actions.onToggleCapture,
                        modifier = Modifier.testTag("show-trace-capture"),
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun TraceInsightsPanel(
    state: TraceAnalyzerWorkspaceState,
    actions: TraceAnalyzerWorkspaceActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    WorkspaceCard(
        title = localizedStringResource(Res.string.trace_workspace_insights, language),
        modifier = modifier.testTag("trace-insights-panel"),
        action = {
            Text(
                text = localizedStringResource(Res.string.trace_workspace_all_insights, language, state.insights.size),
                color = MaterialTheme.colorScheme.primary,
                fontSize = ViewerTypography.label.fontSize,
            )
        },
    ) {
        val presetInsights = visibleTraceInsights(state.insights, state.presets, state.selectedPresetId, TraceInsightFilter.ALL)
        val counts = traceInsightCounts(presetInsights)
        val visible = visibleTraceInsights(state.insights, state.presets, state.selectedPresetId, state.insightFilter)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("trace-insight-filters"),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TraceInsightFilter.entries.forEach { filter ->
                    PerfettoCompactButton(
                        text = localizedStringResource(filter.label(), language, counts.getValue(filter)),
                        onClick = { actions.onInsightFilterChange(filter) },
                        selected = state.insightFilter == filter,
                        modifier = Modifier.testTag("trace-insight-filter-${filter.name.lowercase()}"),
                    )
                }
            }
            if (visible.isEmpty()) {
                EmptyMessage(localizedStringResource(Res.string.trace_workspace_empty_insights, language))
            }
            visible.forEach { insight ->
                TraceInsightCard(
                    insight = insight,
                    activeTrace = state.activeTrace,
                    selected = insight.queryId == state.selectedQueryId,
                    actions = actions,
                    language = language,
                )
            }
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun TraceInsightCard(
    insight: TraceInsightEvidence,
    activeTrace: TraceWorkspaceArtifact?,
    selected: Boolean,
    actions: TraceAnalyzerWorkspaceActions,
    language: UiLanguage,
) {
    val accent = insight.severity.color()
    StudioCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("trace-insight-${insight.queryId}"),
        containerColor =
            if (selected) {
                accent.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        borderColor = if (selected) accent else MaterialTheme.colorScheme.outlineVariant,
        contentSpacing = 5.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            PerfettoStatusDot(accent)
            Text(
                text = insight.title,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = ViewerTypography.secondary.fontSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        insight.aggregate?.takeIf(String::isNotBlank)?.let { aggregate ->
            EvidenceLine(localizedStringResource(Res.string.trace_workspace_aggregate, language), aggregate, accent)
        }
        EvidenceLine(localizedStringResource(Res.string.trace_workspace_query_id, language), insight.queryId)
        EvidenceLine(localizedStringResource(Res.string.trace_workspace_artifact_id, language), insight.artifactId)
        insight.resultRowsSummary?.takeIf(String::isNotBlank)?.let { summary ->
            EvidenceLine(localizedStringResource(Res.string.trace_workspace_result, language), summary, accent)
        }
        insight.limitations.forEach { limitation ->
            EvidenceLine(
                localizedStringResource(Res.string.trace_workspace_limitation, language),
                limitation,
                MaterialTheme.colorScheme.tertiary,
            )
        }
        insight.error?.let { error ->
            EvidenceLine(
                localizedStringResource(Res.string.trace_workspace_error, language),
                error,
                MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PerfettoCompactButton(
                text = localizedStringResource(Res.string.trace_workspace_run_diagnostic, language),
                onClick = { actions.onRunDiagnostic(insight.queryId) },
                selected = selected,
                modifier = Modifier.testTag("run-trace-diagnostic-${insight.queryId}"),
            )
            PerfettoCompactButton(
                text = localizedStringResource(Res.string.trace_workspace_open_in_timeline, language),
                onClick = { actions.onOpenInTimeline(insight) },
                enabled = canOpenInsightInTimeline(insight, activeTrace),
                modifier = Modifier.testTag("open-insight-timeline-${insight.queryId}"),
            )
            PerfettoCompactButton(
                text = localizedStringResource(Res.string.trace_workspace_view_result, language),
                onClick = { actions.onViewInsightResult(insight.queryId) },
                enabled = insight.error == null && insight.resultRowsSummary != null,
                modifier = Modifier.testTag("view-insight-result-${insight.queryId}"),
            )
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
internal fun WorkspaceCard(
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    StudioCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant,
        contentPadding = 0.dp,
        contentSpacing = 0.dp,
    ) {
        StudioPanelHeader(
            title = title,
            modifier = Modifier.padding(horizontal = StudioTokens.compactContentPadding),
            actions = { action() },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
internal fun EvidenceLine(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "$label:",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = ViewerTypography.label.fontSize,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            color = valueColor,
            fontSize = ViewerTypography.label.fontSize,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
internal fun EmptyMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.padding(8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = ViewerTypography.secondary.fontSize,
        lineHeight = ViewerTypography.secondary.lineHeight,
    )
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun PerfettoMark() {
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "PF",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = MaterialTheme.typography.titleMedium.fontSize,
        )
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun TraceWorkspaceStatus(state: TraceAnalyzerWorkspaceState) {
    val message = state.error ?: state.status ?: return
    Text(
        text = message,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 5.dp)
                .testTag(if (state.error != null) "trace-workspace-error" else "trace-workspace-status"),
        color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = ViewerTypography.label.fontSize,
    )
}

private fun TraceInsightFilter.label() =
    when (this) {
        TraceInsightFilter.ALL -> Res.string.trace_workspace_filter_all
        TraceInsightFilter.CRITICAL -> Res.string.trace_workspace_filter_critical
        TraceInsightFilter.WARNING -> Res.string.trace_workspace_filter_warning
        TraceInsightFilter.INFO -> Res.string.trace_workspace_filter_info
    }

@Composable
private fun TraceInsightSeverity.color(): Color =
    when (this) {
        TraceInsightSeverity.CRITICAL -> MaterialTheme.colorScheme.error
        TraceInsightSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
        TraceInsightSeverity.INFO -> MaterialTheme.colorScheme.primary
        TraceInsightSeverity.OPPORTUNITY -> MaterialTheme.colorScheme.secondary
    }
