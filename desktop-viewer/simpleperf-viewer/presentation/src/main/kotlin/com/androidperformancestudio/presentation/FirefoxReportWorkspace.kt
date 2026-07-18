package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.application.ReportController
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportState
import kotlin.math.roundToInt

@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList", "ktlint:standard:function-naming")
internal fun FirefoxReportWorkspace(
    state: ReportState,
    report: ReportData,
    actions: ReportActions,
    style: MacOsDeviceTargetStyle,
    flameTooltipMode: FlameTooltipMode,
) {
    val density = LocalDensity.current
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(style.panel, RoundedCornerShape(10.dp))
                .border(MacOsDeviceTargetDimensions.hairline, style.border, RoundedCornerShape(10.dp))
                .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(state.workspace.timelineHeightDp.dp)
                .testTag("report-timeline"),
        ) {
            TimelineReport(state, report, actions, style)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .testTag("timeline-divider")
                .background(style.strongBorder)
                .semantics {
                    progressBarRangeInfo =
                        ProgressBarRangeInfo(
                            state.workspace.timelineHeightDp.toFloat(),
                            MIN_TIMELINE_HEIGHT_DP.toFloat()..MAX_TIMELINE_HEIGHT_DP.toFloat(),
                        )
                }.pointerInput(state.workspace.timelineHeightDp, density) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val deltaDp = with(density) { dragAmount.y.toDp().value }
                        actions.onTimelineHeightDp((state.workspace.timelineHeightDp + deltaDp).roundToInt())
                    }
                },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FirefoxReportTabs(state.selectedTab, actions.onSelectTab, style)
            Box(Modifier.testTag("show-details")) {
                MacOsButton(
                    label = if (state.workspace.detailsVisible) "Hide details" else "Show details",
                    onClick = { actions.onDetailsVisible(!state.workspace.detailsVisible) },
                    style = style,
                )
            }
        }
        FirefoxStackToolbar(state, actions, style)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxHeight().testTag("report-content")) {
                ReportSelectedPanel(state, report, actions, style, flameTooltipMode)
            }
            if (state.workspace.detailsVisible) {
                Box(Modifier.width(300.dp).fillMaxHeight()) {
                    FirefoxReportDetails(state, report, style)
                }
            }
        }
        Text(ReportController.WEIGHT_SEMANTICS, color = style.secondaryText, fontSize = 9.sp)
    }
}

private const val MIN_TIMELINE_HEIGHT_DP = 120
private const val MAX_TIMELINE_HEIGHT_DP = 480
