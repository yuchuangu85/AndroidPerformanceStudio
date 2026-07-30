@file:Suppress("FunctionName", "MaxLineLength", "ktlint:standard:function-naming")

package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.storage.MarkerProjectionRow
import com.androidperformancestudio.storage.PanelProjection
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.ViewerDimensions
import com.androidperformancestudio.ui.localizedStringResource
import org.jetbrains.compose.resources.StringResource

@Composable
@Suppress("FunctionName", "LongMethod", "ktlint:standard:function-naming")
internal fun FirefoxReportDetails(
    state: ReportState,
    report: ReportData,
    style: ViewerColors,
) {
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .testTag("report-details")
                .background(style.panel)
                .border(ViewerDimensions.hairline, style.border)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state.selectedTab) {
            ReportTab.OVERVIEW -> OverviewDetails(state, report, style)
            ReportTab.TOP_FUNCTIONS -> TopFunctionDetails(state, report, style)
            ReportTab.CALL_TREE,
            ReportTab.FLAME_GRAPH,
            -> CallStackDetails(state, report, style)
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
        Text(finding.title, color = style.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(finding.conclusion, color = style.secondaryText, fontSize = 10.sp)
        finding.recommendations.forEach {
            Text(
                localizedStringResource(
                    SimpleperfViewerRes.sp_common_bullet_format,
                    currentSimpleperfLanguage(),
                    it,
                ),
                color = style.text,
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
private fun TopFunctionDetails(
    state: ReportState,
    report: ReportData,
    style: ViewerColors,
) {
    val function = report.topFunctions.firstOrNull { it.symbolName == state.workspace.selections.topFunctionKey }
    if (function == null) {
        DetailsPrompt(SimpleperfViewerRes.sp_function_select_function_hint, style)
    } else {
        DetailsHeading(SimpleperfViewerRes.sp_function_function_details, style)
        Text(function.symbolName, color = style.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(function.filePath, color = style.secondaryText, fontSize = 9.sp)
        Text(
            localizedStringResource(
                SimpleperfViewerRes.sp_report_inclusive_value_format,
                currentSimpleperfLanguage(),
                function.inclusiveWeight,
            ),
            color = style.text,
            fontSize = 10.sp,
        )
        Text(
            localizedStringResource(
                SimpleperfViewerRes.sp_report_exclusive_value_format,
                currentSimpleperfLanguage(),
                function.exclusiveWeight,
            ),
            color = style.text,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun CallStackDetails(
    state: ReportState,
    report: ReportData,
    style: ViewerColors,
) {
    val selected = state.workspace.selections.callNodeId
    val treeNode = report.callTree.firstOrNull { it.id == selected?.value }
    val flameFrame = selected?.let { report.flameGraph.callNodes.indexOf(it) }?.let(report.flameGraph.callNodes::frameAt)
    val symbol = treeNode?.symbolName ?: flameFrame?.symbolName
    if (symbol == null) {
        DetailsPrompt(SimpleperfViewerRes.sp_calltree_select_call_stack_frame_hint, style)
    } else {
        DetailsHeading(SimpleperfViewerRes.sp_function_function_details, style)
        Text(symbol, color = style.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(treeNode?.filePath ?: flameFrame?.resource.orEmpty(), color = style.secondaryText, fontSize = 9.sp)
    }
}

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
        Text(frame.symbolName, color = style.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(frame.resource, color = style.secondaryText, fontSize = 9.sp)
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_marker_start_value_format, currentSimpleperfLanguage(), block.startNanos),
            color = style.text,
            fontSize = 10.sp,
        )
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_marker_end_value_format, currentSimpleperfLanguage(), block.endNanosExclusive),
            color = style.text,
            fontSize = 10.sp,
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
    Text(marker.name, color = style.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    Text(
        localizedStringResource(SimpleperfViewerRes.sp_marker_start_value_format, language, marker.startNanos),
        color = style.secondaryText,
        fontSize = 10.sp,
    )
    Text(
        localizedStringResource(SimpleperfViewerRes.sp_marker_end_value_format, language, marker.endNanosExclusive),
        color = style.secondaryText,
        fontSize = 10.sp,
    )
    Text(
        localizedStringResource(
            SimpleperfViewerRes.sp_capture_duration_value_format,
            language,
            marker.endNanosExclusive - marker.startNanos,
        ),
        color = style.secondaryText,
        fontSize = 10.sp,
    )
    Text(
        localizedStringResource(SimpleperfViewerRes.sp_details_process_value_format, language, marker.processId ?: "Global"),
        color = style.secondaryText,
        fontSize = 10.sp,
    )
    Text(
        localizedStringResource(
            SimpleperfViewerRes.sp_details_thread_value_format,
            language,
            marker.threadName ?: marker.threadId ?: "Global",
        ),
        color = style.secondaryText,
        fontSize = 10.sp,
    )
    Text(
        localizedStringResource(SimpleperfViewerRes.sp_marker_schema_value_format, language, marker.schema),
        color = style.secondaryText,
        fontSize = 10.sp,
    )
    val formattedPayload = marker.payloadJson.prettyJsonOrNull()
    Text(formattedPayload ?: marker.payloadJson, color = style.text, fontSize = 9.sp)
    if (formattedPayload == null && marker.payloadJson.isNotBlank()) {
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_diagnostics_invalid_json_raw_fallback_message, language),
            color = style.warning,
            fontSize = 9.sp,
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
        fontSize = 12.sp,
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
        fontSize = 10.sp,
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
