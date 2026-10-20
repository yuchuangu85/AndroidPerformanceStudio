@file:Suppress("FunctionName", "MaxLineLength", "ktlint:standard:function-naming")

package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.storage.MarkerProjectionRow
import com.androidperformancestudio.storage.PanelProjection
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.ViewerDimensions
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.button.MacOSTextButton
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioTokens
import org.jetbrains.compose.resources.StringResource

@Composable
@Suppress("FunctionName", "LongMethod", "ktlint:standard:function-naming")
internal fun FirefoxReportDetails(
    state: ReportState,
    report: ReportData,
    style: ViewerColors,
    actions: ReportActions? = null,
) {
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .testTag("report-details")
                .verticalScroll(rememberScrollState())
                .background(style.panel)
                .border(ViewerDimensions.hairline, style.border)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state.selectedTab) {
            ReportTab.OVERVIEW -> OverviewDetails(state, report, style)
            ReportTab.TOP_FUNCTIONS -> TopFunctionDetails(state, report, style, actions)
            ReportTab.CALL_TREE,
            ReportTab.FLAME_GRAPH,
            -> CallStackDetails(state, report, style, actions)
            ReportTab.STACK_CHART -> StackBlockDetails(state, report, style)
            ReportTab.MARKER_CHART,
            ReportTab.MARKER_TABLE,
            -> MarkerDetails(state, report, style)
        }
    }
}

@Composable
private fun OverviewDetails(
    state: ReportState,
    report: ReportData,
    style: ViewerColors,
) {
    val finding = report.diagnostics.firstOrNull { it.ruleId == state.workspace.selections.overviewFindingRuleId }
    if (finding == null) {
        DetailsPrompt(SimpleperfViewerRes.sp_finding_select_finding_hint, style)
    } else {
        DetailsHeading(SimpleperfViewerRes.sp_finding_finding_details, style)
        Text(finding.title, color = style.text, fontSize = ViewerTypography.secondary.fontSize, fontWeight = FontWeight.SemiBold)
        Text(finding.conclusion, color = style.secondaryText, fontSize = ViewerTypography.label.fontSize)
        finding.recommendations.forEach {
            Text(
                localizedStringResource(
                    SimpleperfViewerRes.sp_common_bullet_format,
                    currentSimpleperfLanguage(),
                    it,
                ),
                color = style.text,
                fontSize = ViewerTypography.dense.fontSize,
            )
        }
    }
}

@Composable
private fun TopFunctionDetails(
    state: ReportState,
    report: ReportData,
    style: ViewerColors,
    actions: ReportActions?,
) {
    val function = report.topFunctions.firstOrNull { it.symbolName == state.workspace.selections.topFunctionKey }
    if (function == null) {
        DetailsPrompt(SimpleperfViewerRes.sp_function_select_function_hint, style)
    } else {
        val total = report.overview.totalEventWeight
        DetailsHeading(SimpleperfViewerRes.sp_function_function_details, style)
        InspectorSymbol(function.symbolName, function.filePath, style)
        InspectorMetricGrid(
            listOf(
                SimpleperfViewerRes.sp_inspector_total_share to inspectorShare(function.inclusiveWeight, total),
                SimpleperfViewerRes.sp_inspector_self_share to inspectorShare(function.exclusiveWeight, total),
                SimpleperfViewerRes.sp_inspector_inclusive to formatCpuCount(function.inclusiveWeight),
                SimpleperfViewerRes.sp_inspector_self to formatCpuCount(function.exclusiveWeight),
                SimpleperfViewerRes.sp_inspector_samples to formatCpuCount(function.sampleCount),
                SimpleperfViewerRes.sp_inspector_threads to formatCpuCount(function.threadCount),
            ),
            style,
        )
        Text(
            localizedStringResource(
                SimpleperfViewerRes.sp_report_inclusive_value_format,
                currentSimpleperfLanguage(),
                function.inclusiveWeight,
            ),
            color = style.secondaryText,
            fontSize = ViewerTypography.dense.fontSize,
        )
        Text(
            localizedStringResource(
                SimpleperfViewerRes.sp_report_exclusive_value_format,
                currentSimpleperfLanguage(),
                function.exclusiveWeight,
            ),
            color = style.secondaryText,
            fontSize = ViewerTypography.dense.fontSize,
        )
        actions?.let { InspectorActions(function.symbolName, it, style) }
    }
}

@Composable
private fun CallStackDetails(
    state: ReportState,
    report: ReportData,
    style: ViewerColors,
    actions: ReportActions?,
) {
    val selected = state.workspace.selections.callNodeId
    val frame = remember(report, selected) { report.cpuSelectedFrame(selected) }
    if (frame == null) {
        DetailsPrompt(SimpleperfViewerRes.sp_calltree_select_call_stack_frame_hint, style)
    } else {
        DetailsHeading(SimpleperfViewerRes.sp_function_function_details, style)
        InspectorSymbol(frame.symbolName, frame.resource, style)
        InspectorMetricGrid(
            listOf(
                SimpleperfViewerRes.sp_inspector_total_share to (frame.totalShare?.let(::formatCpuPercent) ?: "—"),
                SimpleperfViewerRes.sp_inspector_self_share to (frame.selfShare?.let(::formatCpuPercent) ?: "—"),
                SimpleperfViewerRes.sp_inspector_inclusive to formatCpuCount(frame.inclusiveWeight),
                SimpleperfViewerRes.sp_inspector_self to formatCpuCount(frame.selfWeight),
                SimpleperfViewerRes.sp_inspector_samples to formatCpuCount(frame.sampleCount),
                SimpleperfViewerRes.sp_inspector_threads to formatCpuCount(frame.threadCount),
            ),
            style,
        )
        InspectorFrameList(SimpleperfViewerRes.sp_inspector_call_path, frame.path, frame.nodeId, actions, style)
        if (frame.topCallees.isNotEmpty()) {
            InspectorFrameList(SimpleperfViewerRes.sp_inspector_children, frame.topCallees, null, actions, style)
        }
        actions?.let { InspectorActions(frame.symbolName, it, style) }
    }
}

@Composable
private fun InspectorSymbol(
    symbol: String,
    resource: String,
    style: ViewerColors,
) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                symbol,
                modifier = Modifier.testTag("inspector-symbol"),
                color = style.text,
                fontSize = ViewerTypography.secondary.fontSize,
                fontWeight = FontWeight.SemiBold,
            )
            if (resource.isNotBlank()) {
                Text(
                    localizedStringResource(SimpleperfViewerRes.sp_inspector_library, currentSimpleperfLanguage()) + ": " + resource,
                    color = style.secondaryText,
                    fontSize = ViewerTypography.dense.fontSize,
                )
            }
        }
    }
}

@Composable
private fun InspectorMetricGrid(
    metrics: List<Pair<StringResource, String>>,
    style: ViewerColors,
) {
    val language = currentSimpleperfLanguage()
    Column(Modifier.testTag("inspector-metrics"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        metrics.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                pair.forEach { (label, value) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .background(style.toolbar, RoundedCornerShape(StudioTokens.smallRadius))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        Text(
                            localizedStringResource(label, language),
                            color = style.secondaryText,
                            fontSize = ViewerTypography.micro.fontSize,
                            maxLines = 1,
                        )
                        Text(
                            value,
                            color = style.text,
                            fontSize = ViewerTypography.label.fontSize,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun InspectorFrameList(
    title: StringResource,
    frames: List<CpuPathFrame>,
    current: FlameCallNodeId?,
    actions: ReportActions?,
    style: ViewerColors,
) {
    DetailsHeading(title, style)
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        frames.forEachIndexed { index, frame ->
            val isCurrent = frame.nodeId == current
            Text(
                frame.symbolName,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (actions != null) {
                                Modifier.clickable { actions.onSelectCallNode(frame.nodeId) }
                            } else {
                                Modifier
                            },
                        ).padding(start = if (current != null) (index * INSPECTOR_PATH_INDENT).dp else 0.dp, top = 1.dp, bottom = 1.dp),
                color = if (isCurrent) style.accent else style.text,
                fontSize = ViewerTypography.dense.fontSize,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InspectorActions(
    symbol: String,
    actions: ReportActions,
    style: ViewerColors,
) {
    val language = currentSimpleperfLanguage()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.testTag("inspector-focus-flame")) {
            MacOSTextButton(
                localizedStringResource(SimpleperfViewerRes.sp_inspector_focus_flame, language),
                { actions.onFocusFunction(symbol) },
                style,
            )
        }
        Box(Modifier.testTag("inspector-focus-call-tree")) {
            MacOSTextButton(
                localizedStringResource(SimpleperfViewerRes.sp_inspector_focus_call_tree, language),
                { actions.onFocusCallTreeFunction(symbol) },
                style,
            )
        }
        Box(Modifier.testTag("inspector-copy-symbol")) {
            MacOSTextButton(
                localizedStringResource(SimpleperfViewerRes.sp_inspector_copy_symbol, language),
                { actions.onCopyFlameFunction(symbol) },
                style,
            )
        }
    }
}

private fun inspectorShare(
    weight: Long,
    total: Long,
): String = if (total > 0L) formatCpuPercent(weight.toDouble() / total) else "—"

private const val INSPECTOR_PATH_INDENT = 6

@Composable
private fun StackBlockDetails(
    state: ReportState,
    report: ReportData,
    style: ViewerColors,
) {
    val snapshot = (report.stackChart as? PanelProjection.Ready)?.value
    val block = snapshot?.blocks?.firstOrNull { it.id == state.workspace.selections.stackChartBlockId }
    val frame = block?.let { snapshot.framesById[it.frameId] }
    if (block == null || frame == null) {
        DetailsPrompt(SimpleperfViewerRes.sp_stack_select_stack_block_hint, style)
    } else {
        DetailsHeading(SimpleperfViewerRes.sp_stack_stack_block_details, style)
        Text(frame.symbolName, color = style.text, fontSize = ViewerTypography.secondary.fontSize, fontWeight = FontWeight.SemiBold)
        Text(frame.resource, color = style.secondaryText, fontSize = ViewerTypography.dense.fontSize)
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_marker_start_value_format, currentSimpleperfLanguage(), block.startNanos),
            color = style.text,
            fontSize = ViewerTypography.label.fontSize,
        )
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_marker_end_value_format, currentSimpleperfLanguage(), block.endNanosExclusive),
            color = style.text,
            fontSize = ViewerTypography.label.fontSize,
        )
    }
}

@Composable
private fun MarkerDetails(
    state: ReportState,
    report: ReportData,
    style: ViewerColors,
) {
    val marker =
        (report.markers as? PanelProjection.Ready)
            ?.value
            ?.markers
            ?.firstOrNull { it.id == state.workspace.selections.markerId }
    if (marker == null) {
        DetailsPrompt(SimpleperfViewerRes.sp_marker_select_marker_hint, style)
    } else {
        MarkerDetailsContent(marker, style)
    }
}

@Composable
private fun MarkerDetailsContent(
    marker: MarkerProjectionRow,
    style: ViewerColors,
) {
    val language = currentSimpleperfLanguage()
    DetailsHeading(SimpleperfViewerRes.sp_marker_marker_details, style)
    Text(marker.name, color = style.text, fontSize = ViewerTypography.secondary.fontSize, fontWeight = FontWeight.SemiBold)
    Text(
        localizedStringResource(SimpleperfViewerRes.sp_marker_start_value_format, language, marker.startNanos),
        color = style.secondaryText,
        fontSize = ViewerTypography.label.fontSize,
    )
    Text(
        localizedStringResource(SimpleperfViewerRes.sp_marker_end_value_format, language, marker.endNanosExclusive),
        color = style.secondaryText,
        fontSize = ViewerTypography.label.fontSize,
    )
    Text(
        localizedStringResource(
            SimpleperfViewerRes.sp_capture_duration_value_format,
            language,
            marker.endNanosExclusive - marker.startNanos,
        ),
        color = style.secondaryText,
        fontSize = ViewerTypography.label.fontSize,
    )
    Text(
        localizedStringResource(SimpleperfViewerRes.sp_details_process_value_format, language, marker.processId ?: "Global"),
        color = style.secondaryText,
        fontSize = ViewerTypography.label.fontSize,
    )
    Text(
        localizedStringResource(
            SimpleperfViewerRes.sp_details_thread_value_format,
            language,
            marker.threadName ?: marker.threadId ?: "Global",
        ),
        color = style.secondaryText,
        fontSize = ViewerTypography.label.fontSize,
    )
    Text(
        localizedStringResource(SimpleperfViewerRes.sp_marker_schema_value_format, language, marker.schema),
        color = style.secondaryText,
        fontSize = ViewerTypography.label.fontSize,
    )
    val formattedPayload = marker.payloadJson.prettyJsonOrNull()
    Text(formattedPayload ?: marker.payloadJson, color = style.text, fontSize = ViewerTypography.dense.fontSize)
    if (formattedPayload == null && marker.payloadJson.isNotBlank()) {
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_diagnostics_invalid_json_raw_fallback_message, language),
            color = style.warning,
            fontSize = ViewerTypography.dense.fontSize,
        )
    }
}

@Composable
private fun DetailsHeading(
    title: StringResource,
    style: ViewerColors,
) {
    Text(
        localizedStringResource(title, currentSimpleperfLanguage()),
        color = style.text,
        fontSize = ViewerTypography.bodyCompact.fontSize,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun DetailsPrompt(
    prompt: StringResource,
    style: ViewerColors,
) {
    DetailsHeading(SimpleperfViewerRes.sp_details_details, style)
    Text(
        localizedStringResource(prompt, currentSimpleperfLanguage()),
        color = style.secondaryText,
        fontSize = ViewerTypography.label.fontSize,
    )
}

@Suppress("ComplexCondition", "CyclomaticComplexMethod", "ReturnCount")
private fun String.prettyJsonOrNull(): String? {
    val trimmed = trim()
    if (!(trimmed.startsWith('{') && trimmed.endsWith('}')) && !(trimmed.startsWith('[') && trimmed.endsWith(']'))) {
        return null
    }
    var indent = 0
    var quoted = false
    var escaped = false
    return buildString {
        trimmed.forEach { character ->
            when {
                escaped -> {
                    append(character)
                    escaped = false
                }
                character == '\\' && quoted -> {
                    append(character)
                    escaped = true
                }
                character == '"' -> {
                    quoted = !quoted
                    append(character)
                }
                quoted -> append(character)
                character == '{' || character == '[' -> {
                    append(character)
                    appendLine()
                    indent++
                    append("  ".repeat(indent))
                }
                character == '}' || character == ']' -> {
                    appendLine()
                    indent--
                    if (indent < 0) return null
                    append("  ".repeat(indent))
                    append(character)
                }
                character == ',' -> {
                    append(character)
                    appendLine()
                    append("  ".repeat(indent))
                }
                character == ':' -> append(": ")
                !character.isWhitespace() -> append(character)
            }
        }
        if (quoted || indent != 0) return null
    }
}
