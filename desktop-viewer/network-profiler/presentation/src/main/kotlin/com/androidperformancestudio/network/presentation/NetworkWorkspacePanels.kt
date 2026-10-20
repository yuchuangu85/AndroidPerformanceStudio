@file:Suppress("FunctionName", "LongMethod", "MagicNumber", "LongParameterList")

package com.androidperformancestudio.network.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.network.model.CallOutcome
import com.androidperformancestudio.network.model.HttpCall
import com.androidperformancestudio.network.model.NetworkEvidenceSource
import com.androidperformancestudio.network.presentation.generated.resources.*
import com.androidperformancestudio.network.presentation.generated.resources.Res
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioDataTable
import com.androidperformancestudio.ui.studio.StudioPanel
import com.androidperformancestudio.ui.studio.StudioPanelHeader
import com.androidperformancestudio.ui.studio.StudioSearchField
import com.androidperformancestudio.ui.studio.StudioTableColumn

@Composable
internal fun NetworkRequestControls(
    query: String,
    onQueryChange: (String) -> Unit,
    outcome: CallOutcome?,
    onOutcomeChange: (CallOutcome?) -> Unit,
    showDomains: Boolean,
    onShowDomains: (Boolean) -> Unit,
    visibleCount: Int,
    totalCount: Int,
    language: UiLanguage,
    narrow: Boolean,
) {
    val tabs: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { onShowDomains(false) }, modifier = Modifier.testTag("network-requests-tab")) {
                Text(localizedStringResource(Res.string.request_list, language), color = if (!showDomains) LocalViewerColors.current.accent else LocalViewerColors.current.secondaryText)
            }
            TextButton(onClick = { onShowDomains(true) }, modifier = Modifier.testTag("network-domains-tab")) {
                Text(localizedStringResource(Res.string.network_domains, language), color = if (showDomains) LocalViewerColors.current.accent else LocalViewerColors.current.secondaryText)
            }
            var expanded by remember { mutableStateOf(false) }
            Column {
                TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("network-outcome-filter")) {
                    Text(outcome?.displayName(language) ?: localizedStringResource(Res.string.network_all_outcomes, language))
                }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text(localizedStringResource(Res.string.network_all_outcomes, language)) },
                        onClick = {
                            onOutcomeChange(null)
                            expanded = false
                        },
                    )
                    CallOutcome.entries.forEach { value ->
                        DropdownMenuItem(
                            text = { Text(value.displayName(language)) },
                            onClick = {
                                onOutcomeChange(value)
                                expanded = false
                            },
                        )
                    }
                }
            }
            Text("$visibleCount / $totalCount", style = ViewerTypography.secondary, color = LocalViewerColors.current.secondaryText)
        }
    }
    val search: @Composable (Modifier) -> Unit = { modifier ->
        StudioSearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = localizedStringResource(Res.string.network_search, language),
            modifier = modifier.testTag("network-request-search"),
            compact = true,
        )
    }
    if (narrow) {
        Column(Modifier.fillMaxWidth()) {
            tabs()
            search(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs()
            search(Modifier.weight(1f))
        }
    }
}

@Composable
internal fun NetworkActivityPanel(calls: List<HttpCall>, language: UiLanguage) {
    val activity = remember(calls) { networkActivity(calls) }
    val colors = LocalViewerColors.current
    val notice = localizedStringResource(Res.string.network_activity_notice, language)
    StudioPanel(Modifier.fillMaxWidth().testTag("network-activity-panel")) {
        StudioPanelHeader(localizedStringResource(Res.string.network_activity, language))
        if (activity == null) {
            Text(localizedStringResource(Res.string.network_activity_unavailable, language), color = colors.secondaryText, style = ViewerTypography.secondary)
        } else {
            val measuredPeak = activity.completionBytes.filterNotNull().maxOrNull()
            val peak = measuredPeak?.coerceAtLeast(1) ?: 1
            Canvas(
                Modifier.fillMaxWidth().height(78.dp).testTag("network-activity-chart")
                    .semantics { contentDescription = "$notice · ${activity.timedCalls.size} / ${calls.size}" },
            ) {
                val mid = size.height * 0.5f
                repeat(5) { index ->
                    val x = size.width * index / 4
                    drawLine(colors.border, Offset(x, 0f), Offset(x, size.height))
                }
                drawLine(colors.border, Offset(0f, mid), Offset(size.width, mid))
                // Each call retains its real interval. Dense evidence shares visual lanes; no invented timestamps.
                activity.timedCalls.forEachIndexed { index, call ->
                    val start = ((call.startedNs - activity.originNs).toDouble() / activity.durationNs).toFloat() * size.width
                    val end = ((requireNotNull(call.endedNs) - activity.originNs).toDouble() / activity.durationNs).toFloat() * size.width
                    val y = 5.dp.toPx() + (index % 5) * (mid - 10.dp.toPx()) / 5
                    drawLine(
                        color = if (call.outcome == CallOutcome.FAILED) colors.error else colors.info,
                        start = Offset(start, y),
                        end = Offset(end.coerceAtLeast(start + 1f), y),
                        strokeWidth = 3.dp.toPx(),
                        pathEffect = if (call.source == NetworkEvidenceSource.HAR_IMPORT) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 3.dp.toPx())) else null,
                    )
                }
                val width = size.width / activity.completionBytes.size
                activity.completionBytes.forEachIndexed { index, bytes ->
                    if (bytes != null) {
                        val height = (bytes.toDouble() / peak).toFloat() * (size.height - mid - 3.dp.toPx())
                        drawRect(colors.accent.copy(alpha = 0.7f), Offset(index * width + 1f, size.height - height), Size((width - 2f).coerceAtLeast(1f), height.coerceAtLeast(1f)))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0 ms", style = ViewerTypography.label, color = colors.secondaryText)
                Text(localizedStringResource(Res.string.network_completion_peak, language, formatBytes(measuredPeak)), style = ViewerTypography.label, color = colors.accent)
                Text(formatDurationNs(activity.durationNs), style = ViewerTypography.label, color = colors.secondaryText)
            }
            if (activity.incompleteByteCalls > 0) {
                Text(
                    localizedStringResource(Res.string.network_partial_bytes, language, activity.incompleteByteCalls),
                    color = colors.warning,
                    style = ViewerTypography.label,
                )
            }
            if (activity.omittedCalls > 0) {
                Text(localizedStringResource(Res.string.network_activity_omitted, language, activity.omittedCalls), color = colors.warning, style = ViewerTypography.label)
            }
        }
        Text(notice, style = ViewerTypography.label, color = colors.secondaryText)
    }
}

@Composable
internal fun NetworkDomainsPanel(calls: List<HttpCall>, language: UiLanguage, modifier: Modifier) {
    val domains = remember(calls) { networkDomains(calls) }
    StudioDataTable(
        columns = listOf(
            StudioTableColumn(localizedStringResource(Res.string.network_domain, language), 2f),
            StudioTableColumn(localizedStringResource(Res.string.calls, language)),
            StudioTableColumn(localizedStringResource(Res.string.failures, language)),
            StudioTableColumn(localizedStringResource(Res.string.transferred_bytes, language)),
        ),
        title = localizedStringResource(Res.string.network_domains, language),
        modifier = modifier.testTag("network-domains-table"),
    ) {
        if (domains.isEmpty()) {
            Text(localizedStringResource(Res.string.no_requests, language), style = ViewerTypography.secondary, color = LocalViewerColors.current.secondaryText)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(domains, key = { it.domain.orEmpty() }) { domain ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        domain.domain ?: localizedStringResource(Res.string.unavailable, language),
                        domain.calls.size.toString(),
                        domain.failures.toString(),
                        formatBytes(domain.transferredBytes),
                    ).forEachIndexed { index, value ->
                        Text(value, Modifier.weight(if (index == 0) 2f else 1f), color = LocalViewerColors.current.rowText, style = ViewerTypography.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
