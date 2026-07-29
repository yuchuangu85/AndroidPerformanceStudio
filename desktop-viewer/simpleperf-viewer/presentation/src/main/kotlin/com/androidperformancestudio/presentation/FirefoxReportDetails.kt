@file:Suppress("FunctionName", "MaxLineLength", "ktlint:standard:function-naming")

package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.storage.MarkerProjectionRow
import com.androidperformancestudio.storage.PanelProjection
import com.androidperformancestudio.ui.MacOsDeviceTargetDimensions
import com.androidperformancestudio.ui.MacOsDeviceTargetStyle

@Composable
@Suppress("FunctionName", "LongMethod", "ktlint:standard:function-naming")
internal fun FirefoxReportDetails(
    state: ReportState,
    report: ReportData,
    style: MacOsDeviceTargetStyle,
) {
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .testTag("report-details")
                .background(style.panel)
                .border(MacOsDeviceTargetDimensions.hairline, style.border)
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
    style: MacOsDeviceTargetStyle,
) {
    val finding = report.diagnostics.firstOrNull { it.ruleId == state.workspace.selections.overviewFindingRuleId }
    if (finding == null) {
        DetailsPrompt("Select a finding to inspect details.", style)
    } else {
        DetailsHeading("Finding details", style)
        Text(finding.title, color = style.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(finding.conclusion, color = style.secondaryText, fontSize = 10.sp)
        finding.recommendations.forEach { Text("• $it", color = style.text, fontSize = 9.sp) }
    }
}

@Composable
private fun TopFunctionDetails(
    state: ReportState,
    report: ReportData,
    style: MacOsDeviceTargetStyle,
) {
    val function = report.topFunctions.firstOrNull { it.symbolName == state.workspace.selections.topFunctionKey }
    if (function == null) {
        DetailsPrompt("Select a function to inspect details.", style)
    } else {
        DetailsHeading("Function details", style)
        Text(function.symbolName, color = style.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(function.filePath, color = style.secondaryText, fontSize = 9.sp)
        Text("Inclusive ${function.inclusiveWeight}", color = style.text, fontSize = 10.sp)
        Text("Exclusive ${function.exclusiveWeight}", color = style.text, fontSize = 10.sp)
    }
}

@Composable
private fun CallStackDetails(
    state: ReportState,
    report: ReportData,
    style: MacOsDeviceTargetStyle,
) {
    val selected = state.workspace.selections.callNodeId
    val treeNode = report.callTree.firstOrNull { it.id == selected?.value }
    val flameFrame = selected?.let { report.flameGraph.callNodes.indexOf(it) }?.let(report.flameGraph.callNodes::frameAt)
    val symbol = treeNode?.symbolName ?: flameFrame?.symbolName
    if (symbol == null) {
        DetailsPrompt("Select a call stack frame to inspect details.", style)
    } else {
        DetailsHeading("Function details", style)
        Text(symbol, color = style.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(treeNode?.filePath ?: flameFrame?.resource.orEmpty(), color = style.secondaryText, fontSize = 9.sp)
    }
}

@Composable
private fun StackBlockDetails(
    state: ReportState,
    report: ReportData,
    style: MacOsDeviceTargetStyle,
) {
    val snapshot = (report.stackChart as? PanelProjection.Ready)?.value
    val block = snapshot?.blocks?.firstOrNull { it.id == state.workspace.selections.stackChartBlockId }
    val frame = block?.let { snapshot.framesById[it.frameId] }
    if (block == null || frame == null) {
        DetailsPrompt("Select a stack block to inspect details.", style)
    } else {
        DetailsHeading("Stack block details", style)
        Text(frame.symbolName, color = style.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(frame.resource, color = style.secondaryText, fontSize = 9.sp)
        Text("Start: ${block.startNanos}", color = style.text, fontSize = 10.sp)
        Text("End: ${block.endNanosExclusive}", color = style.text, fontSize = 10.sp)
    }
}

@Composable
private fun MarkerDetails(
    state: ReportState,
    report: ReportData,
    style: MacOsDeviceTargetStyle,
) {
    val marker =
        (report.markers as? PanelProjection.Ready)
            ?.value
            ?.markers
            ?.firstOrNull { it.id == state.workspace.selections.markerId }
    if (marker == null) {
        DetailsPrompt("Select a marker to inspect details.", style)
    } else {
        MarkerDetailsContent(marker, style)
    }
}

@Composable
private fun MarkerDetailsContent(
    marker: MarkerProjectionRow,
    style: MacOsDeviceTargetStyle,
) {
    DetailsHeading("Marker details", style)
    Text(marker.name, color = style.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    Text("Start: ${marker.startNanos}", color = style.secondaryText, fontSize = 10.sp)
    Text("End: ${marker.endNanosExclusive}", color = style.secondaryText, fontSize = 10.sp)
    Text("Duration: ${marker.endNanosExclusive - marker.startNanos}", color = style.secondaryText, fontSize = 10.sp)
    Text("Process: ${marker.processId ?: "Global"}", color = style.secondaryText, fontSize = 10.sp)
    Text("Thread: ${marker.threadName ?: marker.threadId ?: "Global"}", color = style.secondaryText, fontSize = 10.sp)
    Text("Schema: ${marker.schema}", color = style.secondaryText, fontSize = 10.sp)
    val formattedPayload = marker.payloadJson.prettyJsonOrNull()
    Text(formattedPayload ?: marker.payloadJson, color = style.text, fontSize = 9.sp)
    if (formattedPayload == null && marker.payloadJson.isNotBlank()) {
        Text("Payload is not valid JSON; showing raw text.", color = style.warning, fontSize = 9.sp)
    }
}

@Composable
private fun DetailsHeading(
    title: String,
    style: MacOsDeviceTargetStyle,
) {
    Text(title, color = style.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun DetailsPrompt(
    prompt: String,
    style: MacOsDeviceTargetStyle,
) {
    DetailsHeading("Details", style)
    Text(prompt, color = style.secondaryText, fontSize = 10.sp)
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
