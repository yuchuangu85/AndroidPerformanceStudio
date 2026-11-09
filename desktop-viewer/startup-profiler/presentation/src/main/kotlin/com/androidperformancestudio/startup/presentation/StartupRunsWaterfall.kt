@file:Suppress("FunctionName", "LongMethod", "MagicNumber", "MaxLineLength", "ktlint:standard:function-naming")

package com.androidperformancestudio.startup.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.startup.model.StartupMilestoneKind
import com.androidperformancestudio.startup.model.StartupPhase
import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.presentation.generated.resources.Res
import com.androidperformancestudio.startup.presentation.generated.resources.export_csv
import com.androidperformancestudio.startup.presentation.generated.resources.platform_durations_independent
import com.androidperformancestudio.startup.presentation.generated.resources.run
import com.androidperformancestudio.startup.presentation.generated.resources.startup_waterfall
import com.androidperformancestudio.startup.presentation.generated.resources.waterfall_all_phases
import com.androidperformancestudio.startup.presentation.generated.resources.waterfall_axis
import com.androidperformancestudio.startup.presentation.generated.resources.waterfall_clock_domain
import com.androidperformancestudio.startup.presentation.generated.resources.waterfall_no_intervals
import com.androidperformancestudio.startup.presentation.generated.resources.waterfall_platform_only
import com.androidperformancestudio.startup.presentation.generated.resources.waterfall_runs_preview
import com.androidperformancestudio.startup.presentation.generated.resources.waterfall_unplotted_phases
import com.androidperformancestudio.ui.CopyableText
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioPanel
import com.androidperformancestudio.ui.studio.StudioTokens

internal fun startupWaterfallPreview(
    runs: List<StartupRun>,
    selected: StartupRun,
): List<StartupRun> {
    val initial = runs.take(5)
    return if (initial.any { it.id == selected.id }) initial else initial.take(4) + selected
}

@Composable
internal fun StartupRunsWaterfall(
    runs: List<StartupRun>,
    selected: StartupRun,
    actions: StartupProfilerActions,
    language: UiLanguage,
    isRunning: Boolean = false,
) {
    val preview = remember(runs, selected.id) { startupWaterfallPreview(runs, selected) }
    val models = remember(preview) { preview.associate { it.id to it.waterfallModel() } }
    val sources = models.values.flatMap { it.groups.map { group -> group.source } }.distinct()
    val colors = LocalViewerColors.current
    val palette = listOf(colors.info, colors.success, colors.warning, colors.accent, colors.error, colors.online)
    val phases =
        models.values
            .flatMap { it.groups }
            .flatMap { it.segments }
            .map { it.phase }
            .distinctBy { it.start to it.end }
    val phaseKinds = phases.map { it.start to it.end }
    var phaseFilter by remember(runs, phaseKinds) { mutableStateOf<Pair<StartupMilestoneKind, StartupMilestoneKind>?>(null) }
    StudioPanel(
        modifier = Modifier.fillMaxWidth().testTag("startup-waterfall"),
        contentPadding = StudioTokens.compactContentPadding,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(localizedStringResource(Res.string.startup_waterfall, language), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text(
                localizedStringResource(Res.string.waterfall_axis, language),
                color = colors.secondaryText,
                fontSize = ViewerTypography.secondary.fontSize,
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StartupWaterfallPhaseSelector(phases, phaseFilter, { phaseFilter = it }, language)
            ProfilerCompactButton(
                localizedStringResource(Res.string.export_csv, language),
                enabled = !isRunning,
                modifier = Modifier.testTag("startup-waterfall-export"),
                onClick = actions.onExportCsv,
            )
        }
        if (sources.isEmpty()) {
            CopyableText(localizedStringResource(Res.string.waterfall_no_intervals, language), style = MaterialTheme.typography.bodySmall)
            CopyableText(
                localizedStringResource(Res.string.waterfall_platform_only, language),
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryText,
            )
            preview.forEach { run ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .testTag("startup-waterfall-${run.id}")
                        .selectable(run.id == selected.id, role = Role.RadioButton, onClick = { actions.onSelectRun(run.id) })
                        .background(if (run.id == selected.id) colors.selectedRow else colors.transparent),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("${localizedStringResource(Res.string.run, language)} ${run.iteration}")
                    Text(run.platform.totalTimeMs.formatMs())
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            sources.forEach { source ->
                val extent =
                    models.values
                        .flatMap { it.groups }
                        .filter { it.source == source }
                        .maxOf { it.extentNs }
                        .coerceAtLeast(1L)
                Text(
                    localizedStringResource(Res.string.waterfall_clock_domain, language, source.name),
                    color = colors.secondaryText,
                    fontSize = ViewerTypography.secondary.fontSize,
                )
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    models.values
                        .flatMap { it.groups }
                        .filter { it.source == source }
                        .flatMap { it.segments }
                        .distinctBy { it.phase.start to it.phase.end }
                        .filterPhase(phaseFilter)
                        .forEach { segment ->
                            val index = phaseKinds.indexOf(segment.phase.start to segment.phase.end)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.size(7.dp).background(palette[index % palette.size]))
                                Text(segment.phase.localizedName(language), fontSize = ViewerTypography.secondary.fontSize)
                            }
                        }
                }
                preview.forEach { run ->
                    val group = models.getValue(run.id).groups.firstOrNull { it.source == source }
                    val originNs = group?.originNs ?: 0L
                    val isSelected = run.id == selected.id
                    val visibleSegments = group?.segments.orEmpty().filterPhase(phaseFilter)
                    val rowDescription =
                        visibleSegments.joinToString("; ") { segment ->
                            "${segment.phase.localizedName(
                                language,
                            )} ${(segment.phase.durationNs / 1_000_000.0).formatMs()} ${segment.phase.confidence.localizedLabel(language)}"
                        }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(25.dp)
                            .testTag("startup-waterfall-${run.id}")
                            .semantics { contentDescription = "$source · $rowDescription" }
                            .background(if (isSelected) colors.selectedRow else colors.transparent)
                            .selectable(isSelected, role = Role.RadioButton, onClick = { actions.onSelectRun(run.id) }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "${localizedStringResource(Res.string.run, language)} ${run.iteration}",
                            Modifier.width(44.dp),
                            fontSize = ViewerTypography.secondary.fontSize,
                        )
                        Canvas(Modifier.weight(1f).height(20.dp)) {
                            for (tick in 0..4) {
                                val x = size.width * tick / 4f
                                drawLine(colors.border, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                            }
                            visibleSegments.forEach { segment ->
                                val index = phaseKinds.indexOf(segment.phase.start to segment.phase.end)
                                val x =
                                    ((segment.startNs - originNs).toDouble() / extent * size.width).toFloat().coerceIn(
                                        0f,
                                        size.width,
                                    )
                                val width =
                                    (segment.phase.durationNs.toDouble() / extent * size.width).toFloat().coerceIn(
                                        0f,
                                        size.width - x,
                                    )
                                if (segment.phase.durationNs == 0L) {
                                    drawLine(palette[index % palette.size], Offset(x, 3f), Offset(x, size.height - 3f), strokeWidth = 2f)
                                } else {
                                    drawRect(
                                        palette[index % palette.size].copy(alpha = 0.85f),
                                        Offset(x, 3f),
                                        Size(width, size.height - 6f),
                                    )
                                }
                            }
                        }
                        Text(run.platform.totalTimeMs.formatMs(), Modifier.width(72.dp), fontSize = ViewerTypography.secondary.fontSize)
                    }
                }
                StartupWaterfallAxis(extent)
            }
        }
        val omitted = models.values.sumOf { it.unplottedPhaseCount }
        if (omitted >
            0
        ) {
            CopyableText(
                localizedStringResource(Res.string.waterfall_unplotted_phases, language, omitted),
                color = colors.warning,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (runs.size >
            preview.size
        ) {
            CopyableText(
                localizedStringResource(Res.string.waterfall_runs_preview, language, preview.size, runs.size),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        CopyableText(
            localizedStringResource(Res.string.platform_durations_independent, language),
            color = colors.secondaryText,
            fontSize = ViewerTypography.secondary.fontSize,
        )
    }
}

@Composable
private fun StartupWaterfallPhaseSelector(
    phases: List<StartupPhase>,
    selected: Pair<StartupMilestoneKind, StartupMilestoneKind>?,
    onSelect: (Pair<StartupMilestoneKind, StartupMilestoneKind>?) -> Unit,
    language: UiLanguage,
) {
    val allPhases = localizedStringResource(Res.string.waterfall_all_phases, language)
    DropdownSelector(
        items = listOf(null) + phases.map { it.start to it.end },
        selectedItem = selected,
        onItemSelected = onSelect,
        itemLabel = { kind -> phases.firstOrNull { it.start to it.end == kind }?.localizedName(language) ?: allPhases },
        placeholder = allPhases,
        enabled = phases.isNotEmpty(),
        modifier = Modifier.testTag("startup-waterfall-phase-filter"),
    )
}

private fun List<StartupWaterfallSegment>.filterPhase(
    kind: Pair<StartupMilestoneKind, StartupMilestoneKind>?,
): List<StartupWaterfallSegment> = if (kind == null) this else filter { it.phase.start to it.phase.end == kind }

@Composable
private fun StartupWaterfallAxis(extentNs: Long) {
    Row(Modifier.fillMaxWidth().padding(start = 52.dp, end = 80.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        for (tick in 0..4) {
            Text(
                (extentNs / 1_000_000.0 * tick / 4.0).formatMs().removeSuffix(" ms"),
                color = LocalViewerColors.current.secondaryText,
                fontSize = ViewerTypography.secondary.fontSize,
            )
        }
    }
}
