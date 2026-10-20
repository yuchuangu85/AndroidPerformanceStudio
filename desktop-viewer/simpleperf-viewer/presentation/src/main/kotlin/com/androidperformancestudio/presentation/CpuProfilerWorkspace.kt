@file:Suppress("FunctionName", "TooManyFunctions", "ktlint:standard:function-naming")

package com.androidperformancestudio.presentation

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.ViewerDimensions
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.button.MacOSTextButton
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioTokens
import org.jetbrains.compose.resources.StringResource
import java.util.Locale

/**
 * Display order of the CPU profiler tabs. The [ReportTab] enum order is persisted and shared with the
 * controller, so the commercial layout (Overview | Flame Graph | Call Tree | Top Functions | Stack | Markers)
 * is expressed here instead of by reordering the enum.
 */
internal val CPU_PROFILER_TAB_ORDER: List<ReportTab> =
    listOf(
        ReportTab.OVERVIEW,
        ReportTab.FLAME_GRAPH,
        ReportTab.CALL_TREE,
        ReportTab.TOP_FUNCTIONS,
        ReportTab.STACK_CHART,
        ReportTab.MARKER_CHART,
        ReportTab.MARKER_TABLE,
    )

internal enum class CpuKpiKind {
    SAMPLES,
    THREADS,
    CAPTURE_DURATION,
    EFFECTIVE_SAMPLE_RATE,
    INVALID_SAMPLES,
    TOP_FUNCTION_SHARE,
}

/** Cards every recorded session can fill; also the placeholder set shown before a report is ready. */
internal val CPU_BASE_KPI_KINDS: List<CpuKpiKind> =
    listOf(CpuKpiKind.SAMPLES, CpuKpiKind.THREADS, CpuKpiKind.CAPTURE_DURATION, CpuKpiKind.EFFECTIVE_SAMPLE_RATE)

/**
 * KPI values derived only from evidence the session actually recorded. Metrics such as average CPU usage,
 * energy cost or CPU temperature are never synthesised: simpleperf samples do not carry them.
 */
internal data class CpuProfilerKpis(
    val sampleCount: Long,
    val lostSampleCount: Long,
    val lostRate: Double,
    val threadCount: Long,
    val processCount: Long,
    val durationNanos: Long?,
    val windowStartNanos: Long?,
    val windowEndNanos: Long?,
    val effectiveSampleRateHz: Double?,
    val unwindErrorSamples: Long,
    val unknownSymbolSamples: Long,
    val emptyStackSamples: Long,
    val topFunctionName: String?,
    val topFunctionShare: Double?,
) {
    val invalidSampleCount: Long
        get() = lostSampleCount + unwindErrorSamples + emptyStackSamples

    /** Cards backed by evidence; quality cards only appear when the quality summary was recorded. */
    val visibleKinds: List<CpuKpiKind>
        get() =
            buildList {
                addAll(CPU_BASE_KPI_KINDS)
                if (invalidSampleCount > 0L || unknownSymbolSamples > 0L) add(CpuKpiKind.INVALID_SAMPLES)
                if (topFunctionShare != null) add(CpuKpiKind.TOP_FUNCTION_SHARE)
            }
}

internal fun ReportData.cpuProfilerKpis(): CpuProfilerKpis {
    val start = overview.startNanos
    val end = overview.endNanosInclusive
    val duration = if (start != null && end != null && end >= start) end - start else null
    val rate =
        duration
            ?.takeIf { it > 0L && overview.sampleCount > 0L }
            ?.let { overview.sampleCount * NANOS_PER_SECOND / it.toDouble() }
    val hottestSelf = topFunctions.maxByOrNull { it.exclusiveWeight }
    val share =
        hottestSelf
            ?.takeIf { overview.totalEventWeight > 0L && it.exclusiveWeight > 0L }
            ?.let { it.exclusiveWeight.toDouble() / overview.totalEventWeight }
    return CpuProfilerKpis(
        sampleCount = overview.sampleCount,
        lostSampleCount = quality.lostSampleCount,
        lostRate = quality.lostRate,
        threadCount = overview.threadCount,
        processCount = overview.processCount,
        durationNanos = duration,
        // Offsets are relative to the session start; clamp so a report starting earlier never renders negative time.
        windowStartNanos = start?.let { (it - (sessionOverview.startNanos ?: it)).coerceAtLeast(0L) },
        windowEndNanos = end?.let { (it - (sessionOverview.startNanos ?: start ?: it)).coerceAtLeast(0L) },
        effectiveSampleRateHz = rate,
        unwindErrorSamples = quality.unwindErrorSamples,
        unknownSymbolSamples = quality.unknownSymbolSamples,
        emptyStackSamples = quality.emptyStackSamples,
        topFunctionName = hottestSelf?.symbolName?.takeIf { share != null },
        topFunctionShare = share,
    )
}

internal fun formatCpuDuration(nanos: Long): String =
    when {
        nanos >= NANOS_PER_SECOND_LONG -> String.format(Locale.ROOT, "%.2f s", nanos / NANOS_PER_SECOND)
        nanos >= NANOS_PER_MILLI -> String.format(Locale.ROOT, "%.1f ms", nanos / NANOS_PER_MILLI.toDouble())
        nanos >= NANOS_PER_MICRO -> String.format(Locale.ROOT, "%.1f µs", nanos / NANOS_PER_MICRO.toDouble())
        else -> "$nanos ns"
    }

internal fun formatCpuRate(hz: Double): String =
    when {
        hz >= HZ_PER_MHZ -> String.format(Locale.ROOT, "%.2f MHz", hz / HZ_PER_MHZ)
        hz >= HZ_PER_KHZ -> String.format(Locale.ROOT, "%.2f kHz", hz / HZ_PER_KHZ)
        else -> String.format(Locale.ROOT, "%.0f Hz", hz)
    }

internal fun formatCpuPercent(fraction: Double): String = String.format(Locale.ROOT, "%.1f%%", fraction * PERCENT)

internal fun formatCpuCount(value: Long): String = String.format(Locale.ROOT, "%,d", value)

@Composable
internal fun CpuProfilerKpiStrip(
    report: ReportData,
    style: ViewerColors,
    modifier: Modifier = Modifier,
) {
    val language = currentSimpleperfLanguage()
    val kpis = remember(report) { report.cpuProfilerKpis() }
    val kinds = kpis.visibleKinds
    BoxWithConstraints(modifier.fillMaxWidth().testTag("cpu-kpi-strip")) {
        val columns = cpuKpiColumns(maxWidth.value.toInt(), kinds.size)
        Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap)) {
            kinds.chunked(columns).forEach { rowKinds ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.panelGap)) {
                    rowKinds.forEach { kind ->
                        CpuKpiTile(kind, kpis, language, style, Modifier.weight(1f))
                    }
                    repeat(columns - rowKinds.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * The same card grid before a report is ready, so the page shows its cards immediately and fills them in place
 * once the captured or imported session finishes parsing. Values stay empty; nothing is estimated meanwhile.
 */
@Composable
internal fun CpuProfilerPendingKpiStrip(
    pendingText: String,
    style: ViewerColors,
    modifier: Modifier = Modifier,
) {
    val language = currentSimpleperfLanguage()
    BoxWithConstraints(modifier.fillMaxWidth().testTag("cpu-kpi-strip-pending")) {
        val columns = cpuKpiColumns(maxWidth.value.toInt(), CPU_BASE_KPI_KINDS.size)
        Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap)) {
            CPU_BASE_KPI_KINDS.chunked(columns).forEach { rowKinds ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.panelGap)) {
                    rowKinds.forEach { kind ->
                        CpuKpiTileFrame(
                            kind = kind,
                            labelText = localizedStringResource(kind.labelResource(), language),
                            value = PENDING_KPI_VALUE,
                            support = pendingText,
                            style = style,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - rowKinds.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private fun CpuKpiKind.labelResource(): StringResource =
    when (this) {
        CpuKpiKind.SAMPLES -> SimpleperfViewerRes.sp_kpi_samples
        CpuKpiKind.THREADS -> SimpleperfViewerRes.sp_kpi_threads
        CpuKpiKind.CAPTURE_DURATION -> SimpleperfViewerRes.sp_kpi_capture_duration
        CpuKpiKind.EFFECTIVE_SAMPLE_RATE -> SimpleperfViewerRes.sp_kpi_effective_sample_rate
        CpuKpiKind.INVALID_SAMPLES -> SimpleperfViewerRes.sp_kpi_invalid_samples
        CpuKpiKind.TOP_FUNCTION_SHARE -> SimpleperfViewerRes.sp_kpi_top_function_share
    }

internal fun cpuKpiColumns(
    availableWidthDp: Int,
    count: Int,
): Int {
    val fit =
        when {
            availableWidthDp >= KPI_WIDE_WIDTH -> MAX_KPI_COLUMNS
            availableWidthDp >= KPI_MEDIUM_WIDTH -> MEDIUM_KPI_COLUMNS
            else -> NARROW_KPI_COLUMNS
        }
    return fit.coerceAtMost(count.coerceAtLeast(1))
}

@Composable
@Suppress("LongMethod")
private fun CpuKpiTile(
    kind: CpuKpiKind,
    kpis: CpuProfilerKpis,
    language: UiLanguage,
    style: ViewerColors,
    modifier: Modifier,
) {
    val unavailable = localizedStringResource(SimpleperfViewerRes.sp_kpi_unavailable, language)
    val (value, support) =
        when (kind) {
            CpuKpiKind.SAMPLES ->
                Pair(
                    formatCpuCount(kpis.sampleCount),
                    localizedStringResource(
                        SimpleperfViewerRes.sp_kpi_samples_support_format,
                        language,
                        formatCpuCount(kpis.lostSampleCount),
                        formatCpuPercent(kpis.lostRate),
                    ),
                )
            CpuKpiKind.THREADS ->
                Pair(
                    formatCpuCount(kpis.threadCount),
                    localizedStringResource(
                        SimpleperfViewerRes.sp_kpi_threads_support_format,
                        language,
                        formatCpuCount(kpis.processCount),
                    ),
                )
            CpuKpiKind.CAPTURE_DURATION ->
                Pair(
                    kpis.durationNanos?.let(::formatCpuDuration) ?: unavailable,
                    if (kpis.windowStartNanos != null && kpis.windowEndNanos != null) {
                        localizedStringResource(
                            SimpleperfViewerRes.sp_kpi_capture_duration_support_format,
                            language,
                            formatCpuDuration(kpis.windowStartNanos),
                            formatCpuDuration(kpis.windowEndNanos),
                        )
                    } else {
                        null
                    },
                )
            CpuKpiKind.EFFECTIVE_SAMPLE_RATE ->
                Pair(
                    kpis.effectiveSampleRateHz?.let(::formatCpuRate) ?: unavailable,
                    localizedStringResource(SimpleperfViewerRes.sp_kpi_effective_sample_rate_support, language),
                )
            CpuKpiKind.INVALID_SAMPLES ->
                Pair(
                    formatCpuCount(kpis.invalidSampleCount),
                    localizedStringResource(
                        SimpleperfViewerRes.sp_kpi_invalid_samples_support_format,
                        language,
                        formatCpuCount(kpis.unwindErrorSamples),
                        formatCpuCount(kpis.unknownSymbolSamples),
                        formatCpuCount(kpis.emptyStackSamples),
                    ),
                )
            CpuKpiKind.TOP_FUNCTION_SHARE ->
                Pair(
                    kpis.topFunctionShare?.let(::formatCpuPercent) ?: unavailable,
                    kpis.topFunctionName,
                )
        }
    CpuKpiTileFrame(kind, localizedStringResource(kind.labelResource(), language), value, support, style, modifier)
}

@Composable
@Suppress("LongParameterList")
private fun CpuKpiTileFrame(
    kind: CpuKpiKind,
    labelText: String,
    value: String,
    support: String?,
    style: ViewerColors,
    modifier: Modifier,
) {
    val tone = if (kind == CpuKpiKind.INVALID_SAMPLES) style.warning else style.accent
    Column(
        modifier
            .testTag("cpu-kpi-${kind.name}")
            .background(style.panel, RoundedCornerShape(StudioTokens.cardRadius))
            .border(ViewerDimensions.hairline, style.border, RoundedCornerShape(StudioTokens.cardRadius))
            .semantics { contentDescription = "$labelText: $value" }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            labelText,
            color = tone,
            fontSize = ViewerTypography.label.fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            color = style.text,
            fontSize = ViewerTypography.lead.fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            support.orEmpty(),
            color = style.secondaryText,
            fontSize = ViewerTypography.dense.fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal data class CpuExportEntry(
    val id: String,
    val label: StringResource,
    val onClick: () -> Unit,
)

internal fun ReportActions.cpuExportEntries(): List<CpuExportEntry> =
    listOf(
        CpuExportEntry("report", SimpleperfViewerRes.sp_workspace_export_report, onExportReport),
        CpuExportEntry("session", SimpleperfViewerRes.sp_workspace_export_session, onExportSession),
        CpuExportEntry("gecko", SimpleperfViewerRes.sp_workspace_export_gecko, onExportGeckoProfile),
        CpuExportEntry("folded", SimpleperfViewerRes.sp_workspace_export_folded, onExportFoldedStacks),
        CpuExportEntry("pprof", SimpleperfViewerRes.sp_workspace_export_pprof, onExportPprof),
        CpuExportEntry("html", SimpleperfViewerRes.sp_workspace_export_html, onGenerateHtmlReport),
        CpuExportEntry("screenshot", SimpleperfViewerRes.sp_workspace_export_screenshot, onExportScreenshot),
    )

@Composable
internal fun CpuExportMenuButton(
    actions: ReportActions,
    style: ViewerColors,
) {
    val language = currentSimpleperfLanguage()
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.testTag("cpu-export")) {
        MacOSTextButton(
            label = localizedStringResource(SimpleperfViewerRes.sp_workspace_export, language),
            onClick = { expanded = true },
            style = style,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(style.panel),
        ) {
            actions.cpuExportEntries().forEach { entry ->
                DropdownMenuItem(
                    modifier = Modifier.testTag("cpu-export-${entry.id}"),
                    text = {
                        Text(
                            localizedStringResource(entry.label, language),
                            color = style.text,
                            fontSize = ViewerTypography.label.fontSize,
                        )
                    },
                    onClick = {
                        expanded = false
                        entry.onClick()
                    },
                )
            }
        }
    }
}

/** A frame in the root-to-leaf call path of the current selection. */
internal data class CpuPathFrame(
    val nodeId: FlameCallNodeId,
    val symbolName: String,
)

/** Selected call stack frame resolved from either the call tree projection or the flame call node table. */
internal data class CpuSelectedFrame(
    val nodeId: FlameCallNodeId,
    val symbolName: String,
    val resource: String,
    val inclusiveWeight: Long,
    val selfWeight: Long,
    val sampleCount: Long,
    val threadCount: Long,
    val totalWeight: Long,
    val path: List<CpuPathFrame>,
    val topCallees: List<CpuPathFrame>,
) {
    val totalShare: Double?
        get() = if (totalWeight > 0L) inclusiveWeight.toDouble() / totalWeight else null

    val selfShare: Double?
        get() = if (totalWeight > 0L) selfWeight.toDouble() / totalWeight else null
}

internal fun ReportData.cpuSelectedFrame(selected: FlameCallNodeId?): CpuSelectedFrame? {
    selected ?: return null
    return callTreeSelectedFrame(selected) ?: flameSelectedFrame(selected)
}

private fun ReportData.callTreeSelectedFrame(selected: FlameCallNodeId): CpuSelectedFrame? {
    val byId = callTree.associateBy { it.id }
    val node = byId[selected.value] ?: return null
    val path =
        generateSequence(node) { current -> current.parentId?.let(byId::get) }
            .map { CpuPathFrame(FlameCallNodeId(it.id), it.symbolName) }
            .toList()
            .asReversed()
    val callees =
        callTree
            .filter { it.parentId == node.id }
            .sortedByDescending { it.inclusiveWeight }
            .take(MAX_TOP_CALLEES)
            .map { CpuPathFrame(FlameCallNodeId(it.id), it.symbolName) }
    return CpuSelectedFrame(
        nodeId = selected,
        symbolName = node.symbolName,
        resource = node.filePath,
        inclusiveWeight = node.inclusiveWeight,
        selfWeight = node.exclusiveWeight,
        sampleCount = node.sampleCount,
        threadCount = node.threadCount,
        totalWeight = overview.totalEventWeight,
        path = path,
        topCallees = callees,
    )
}

private fun ReportData.flameSelectedFrame(selected: FlameCallNodeId): CpuSelectedFrame? {
    val table = flameGraph.callNodes
    val index = table.indexOf(selected) ?: return null
    val frame = table.frameAt(index) ?: return null
    val path =
        generateSequence(index) { current -> table.parentIndexAt(current)?.takeIf { it >= 0 } }
            .mapNotNull { nodeIndex ->
                val id = table.nodeIdAt(nodeIndex) ?: return@mapNotNull null
                CpuPathFrame(id, table.frameAt(nodeIndex)?.symbolName.orEmpty())
            }.toList()
            .asReversed()
    val callees =
        generateSequence(table.firstChildIndexAt(index)) { table.nextSiblingIndexAt(it) }
            .sortedByDescending { table.inclusiveWeightAt(it) ?: 0L }
            .take(MAX_TOP_CALLEES)
            .mapNotNull { child ->
                table.nodeIdAt(child)?.let { CpuPathFrame(it, table.frameAt(child)?.symbolName.orEmpty()) }
            }.toList()
    return CpuSelectedFrame(
        nodeId = selected,
        symbolName = frame.symbolName,
        resource = frame.resource,
        inclusiveWeight = table.inclusiveWeightAt(index) ?: 0L,
        selfWeight = table.selfWeightAt(index) ?: 0L,
        sampleCount = table.sampleCountAt(index) ?: 0L,
        threadCount = (table.threadCountAt(index) ?: 0).toLong(),
        totalWeight = flameGraph.totalWeight,
        path = path,
        topCallees = callees,
    )
}

@Composable
internal fun CpuCallPathBreadcrumb(
    report: ReportData,
    selected: FlameCallNodeId?,
    onSelect: (FlameCallNodeId) -> Unit,
    style: ViewerColors,
) {
    val language = currentSimpleperfLanguage()
    val frame = remember(report, selected) { report.cpuSelectedFrame(selected) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(BREADCRUMB_HEIGHT)
            .testTag("cpu-call-path-breadcrumb")
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_workspace_selected_path, language),
            color = style.secondaryText,
            fontSize = ViewerTypography.dense.fontSize,
            fontWeight = FontWeight.SemiBold,
        )
        if (frame == null) {
            Text(
                localizedStringResource(SimpleperfViewerRes.sp_workspace_no_selection_path, language),
                color = style.secondaryText,
                fontSize = ViewerTypography.dense.fontSize,
            )
        } else {
            frame.path.forEachIndexed { index, pathFrame ->
                if (index > 0) {
                    Text("›", color = style.secondaryText, fontSize = ViewerTypography.dense.fontSize)
                }
                val current = pathFrame.nodeId == frame.nodeId
                Text(
                    pathFrame.symbolName,
                    modifier =
                        Modifier
                            .testTag("cpu-breadcrumb-${pathFrame.nodeId.value}")
                            .clickable { onSelect(pathFrame.nodeId) }
                            .padding(horizontal = 2.dp),
                    color = if (current) style.accent else style.text,
                    fontSize = ViewerTypography.dense.fontSize,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun CpuToolbarSeparator(style: ViewerColors) {
    Box(Modifier.width(ViewerDimensions.hairline).height(16.dp).background(style.border))
}

private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val NANOS_PER_SECOND_LONG = 1_000_000_000L
private const val NANOS_PER_MILLI = 1_000_000L
private const val NANOS_PER_MICRO = 1_000L
private const val HZ_PER_KHZ = 1_000.0
private const val HZ_PER_MHZ = 1_000_000.0
private const val PERCENT = 100.0
private const val MAX_TOP_CALLEES = 5
private const val MAX_KPI_COLUMNS = 6
private const val MEDIUM_KPI_COLUMNS = 3
private const val NARROW_KPI_COLUMNS = 2
private const val KPI_WIDE_WIDTH = 900
private const val KPI_MEDIUM_WIDTH = 520
private val BREADCRUMB_HEIGHT = 22.dp
private const val PENDING_KPI_VALUE = "—"
