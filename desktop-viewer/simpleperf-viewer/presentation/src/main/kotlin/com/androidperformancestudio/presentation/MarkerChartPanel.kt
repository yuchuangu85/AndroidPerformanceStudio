@file:Suppress("MaxLineLength")

package com.androidperformancestudio.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.storage.MarkerAvailability
import com.androidperformancestudio.storage.MarkerEmptyReason
import com.androidperformancestudio.storage.MarkerProjectionSnapshot
import com.androidperformancestudio.storage.PanelProjection
import com.androidperformancestudio.ui.ViewerColors

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun MarkerChartPanel(
    state: ReportState,
    projection: PanelProjection<MarkerProjectionSnapshot>,
    actions: ReportActions,
    style: ViewerColors,
) {
    Column(Modifier.fillMaxSize().testTag("marker-chart-panel")) {
        when (projection) {
            is PanelProjection.Failed -> MarkerPanelMessage("${projection.code}: ${projection.message}", style)
            is PanelProjection.Ready -> {
                val snapshot = projection.value
                if (snapshot.markers.isEmpty()) {
                    MarkerPanelMessage(snapshot.markerMessage(), style)
                } else {
                    val report = requireNotNull(state.lastReadyReport)
                    val start = state.filter.startNanosInclusive ?: report.sessionOverview.startNanos ?: 0L
                    val end = state.filter.endNanosExclusive ?: report.sessionOverview.endNanosInclusive?.plus(1) ?: start + 1
                    MarkerChartCanvas(
                        snapshot,
                        StackChartViewport(start, end),
                        state.workspace.selections.markerId,
                        style,
                        actions.onSelectMarker,
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun MarkerPanelMessage(
    message: String,
    style: ViewerColors,
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, color = style.secondaryText, fontSize = 11.sp)
    }
}

internal fun MarkerProjectionSnapshot.markerMessage(): String =
    when {
        availability == MarkerAvailability.NOT_COLLECTED -> "Markers were not collected for this session."
        emptyReason == MarkerEmptyReason.PROFILE_EMPTY -> "The profile contains no markers."
        emptyReason == MarkerEmptyReason.RANGE_EMPTY -> "No markers overlap the selected range."
        emptyReason == MarkerEmptyReason.FILTERED_EMPTY -> "No markers match the current filter."
        else -> "No markers are available."
    }
