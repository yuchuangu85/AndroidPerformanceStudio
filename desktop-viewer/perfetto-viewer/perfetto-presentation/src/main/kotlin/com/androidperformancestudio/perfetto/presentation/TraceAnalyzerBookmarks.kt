@file:Suppress("FunctionNaming")

package com.androidperformancestudio.perfetto.presentation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.perfetto_presentation.generated.resources.Res
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_anchors
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_bookmarks
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_delete
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_empty_bookmarks
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_go
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_workspace_no_anchors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioCard
import com.androidperformancestudio.ui.studio.StudioTokens

@Composable
@Suppress("ktlint:standard:function-naming")
internal fun TraceBookmarksPanel(
    state: TraceAnalyzerWorkspaceState,
    actions: TraceAnalyzerWorkspaceActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val viewerAvailable = state.activeTrace?.viewerAvailable == true
    WorkspaceCard(
        title = localizedStringResource(Res.string.trace_workspace_bookmarks, language),
        modifier = modifier.testTag("trace-bookmarks-panel"),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(StudioTokens.workspacePadding),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
        ) {
            if (state.bookmarks.isEmpty()) {
                EmptyMessage(localizedStringResource(Res.string.trace_workspace_empty_bookmarks, language))
            }
            state.bookmarks.forEach { bookmark ->
                StudioCard(
                    modifier = Modifier.fillMaxWidth().testTag("trace-bookmark-${bookmark.bookmarkId}"),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    contentSpacing = 3.dp,
                ) {
                    Text(
                        text = bookmark.label,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = ViewerTypography.secondary.fontSize,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = formatTraceTimestamp(bookmark.timestampNanos),
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = ViewerTypography.label.fontSize,
                            maxLines = 1,
                        )
                        PerfettoCompactButton(
                            text = localizedStringResource(Res.string.trace_workspace_go, language),
                            onClick = { actions.onOpenTimestamp(bookmark.timestampNanos) },
                            enabled = viewerAvailable,
                            modifier = Modifier.testTag("open-bookmark-${bookmark.bookmarkId}"),
                        )
                        PerfettoCompactButton(
                            text = localizedStringResource(Res.string.trace_workspace_delete, language),
                            onClick = { actions.onDeleteBookmark(bookmark.bookmarkId) },
                            modifier = Modifier.testTag("delete-bookmark-${bookmark.bookmarkId}"),
                        )
                    }
                }
            }
        }
    }
}

/** Locatable insight and bookmark timestamps as jump targets into Perfetto; no track rendering. */
@Composable
@Suppress("ktlint:standard:function-naming")
internal fun TraceEvidenceAnchors(
    state: TraceAnalyzerWorkspaceState,
    actions: TraceAnalyzerWorkspaceActions,
    language: UiLanguage,
) {
    val anchors = traceTimelineAnchors(state.insights, state.activeTrace)
    Column(modifier = Modifier.fillMaxWidth().testTag("trace-evidence-anchors"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = localizedStringResource(Res.string.trace_workspace_anchors, language),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = ViewerTypography.secondary.fontSize,
            fontWeight = FontWeight.SemiBold,
        )
        if (anchors.isEmpty()) {
            Text(
                text = localizedStringResource(Res.string.trace_workspace_no_anchors, language),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = ViewerTypography.label.fontSize,
                lineHeight = ViewerTypography.label.lineHeight,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                anchors.forEach { insight ->
                    PerfettoCompactButton(
                        text = "${formatTraceTimestamp(insight.timestampNanos ?: 0L)} · ${insight.title}",
                        onClick = { actions.onOpenInTimeline(insight) },
                        modifier = Modifier.testTag("trace-anchor-${insight.queryId}"),
                    )
                }
            }
        }
    }
}
