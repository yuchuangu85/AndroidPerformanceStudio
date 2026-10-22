@file:Suppress(
    "FunctionName",
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "TooManyFunctions",
    "CyclomaticComplexMethod",
)

package com.androidperformancestudio.battery.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.battery.battery_app.generated.resources.Res
import com.androidperformancestudio.battery.battery_app.generated.resources.alarms
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_active
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_aggregate_network
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_app_state
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_artifact_total
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_bt_rx
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_bt_tx
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_charging
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_confidence
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_count
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_counter_note
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_device_scope
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_duration
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_elapsed
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_empty
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_energy
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_events
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_exact
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_history
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_history_clock
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_inactive
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_inferred
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_inspector
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_kind
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_level
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_mobile_rx
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_mobile_tx
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_modeled
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_name
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_no
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_no_level
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_no_snapshots
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_package_scope
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_packets
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_powered
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_radio
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_raw
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_records
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_sample
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_samples
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_scope
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_screen
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_search
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_select
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_shared_uid_scope
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_snapshot_clock
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_snapshots
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_source
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_state
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_temperature
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_thermal
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_timer_note
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_total_bytes
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_track_note
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_uid
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_uid_delta
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_uid_scope
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_unavailable
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_unknown
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_value
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_voltage
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_wifi_rx
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_wifi_tx
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_windows
import com.androidperformancestudio.battery.battery_app.generated.resources.bw_yes
import com.androidperformancestudio.battery.battery_app.generated.resources.diagnostics_and_warnings
import com.androidperformancestudio.battery.battery_app.generated.resources.energy
import com.androidperformancestudio.battery.battery_app.generated.resources.jobs
import com.androidperformancestudio.battery.battery_app.generated.resources.network
import com.androidperformancestudio.battery.battery_app.generated.resources.run_resource_details
import com.androidperformancestudio.battery.battery_app.generated.resources.sensors
import com.androidperformancestudio.battery.battery_app.generated.resources.sustained_performance_window
import com.androidperformancestudio.battery.battery_app.generated.resources.thermal_evidence_detail
import com.androidperformancestudio.battery.battery_app.generated.resources.wakelocks
import com.androidperformancestudio.battery.model.AttributionScope
import com.androidperformancestudio.battery.model.BatteryHistoryEvent
import com.androidperformancestudio.battery.model.BatteryHistoryEventKind
import com.androidperformancestudio.battery.model.BatteryRun
import com.androidperformancestudio.battery.model.BatteryRunDelta
import com.androidperformancestudio.battery.model.BatterySnapshot
import com.androidperformancestudio.battery.model.EvidenceConfidence
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioDataTable
import com.androidperformancestudio.ui.studio.StudioPanel
import com.androidperformancestudio.ui.studio.StudioPanelHeader
import com.androidperformancestudio.ui.studio.StudioSearchField
import com.androidperformancestudio.ui.studio.StudioTableColumn
import com.androidperformancestudio.ui.studio.StudioTokens
import org.jetbrains.compose.resources.StringResource
import java.time.Duration
import java.util.Locale
import kotlin.math.abs

@Composable
internal fun BatteryRunWorkspace(
    run: BatteryRunDelta,
    capturedRun: BatteryRun?,
    language: UiLanguage,
) {
    var tab by remember(run.runId) { mutableStateOf(BatteryResourceTab.WAKELOCKS) }
    var query by remember(run.runId) { mutableStateOf("") }
    var selectedId by remember(run.runId) { mutableStateOf<String?>(null) }
    val items = batteryWorkspaceItems(run, tab)
    val names = items.associate { it.id to itemName(it, language) }
    val visible = items.filter { it.matches(query, names.getValue(it.id)) }
    val selected = visible.firstOrNull { it.id == selectedId } ?: visible.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 860.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
                    BatterySnapshotChart(capturedRun, language, Modifier.weight(1f))
                    BatteryHistoryChart(run.history, language, Modifier.weight(1f)) { index ->
                        tab = BatteryResourceTab.HISTORY
                        selectedId = "history-$index"
                        query = ""
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
                    BatterySnapshotChart(capturedRun, language, Modifier.fillMaxWidth())
                    BatteryHistoryChart(run.history, language, Modifier.fillMaxWidth()) { index ->
                        tab = BatteryResourceTab.HISTORY
                        selectedId = "history-$index"
                        query = ""
                    }
                }
            }
        }
        StudioPanel(Modifier.fillMaxWidth()) {
            StudioPanelHeader(localizedStringResource(Res.string.run_resource_details, language, run.iteration))
            FlowRow(Modifier.fillMaxWidth()) {
                BatteryResourceTab.entries.forEach { candidate ->
                    val colors = LocalViewerColors.current
                    TextButton(
                        onClick = {
                            tab = candidate
                            query = ""
                            selectedId = null
                        },
                        modifier =
                            Modifier
                                .testTag("battery-tab-${candidate.name.lowercase()}")
                                .background(if (tab == candidate) colors.selectedRow else colors.transparent),
                    ) {
                        Text(
                            tabName(candidate, language),
                            color = if (tab == candidate) colors.accent else colors.secondaryText,
                        )
                    }
                }
            }
            StudioSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = localizedStringResource(Res.string.bw_search, language),
                modifier = Modifier.fillMaxWidth().testTag("battery-evidence-search"),
            )
            Text(
                localizedStringResource(Res.string.bw_records, language, visible.size, items.size),
                style = MaterialTheme.typography.labelSmall,
                color = LocalViewerColors.current.secondaryText,
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 860.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
                        BatteryEvidenceTable(
                            visible,
                            names,
                            selected?.id,
                            language,
                            Modifier.weight(1f),
                        ) { selectedId = it }
                        BatteryEvidenceInspector(selected, language, Modifier.width(290.dp))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
                        BatteryEvidenceTable(
                            visible,
                            names,
                            selected?.id,
                            language,
                            Modifier.fillMaxWidth(),
                        ) { selectedId = it }
                        BatteryEvidenceInspector(selected, language, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        BatteryThermalSummary(run, language)
    }
}

@Composable
private fun BatterySnapshotChart(
    run: BatteryRun?,
    language: UiLanguage,
    modifier: Modifier,
) {
    val samples = batterySnapshotSamples(run)
    var selectedId by remember(run?.id) { mutableStateOf(samples.lastOrNull()?.id) }
    val selected = samples.firstOrNull { it.id == selectedId } ?: samples.lastOrNull()
    val colors = LocalViewerColors.current
    val unavailable = localizedStringResource(Res.string.bw_unavailable, language)
    StudioPanel(modifier.testTag("battery-snapshot-panel")) {
        StudioPanelHeader(
            localizedStringResource(Res.string.bw_snapshots, language),
            subtitle = localizedStringResource(Res.string.bw_snapshot_clock, language),
        )
        if (samples.isEmpty()) {
            Text(localizedStringResource(Res.string.bw_no_snapshots, language), Modifier.heightIn(min = 110.dp))
        } else {
            val start = samples.first().capturedAt
            val span =
                Duration
                    .between(start, samples.last().capturedAt)
                    .toMillis()
                    .coerceAtLeast(1)
                    .toDouble()
            val levelLabel = localizedStringResource(Res.string.bw_level, language)
            val poweredLabel = localizedStringResource(Res.string.bw_powered, language)
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.width(38.dp).height(110.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    listOf("100%", "50%", "0%").forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
                Canvas(
                    Modifier
                        .weight(1f)
                        .height(110.dp)
                        .testTag("battery-snapshot-chart")
                        .semantics { contentDescription = "$levelLabel · ${samples.size}" }
                        .pointerInput(samples) {
                            detectTapGestures { offset ->
                                selectedId =
                                    samples
                                        .minByOrNull {
                                            abs(
                                                Duration.between(start, it.capturedAt).toMillis() /
                                                    span * size.width - offset.x,
                                            )
                                        }?.id
                            }
                        },
                ) {
                    val plotHeight = size.height - 14.dp.toPx()

                    fun x(sample: BatterySnapshot): Float =
                        (Duration.between(start, sample.capturedAt).toMillis() / span * size.width).toFloat()

                    fun y(level: Int): Float = (1f - level / 100f) * plotHeight
                    listOf(0f, 0.5f, 1f).forEach { fraction ->
                        drawLine(
                            colors.border,
                            Offset(0f, fraction * plotHeight),
                            Offset(size.width, fraction * plotHeight),
                        )
                    }
                    samples.zipWithNext().forEach { (before, after) ->
                        val a = before.deviceState.levelPercent?.takeIf { it in 0..100 }
                        val b = after.deviceState.levelPercent?.takeIf { it in 0..100 }
                        // Missing samples break the line; no invented level at a missing observation.
                        if (a != null && b != null) {
                            val area =
                                Path().apply {
                                    moveTo(x(before), plotHeight)
                                    lineTo(x(before), y(a))
                                    lineTo(x(after), y(b))
                                    lineTo(x(after), plotHeight)
                                    close()
                                }
                            drawPath(area, colors.success.copy(alpha = 0.13f))
                            drawLine(colors.success, Offset(x(before), y(a)), Offset(x(after), y(b)), 2.dp.toPx())
                        }
                    }
                    samples.forEach { sample ->
                        sample.deviceState.levelPercent?.takeIf { it in 0..100 }?.let {
                            drawCircle(
                                colors.success,
                                if (sample.id ==
                                    selected?.id
                                ) {
                                    5.dp.toPx()
                                } else {
                                    3.dp.toPx()
                                },
                                Offset(x(sample), y(it)),
                            )
                        }
                        // One tick per externally powered snapshot; never extend it into an inferred interval.
                        if (sample.deviceState.powered == true) {
                            drawRect(
                                colors.info,
                                Offset((x(sample) - 2.dp.toPx()).coerceAtLeast(0f), plotHeight + 4.dp.toPx()),
                                Size(4.dp.toPx(), 8.dp.toPx()),
                            )
                        }
                    }
                    selected?.let { sample ->
                        drawLine(
                            colors.secondaryText.copy(alpha = 0.6f),
                            Offset(x(sample), 0f),
                            Offset(x(sample), plotHeight),
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0 s", style = MaterialTheme.typography.labelSmall)
                    Text(
                        "${durationValue(Duration.between(start, samples.last().capturedAt).toMillis())}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("● $levelLabel", color = colors.success, style = MaterialTheme.typography.labelSmall)
                    Text("▪ $poweredLabel", color = colors.info, style = MaterialTheme.typography.labelSmall)
                }
                if (samples.none { sample -> sample.deviceState.levelPercent?.let { it in 0..100 } == true }) {
                    Text(
                        localizedStringResource(Res.string.bw_no_level, language),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    localizedStringResource(Res.string.bw_samples, language, samples.size),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            selected?.let { sample ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        onClick = { selectedId = samples[(samples.indexOf(sample) - 1).coerceAtLeast(0)].id },
                        modifier = Modifier.width(32.dp),
                    ) { Text("‹") }
                    Column(Modifier.weight(1f).testTag("battery-selected-snapshot")) {
                        Text(
                            localizedStringResource(
                                Res.string.bw_sample,
                                language,
                                sample.sequence,
                                sample.capturedAt.toString(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            "$levelLabel: ${sample.deviceState.levelPercent?.let { "$it%" } ?: unavailable} · " +
                                "$poweredLabel: ${yesNo(sample.deviceState.powered, language)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "${localizedStringResource(Res.string.bw_temperature, language)}: " +
                                (
                                    sample.deviceState.temperatureTenthsCelsius?.let { "${it / 10.0} °C" }
                                        ?: unavailable
                                ) +
                                " · ${localizedStringResource(Res.string.bw_voltage, language)}: " +
                                (sample.deviceState.voltageMillivolts?.let { "$it mV" } ?: unavailable),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TextButton(
                        onClick = {
                            selectedId = samples[(samples.indexOf(sample) + 1).coerceAtMost(samples.lastIndex)].id
                        },
                        modifier = Modifier.width(32.dp),
                    ) { Text("›") }
                }
            }
        }
    }
}

@Composable
private fun BatteryHistoryChart(
    history: List<BatteryHistoryEvent>,
    language: UiLanguage,
    modifier: Modifier,
    onSelect: (Int) -> Unit,
) {
    val tracks = batteryHistoryTracks(history)
    val timed = history.mapNotNull { it.elapsedMs }
    val start = timed.minOrNull() ?: 0L
    val end = timed.maxOrNull() ?: start
    val span = (end.toDouble() - start.toDouble()).coerceAtLeast(1.0)
    val colors = LocalViewerColors.current
    StudioPanel(modifier.testTag("battery-history-panel")) {
        StudioPanelHeader(
            localizedStringResource(Res.string.bw_events, language, history.size),
            subtitle = localizedStringResource(Res.string.bw_history_clock, language),
        )
        if (tracks.isEmpty()) {
            Text(localizedStringResource(Res.string.bw_empty, language), Modifier.heightIn(min = 110.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            tracks.forEach { (kind, events) ->
                val label = historyKindName(kind, language)
                Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        Modifier.width(92.dp),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Canvas(
                        Modifier
                            .weight(1f)
                            .height(16.dp)
                            .testTag("battery-track-${kind.name.lowercase()}")
                            .semantics { contentDescription = "$label · ${events.size}" }
                            .pointerInput(events) {
                                detectTapGestures { offset ->
                                    events
                                        .filter { it.value.elapsedMs != null }
                                        .minByOrNull {
                                            val fraction = (it.value.elapsedMs!!.toDouble() - start) / span
                                            abs(fraction * size.width - offset.x)
                                        }?.let { onSelect(it.index) }
                                }
                            },
                    ) {
                        drawLine(colors.border, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
                        events.forEach { (_, event) ->
                            event.elapsedMs?.let { elapsed ->
                                val x = ((elapsed.toDouble() - start) / span * size.width).toFloat()
                                val tone =
                                    when (event.active) {
                                        true -> colors.accent
                                        false -> colors.info
                                        null -> colors.warning
                                    }
                                drawCircle(tone, 3.dp.toPx(), Offset(x, size.height / 2))
                            }
                        }
                    }
                }
            }
        }
        if (timed.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("$start ms", style = MaterialTheme.typography.labelSmall)
                Text("$end ms", style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(
            localizedStringResource(Res.string.bw_track_note, language),
            style = MaterialTheme.typography.labelSmall,
            color = colors.secondaryText,
        )
    }
}

@Composable
private fun BatteryEvidenceTable(
    items: List<BatteryWorkspaceItem>,
    names: Map<String, String>,
    selectedId: String?,
    language: UiLanguage,
    modifier: Modifier,
    onSelect: (String) -> Unit,
) {
    val colors = LocalViewerColors.current
    BoxWithConstraints(modifier) {
        val tableWidth = maxWidth.coerceAtLeast(530.dp)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            StudioDataTable(
                columns =
                    listOf(
                        StudioTableColumn(localizedStringResource(Res.string.bw_name, language), 2.5f),
                        StudioTableColumn(localizedStringResource(Res.string.bw_value, language), 1.4f),
                        StudioTableColumn(localizedStringResource(Res.string.bw_count, language), 0.7f),
                        StudioTableColumn(localizedStringResource(Res.string.bw_confidence, language), 1f),
                    ),
                modifier = Modifier.width(tableWidth),
            ) {
                Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    if (items.isEmpty()) {
                        Text(localizedStringResource(Res.string.bw_empty, language), Modifier.padding(8.dp))
                    }
                    items.forEach { item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .testTag("battery-row-${item.id}")
                                .background(if (selectedId == item.id) colors.selectedRow else colors.transparent)
                                .clickable { onSelect(item.id) }
                                .padding(horizontal = 8.dp, vertical = 9.dp),
                        ) {
                            EvidenceCell(names.getValue(item.id), 2.5f)
                            EvidenceCell(itemValue(item), 1.4f)
                            EvidenceCell(itemCount(item), 0.7f)
                            EvidenceCell(confidenceName(itemConfidence(item), language), 1f)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryEvidenceInspector(
    item: BatteryWorkspaceItem?,
    language: UiLanguage,
    modifier: Modifier,
) {
    StudioPanel(modifier.testTag("battery-evidence-inspector")) {
        StudioPanelHeader(localizedStringResource(Res.string.bw_inspector, language))
        if (item == null) {
            Text(localizedStringResource(Res.string.bw_select, language), style = MaterialTheme.typography.bodySmall)
        } else {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(itemName(item, language), fontWeight = FontWeight.SemiBold)
                when (item) {
                    is BatteryWorkspaceItem.Timer -> {
                        Detail(Res.string.bw_duration, durationValue(item.timer.durationMs), language)
                        Detail(Res.string.bw_count, item.timer.count.toString(), language)
                        Detail(
                            Res.string.bw_source,
                            localizedStringResource(Res.string.bw_uid_delta, language),
                            language,
                        )
                        Detail(Res.string.bw_confidence, confidenceName(item.timer.confidence, language), language)
                        Text(
                            localizedStringResource(Res.string.bw_timer_note, language),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    is BatteryWorkspaceItem.Energy -> {
                        val energy = item.energy
                        Detail(Res.string.bw_value, itemValue(item), language)
                        energy.energyUws?.let { Detail(Res.string.bw_energy, "$it µWs", language) }
                        Detail(Res.string.bw_source, energy.source.name, language)
                        Detail(Res.string.bw_scope, scopeName(energy.attributionScope, language), language)
                        Detail(Res.string.bw_confidence, confidenceName(energy.confidence, language), language)
                    }
                    is BatteryWorkspaceItem.History -> {
                        val event = item.event
                        Detail(Res.string.bw_kind, historyKindName(event.kind, language), language)
                        Detail(
                            Res.string.bw_elapsed,
                            event.elapsedMs?.let { "$it ms" }
                                ?: localizedStringResource(Res.string.bw_unavailable, language),
                            language,
                        )
                        Detail(Res.string.bw_state, eventState(event.active, language), language)
                        Detail(
                            Res.string.bw_uid,
                            event.uid?.toString() ?: localizedStringResource(Res.string.bw_unavailable, language),
                            language,
                        )
                        Detail(Res.string.bw_confidence, confidenceName(event.confidence, language), language)
                        Detail(Res.string.bw_raw, event.raw, language, monospace = true)
                    }
                    is BatteryWorkspaceItem.Counter -> {
                        Detail(Res.string.bw_value, itemValue(item), language)
                        item.packets?.let { Detail(Res.string.bw_packets, it.toString(), language) }
                        if (item.counter == BatteryNetworkCounter.TOTAL_BYTES) {
                            Detail(
                                Res.string.bw_source,
                                localizedStringResource(Res.string.bw_artifact_total, language),
                                language,
                            )
                            Text(
                                localizedStringResource(Res.string.bw_aggregate_network, language),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        } else {
                            Detail(
                                Res.string.bw_source,
                                localizedStringResource(Res.string.bw_uid_delta, language),
                                language,
                            )
                        }
                        Text(
                            localizedStringResource(Res.string.bw_counter_note, language),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    is BatteryWorkspaceItem.Warning ->
                        Text(
                            item.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalViewerColors.current.warning,
                        )
                }
            }
        }
    }
}

@Composable
private fun BatteryThermalSummary(
    run: BatteryRunDelta,
    language: UiLanguage,
) {
    StudioPanel(Modifier.fillMaxWidth()) {
        StudioPanelHeader(localizedStringResource(Res.string.bw_windows, language))
        Text(
            localizedStringResource(
                Res.string.thermal_evidence_detail,
                language,
                run.peakTemperatureCelsius?.let { "$it °C" } ?: "—",
                run.maxThermalStatus ?: "—",
                run.throttledSamples,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        run.performanceWindows.forEach { window ->
            Text(
                localizedStringResource(
                    Res.string.sustained_performance_window,
                    language,
                    window.durationMs,
                    window.peakTemperatureCelsius?.let { "$it °C" } ?: "—",
                    window.maxThermalStatus ?: "—",
                    yesNo(window.throttled, language),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RowScope.EvidenceCell(
    text: String,
    weight: Float,
) {
    Text(
        text,
        Modifier.weight(weight),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Detail(
    resource: StringResource,
    value: String,
    language: UiLanguage,
    monospace: Boolean = false,
) {
    Column {
        Text(
            localizedStringResource(resource, language),
            style = MaterialTheme.typography.labelSmall,
            color = LocalViewerColors.current.secondaryText,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
private fun tabName(
    tab: BatteryResourceTab,
    language: UiLanguage,
): String =
    localizedStringResource(
        when (tab) {
            BatteryResourceTab.WAKELOCKS -> Res.string.wakelocks
            BatteryResourceTab.ALARMS -> Res.string.alarms
            BatteryResourceTab.JOBS -> Res.string.jobs
            BatteryResourceTab.SENSORS -> Res.string.sensors
            BatteryResourceTab.ENERGY -> Res.string.energy
            BatteryResourceTab.NETWORK -> Res.string.network
            BatteryResourceTab.HISTORY -> Res.string.bw_history
            BatteryResourceTab.WARNINGS -> Res.string.diagnostics_and_warnings
        },
        language,
    )

@Composable
private fun historyKindName(
    kind: BatteryHistoryEventKind,
    language: UiLanguage,
): String =
    localizedStringResource(
        when (kind) {
            BatteryHistoryEventKind.WAKELOCK -> Res.string.wakelocks
            BatteryHistoryEventKind.ALARM -> Res.string.alarms
            BatteryHistoryEventKind.JOB -> Res.string.jobs
            BatteryHistoryEventKind.SENSOR -> Res.string.sensors
            BatteryHistoryEventKind.NETWORK -> Res.string.network
            BatteryHistoryEventKind.APP_STATE -> Res.string.bw_app_state
            BatteryHistoryEventKind.SCREEN -> Res.string.bw_screen
            BatteryHistoryEventKind.CHARGING -> Res.string.bw_charging
            BatteryHistoryEventKind.THERMAL -> Res.string.bw_thermal
            BatteryHistoryEventKind.UNKNOWN -> Res.string.bw_unknown
        },
        language,
    )

@Composable
private fun itemName(
    item: BatteryWorkspaceItem,
    language: UiLanguage,
): String =
    when (item) {
        is BatteryWorkspaceItem.Timer -> item.timer.name
        is BatteryWorkspaceItem.Energy -> item.energy.component
        is BatteryWorkspaceItem.History ->
            "${historyKindName(item.event.kind, language)} · ${item.event.name.orEmpty()}"
        is BatteryWorkspaceItem.Warning -> item.message
        is BatteryWorkspaceItem.Counter ->
            localizedStringResource(
                when (item.counter) {
                    BatteryNetworkCounter.MOBILE_RX -> Res.string.bw_mobile_rx
                    BatteryNetworkCounter.MOBILE_TX -> Res.string.bw_mobile_tx
                    BatteryNetworkCounter.WIFI_RX -> Res.string.bw_wifi_rx
                    BatteryNetworkCounter.WIFI_TX -> Res.string.bw_wifi_tx
                    BatteryNetworkCounter.BLUETOOTH_RX -> Res.string.bw_bt_rx
                    BatteryNetworkCounter.BLUETOOTH_TX -> Res.string.bw_bt_tx
                    BatteryNetworkCounter.MOBILE_RADIO -> Res.string.bw_radio
                    BatteryNetworkCounter.TOTAL_BYTES -> Res.string.bw_total_bytes
                },
                language,
            )
    }

private fun itemValue(item: BatteryWorkspaceItem): String =
    when (item) {
        is BatteryWorkspaceItem.Timer -> durationValue(item.timer.durationMs)
        is BatteryWorkspaceItem.Energy ->
            item.energy.energyMah?.let { String.format(Locale.US, "%.3f mAh", it) }
                ?: item.energy.energyUws?.let { "$it µWs" } ?: "—"
        is BatteryWorkspaceItem.History -> item.event.elapsedMs?.let { "$it ms" } ?: "—"
        is BatteryWorkspaceItem.Counter ->
            if (item.counter ==
                BatteryNetworkCounter.MOBILE_RADIO
            ) {
                durationValue(item.value)
            } else {
                "${item.value} B"
            }
        is BatteryWorkspaceItem.Warning -> "—"
    }

private fun itemCount(item: BatteryWorkspaceItem): String =
    when (item) {
        is BatteryWorkspaceItem.Timer -> item.timer.count.toString()
        is BatteryWorkspaceItem.Counter -> item.packets?.toString() ?: "—"
        else -> "—"
    }

private fun itemConfidence(item: BatteryWorkspaceItem): EvidenceConfidence? =
    when (item) {
        is BatteryWorkspaceItem.Timer -> item.timer.confidence
        is BatteryWorkspaceItem.Energy -> item.energy.confidence
        is BatteryWorkspaceItem.History -> item.event.confidence
        else -> null
    }

@Composable
private fun confidenceName(
    confidence: EvidenceConfidence?,
    language: UiLanguage,
): String =
    localizedStringResource(
        when (confidence) {
            EvidenceConfidence.EXACT -> Res.string.bw_exact
            EvidenceConfidence.MODELED -> Res.string.bw_modeled
            EvidenceConfidence.INFERRED -> Res.string.bw_inferred
            EvidenceConfidence.UNAVAILABLE, null -> Res.string.bw_unavailable
        },
        language,
    )

@Composable
private fun yesNo(
    value: Boolean?,
    language: UiLanguage,
): String =
    localizedStringResource(
        when (value) {
            true -> Res.string.bw_yes
            false -> Res.string.bw_no
            null -> Res.string.bw_unavailable
        },
        language,
    )

@Composable
private fun eventState(
    value: Boolean?,
    language: UiLanguage,
): String =
    localizedStringResource(
        when (value) {
            true -> Res.string.bw_active
            false -> Res.string.bw_inactive
            null -> Res.string.bw_unavailable
        },
        language,
    )

@Composable
private fun scopeName(
    scope: AttributionScope,
    language: UiLanguage,
): String =
    localizedStringResource(
        when (scope) {
            AttributionScope.PACKAGE -> Res.string.bw_package_scope
            AttributionScope.UID -> Res.string.bw_uid_scope
            AttributionScope.SHARED_UID -> Res.string.bw_shared_uid_scope
            AttributionScope.DEVICE -> Res.string.bw_device_scope
        },
        language,
    )

private fun durationValue(durationMs: Long): String =
    if (durationMs < 1_000) "$durationMs ms" else String.format(Locale.US, "%.2f s", durationMs / 1_000.0)
