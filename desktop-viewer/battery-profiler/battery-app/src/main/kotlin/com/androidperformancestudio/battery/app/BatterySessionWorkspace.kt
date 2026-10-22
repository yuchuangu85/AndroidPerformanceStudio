@file:Suppress("FunctionName", "MagicNumber")

package com.androidperformancestudio.battery.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.battery.battery_app.generated.resources.Res
import com.androidperformancestudio.battery.battery_app.generated.resources.refresh
import com.androidperformancestudio.battery.battery_app.generated.resources.sessions_completed
import com.androidperformancestudio.battery.battery_app.generated.resources.sessions_count
import com.androidperformancestudio.battery.battery_app.generated.resources.sessions_empty
import com.androidperformancestudio.battery.battery_app.generated.resources.sessions_interrupted
import com.androidperformancestudio.battery.battery_app.generated.resources.sessions_new
import com.androidperformancestudio.battery.battery_app.generated.resources.sessions_no_matches
import com.androidperformancestudio.battery.battery_app.generated.resources.sessions_run_status
import com.androidperformancestudio.battery.battery_app.generated.resources.sessions_running
import com.androidperformancestudio.battery.battery_app.generated.resources.sessions_search
import com.androidperformancestudio.battery.battery_app.generated.resources.sessions_title
import com.androidperformancestudio.battery.model.BatterySessionStatus
import com.androidperformancestudio.battery.presentation.BatteryProfilerState
import com.androidperformancestudio.battery.storage.StoredBatterySession
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioPanel
import com.androidperformancestudio.ui.studio.StudioPanelHeader
import com.androidperformancestudio.ui.studio.StudioTokens

@Composable
internal fun BatterySessionWorkspace(
    state: BatteryProfilerState,
    language: UiLanguage,
    onOpenSession: (String) -> Unit,
    onNewExperiment: () -> Unit,
    onRefreshSessions: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().testTag("battery-session-workspace")) {
        val sidebar: @Composable (Modifier) -> Unit = { sidebarModifier ->
            BatterySessionsPane(state, language, onOpenSession, onNewExperiment, onRefreshSessions, sidebarModifier)
        }
        if (maxWidth >= 1000.dp) {
            // The content page owns the 4dp separation; adding a row gap would double it.
            Row(Modifier.fillMaxSize()) {
                sidebar(
                    Modifier.width(230.dp).padding(
                        start = StudioTokens.workspacePadding,
                        top = StudioTokens.workspacePadding,
                        bottom = StudioTokens.workspacePadding,
                    ),
                )
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                // The next page's top inset supplies the 4dp gap below the sessions card.
                sidebar(
                    Modifier.fillMaxWidth().height(220.dp).padding(
                        start = StudioTokens.workspacePadding,
                        top = StudioTokens.workspacePadding,
                        end = StudioTokens.workspacePadding,
                    ),
                )
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}

internal fun filterBatterySessions(
    sessions: List<StoredBatterySession>,
    query: String,
): List<StoredBatterySession> {
    val needle = query.trim()
    return sessions.filter {
        needle.isEmpty() ||
            it.packageName.contains(needle, ignoreCase = true) ||
            it.id.contains(needle, ignoreCase = true) ||
            it.createdAt.contains(needle, ignoreCase = true)
    }
}

@Composable
private fun BatterySessionsPane(
    state: BatteryProfilerState,
    language: UiLanguage,
    onOpenSession: (String) -> Unit,
    onNewExperiment: () -> Unit,
    onRefreshSessions: () -> Unit,
    modifier: Modifier,
) {
    var query by remember { mutableStateOf("") }
    val enabled = !state.isRunning && !state.isLoadingSession
    val selectedId = state.experiment?.session?.id ?: state.viewedStoredSessionId
    StudioPanel(modifier.testTag("battery-sessions")) {
        StudioPanelHeader(localizedStringResource(Res.string.sessions_title, language)) {
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.sessions_new, language),
                enabled = enabled,
                onClick = onNewExperiment,
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(localizedStringResource(Res.string.sessions_search, language)) },
            modifier = Modifier.fillMaxWidth().testTag("battery-session-search"),
            singleLine = true,
            textStyle = ViewerTypography.bodyCompact,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                localizedStringResource(Res.string.sessions_count, language, state.sessions.size),
                style = ViewerTypography.secondary,
                color = LocalViewerColors.current.secondaryText,
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.refresh, language),
                onClick = onRefreshSessions,
                enabled = !state.isLoadingSession,
            )
        }
        state.sessionError?.let {
            Text(it, color = LocalViewerColors.current.error, style = ViewerTypography.secondary)
        }
        val sessions = filterBatterySessions(state.sessions, query)
        if (sessions.isEmpty()) {
            Text(
                localizedStringResource(
                    if (state.sessions.isEmpty()) Res.string.sessions_empty else Res.string.sessions_no_matches,
                    language,
                ),
                color = LocalViewerColors.current.mutedText,
                style = ViewerTypography.bodyCompact,
            )
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(sessions, key = { it.id }) { session ->
                BatterySessionEntry(session, selectedId == session.id, enabled, language, onOpenSession)
            }
        }
    }
}

@Composable
private fun BatterySessionEntry(
    session: StoredBatterySession,
    isSelected: Boolean,
    enabled: Boolean,
    language: UiLanguage,
    onOpenSession: (String) -> Unit,
) {
    val colors = LocalViewerColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (isSelected) colors.selectedRow else colors.sectionBackground)
            .clickable(enabled = enabled) { onOpenSession(session.id) }
            .semantics { selected = isSelected }
            .testTag("battery-session-${session.id}")
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            session.packageName,
            color = colors.primaryText,
            style = ViewerTypography.subsectionTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            session.createdAt,
            color = colors.secondaryText,
            style = ViewerTypography.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val status =
            when (session.status) {
                BatterySessionStatus.RUNNING -> Res.string.sessions_running
                BatterySessionStatus.COMPLETED -> Res.string.sessions_completed
                BatterySessionStatus.INTERRUPTED -> Res.string.sessions_interrupted
            }
        Text(
            localizedStringResource(
                Res.string.sessions_run_status,
                language,
                session.runCount,
                localizedStringResource(status, language),
            ),
            color =
                if (session.status == BatterySessionStatus.INTERRUPTED) {
                    colors.warning
                } else {
                    colors.secondaryText
                },
            style = ViewerTypography.secondary,
        )
    }
}
