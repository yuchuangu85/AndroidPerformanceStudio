package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.ViewerDimensions
import com.androidperformancestudio.ui.localizedStringResource
import java.awt.Cursor
import kotlin.math.roundToInt

@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList", "ktlint:standard:function-naming")
internal fun FirefoxReportWorkspace(
    state: ReportState,
    report: ReportData,
    actions: ReportActions,
    style: ViewerColors,
    flameTooltipMode: FlameTooltipMode,
) {
    val language = currentSimpleperfLanguage()
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(style.panel, RoundedCornerShape(10.dp))
                .border(ViewerDimensions.hairline, style.border, RoundedCornerShape(10.dp))
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
        TimelineResizeHandle(
            currentHeightDp = state.workspace.timelineHeightDp,
            onHeightChange = actions.onTimelineHeightDp,
            style = style,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FirefoxReportTabs(state.selectedTab, actions.onSelectTab, style)
            Box(Modifier.testTag("show-details")) {
                MacOsButton(
                    label =
                        localizedStringResource(
                            if (state.workspace.detailsVisible) {
                                SimpleperfViewerRes.sp_report_hide_details
                            } else {
                                SimpleperfViewerRes.sp_report_show_details
                            },
                            language,
                        ),
                    onClick = { actions.onDetailsVisible(!state.workspace.detailsVisible) },
                    style = style,
                )
            }
        }
        if (state.selectedTab == ReportTab.MARKER_CHART || state.selectedTab == ReportTab.MARKER_TABLE) {
            FirefoxMarkerToolbar(state, actions, style)
        } else {
            FirefoxStackToolbar(state, actions, style)
        }
        FirefoxReportContentAndDetails(
            state = state,
            report = report,
            actions = actions,
            style = style,
            flameTooltipMode = flameTooltipMode,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_diagnostics_sample_weight_duration_disclaimer, language),
            color = style.secondaryText,
            fontSize = 9.sp,
        )
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun TimelineResizeHandle(
    currentHeightDp: Int,
    onHeightChange: (Int) -> Unit,
    style: ViewerColors,
) {
    val density = LocalDensity.current
    val latestHeightDp by rememberUpdatedState(currentHeightDp)
    val latestOnHeightChange by rememberUpdatedState(onHeightChange)
    val resizeDescription =
        localizedStringResource(
            SimpleperfViewerRes.sp_report_resize_timeline_description,
            currentSimpleperfLanguage(),
        )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(TIMELINE_RESIZE_HANDLE_HEIGHT)
                .testTag("timeline-divider")
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
                .semantics {
                    contentDescription = resizeDescription
                    progressBarRangeInfo =
                        ProgressBarRangeInfo(
                            currentHeightDp.toFloat(),
                            MIN_TIMELINE_HEIGHT_DP.toFloat()..MAX_TIMELINE_HEIGHT_DP.toFloat(),
                        )
                }.pointerInput(density) {
                    var dragHeightDp = latestHeightDp.toFloat()
                    detectVerticalDragGestures(
                        onDragStart = { dragHeightDp = latestHeightDp.toFloat() },
                    ) { change, dragAmount ->
                        change.consume()
                        dragHeightDp =
                            (dragHeightDp + dragAmount / density.density)
                                .coerceIn(MIN_TIMELINE_HEIGHT_DP.toFloat(), MAX_TIMELINE_HEIGHT_DP.toFloat())
                        latestOnHeightChange(dragHeightDp.roundToInt())
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(style.strongBorder))
    }
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun FirefoxReportContentAndDetails(
    state: ReportState,
    report: ReportData,
    actions: ReportActions,
    style: ViewerColors,
    flameTooltipMode: FlameTooltipMode,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier) {
        if (state.workspace.detailsVisible && maxWidth < NARROW_REPORT_WIDTH) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().testTag("report-content")) {
                    ReportSelectedPanel(state, report, actions, style, flameTooltipMode)
                }
                Box(Modifier.fillMaxWidth().height(NARROW_DETAILS_HEIGHT)) {
                    FirefoxReportDetails(state, report, style)
                }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().testTag("report-content")) {
                    ReportSelectedPanel(state, report, actions, style, flameTooltipMode)
                }
                if (state.workspace.detailsVisible) {
                    Box(Modifier.width(WIDE_DETAILS_WIDTH).fillMaxHeight()) {
                        FirefoxReportDetails(state, report, style)
                    }
                }
            }
        }
    }
}

private const val MIN_TIMELINE_HEIGHT_DP = 120
private const val MAX_TIMELINE_HEIGHT_DP = 480
private val TIMELINE_RESIZE_HANDLE_HEIGHT = 8.dp
private val NARROW_REPORT_WIDTH = 760.dp
private val NARROW_DETAILS_HEIGHT = 160.dp
private val WIDE_DETAILS_WIDTH = 300.dp
