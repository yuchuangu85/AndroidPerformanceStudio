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

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
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
        Text("Details", color = style.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(detailsPrompt(state, report), color = style.secondaryText, fontSize = 10.sp)
    }
}

private fun detailsPrompt(
    state: ReportState,
    report: ReportData,
): String =
    when (state.selectedTab) {
        ReportTab.OVERVIEW -> "Select a finding to inspect details."
        ReportTab.TOP_FUNCTIONS -> "Select a function to inspect details."
        ReportTab.CALL_TREE,
        ReportTab.FLAME_GRAPH,
        -> "Select a call stack frame to inspect details."
        ReportTab.STACK_CHART -> "Select a stack block to inspect details."
        ReportTab.MARKER_CHART,
        ReportTab.MARKER_TABLE,
        -> "Select a marker to inspect details."
    }.let { prompt -> if (report.overview.sampleCount >= 0) prompt else prompt }
