@file:Suppress("FunctionNaming", "LongMethod", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.model.LeakCanaryLiveEvent
import com.androidperformancestudio.memory.model.LeakCanaryLiveSession
import com.androidperformancestudio.memory.model.LeakCanaryLiveStatus
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.leak_live_connecting
import com.androidperformancestudio.memory.presentation.generated.resources.leak_live_disconnected
import com.androidperformancestudio.memory.presentation.generated.resources.leak_live_evidence
import com.androidperformancestudio.memory.presentation.generated.resources.leak_live_failed
import com.androidperformancestudio.memory.presentation.generated.resources.leak_live_monitoring
import com.androidperformancestudio.memory.presentation.generated.resources.leak_live_running
import com.androidperformancestudio.memory.presentation.generated.resources.leak_live_start
import com.androidperformancestudio.memory.presentation.generated.resources.leak_live_stop
import com.androidperformancestudio.memory.presentation.generated.resources.leak_live_stopped
import com.androidperformancestudio.memory.presentation.generated.resources.leak_live_stopping
import com.androidperformancestudio.memory.presentation.generated.resources.live_event_summary
import com.androidperformancestudio.memory.presentation.generated.resources.no_live_events
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource

private const val MAX_VISIBLE_LIVE_EVENTS = 12

@Composable
internal fun MemoryLeakLiveControls(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
) {
    val session = state.leakCanaryLiveSession
    val status = session.status
    val active = status == LeakCanaryLiveStatus.CONNECTING || status == LeakCanaryLiveStatus.RUNNING
    val targetAvailable =
        state.selectedDeviceSerial != null &&
            state.selectedProcessId != null &&
            state.devices.none { it.serial == state.selectedDeviceSerial && !it.online }
    val colors = LocalViewerColors.current
    Column(Modifier.fillMaxWidth().background(colors.panel).testTag("memory-leak-live-controls")) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                localizedStringResource(Res.string.leak_live_monitoring, language),
                Modifier.weight(1f),
                style = ViewerTypography.bodyCompact,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LiveAgentStatus(status, language)
            ProfilerCompactButton(
                localizedStringResource(Res.string.leak_live_start, language),
                actions.onStartLiveLeakMonitoring,
                enabled = targetAvailable && !active && status != LeakCanaryLiveStatus.STOPPING,
                modifier = Modifier.testTag("memory-leak-live-start"),
            )
            ProfilerCompactButton(
                localizedStringResource(Res.string.leak_live_stop, language),
                actions.onStopLiveLeakMonitoring,
                enabled = active,
                modifier = Modifier.testTag("memory-leak-live-stop"),
            )
        }
        if (status != LeakCanaryLiveStatus.DISCONNECTED || session.events.isNotEmpty()) {
            LiveAgentEvents(session, language)
        }
        HorizontalDivider(color = colors.border)
    }
}

@Composable
private fun LiveAgentStatus(
    status: LeakCanaryLiveStatus,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    val label =
        when (status) {
            LeakCanaryLiveStatus.DISCONNECTED -> Res.string.leak_live_disconnected
            LeakCanaryLiveStatus.CONNECTING -> Res.string.leak_live_connecting
            LeakCanaryLiveStatus.RUNNING -> Res.string.leak_live_running
            LeakCanaryLiveStatus.STOPPING -> Res.string.leak_live_stopping
            LeakCanaryLiveStatus.STOPPED -> Res.string.leak_live_stopped
            LeakCanaryLiveStatus.FAILED -> Res.string.leak_live_failed
        }
    Text(
        localizedStringResource(label, language),
        style = ViewerTypography.secondary,
        color =
            when (status) {
                LeakCanaryLiveStatus.RUNNING -> colors.success
                LeakCanaryLiveStatus.FAILED -> colors.error
                else -> colors.secondaryText
            },
    )
}

@Composable
private fun LiveAgentEvents(
    session: LeakCanaryLiveSession,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    Text(
        localizedStringResource(Res.string.leak_live_evidence, language),
        Modifier.padding(horizontal = 8.dp).testTag("memory-leak-live-evidence"),
        style = ViewerTypography.label,
        color = colors.secondaryText,
    )
    session.packageName?.let {
        Text(
            it,
            Modifier.padding(horizontal = 8.dp),
            style = ViewerTypography.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    session.message?.takeIf { it.isNotBlank() }?.let {
        Text(
            it,
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = ViewerTypography.secondary,
            color = if (session.status == LeakCanaryLiveStatus.FAILED) colors.error else colors.secondaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (session.events.isEmpty()) {
        Text(
            localizedStringResource(Res.string.no_live_events, language),
            Modifier.padding(8.dp),
            style = ViewerTypography.secondary,
            color = colors.secondaryText,
        )
    } else {
        Text(
            localizedStringResource(
                Res.string.live_event_summary,
                language,
                session.events.size,
                session.droppedEvents,
            ),
            Modifier.padding(horizontal = 8.dp),
            style = ViewerTypography.label,
            color = colors.secondaryText,
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 80.dp).testTag("memory-profiler-live-events")) {
            items(session.events.takeLast(MAX_VISIBLE_LIVE_EVENTS).asReversed()) { event ->
                LiveAgentEventRow(event)
            }
        }
    }
}

@Composable
private fun LiveAgentEventRow(event: LeakCanaryLiveEvent) {
    val colors = LocalViewerColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(
            event.kind,
            Modifier.width(150.dp),
            style = ViewerTypography.label,
            color = if (event.retained == true) colors.warning else colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            event.className ?: event.description.orEmpty(),
            Modifier.weight(1f),
            style = ViewerTypography.secondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
