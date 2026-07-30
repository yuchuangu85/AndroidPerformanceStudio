@file:Suppress("MaxLineLength")

package com.androidperformancestudio.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.presentation.generated.resources.ViewerRes
import com.androidperformancestudio.storage.MarkerAvailability
import com.androidperformancestudio.storage.MarkerEmptyReason
import com.androidperformancestudio.storage.MarkerProjectionSnapshot
import com.androidperformancestudio.storage.PanelProjection
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.localizedStringResource

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
                    MarkerPanelMessage(snapshot.markerMessage(currentSimpleperfLanguage()), style)
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

internal fun MarkerProjectionSnapshot.markerMessage(language: UiLanguage = UiLanguage.ENGLISH): String =
    when {
        availability == MarkerAvailability.NOT_COLLECTED ->
            localizedStringResource(ViewerRes.sp_marker_markers_not_collected_empty_state, language)
        emptyReason == MarkerEmptyReason.PROFILE_EMPTY ->
            localizedStringResource(ViewerRes.sp_marker_profile_has_no_markers_empty_state, language)
        emptyReason == MarkerEmptyReason.RANGE_EMPTY ->
            localizedStringResource(ViewerRes.sp_marker_no_markers_in_range_empty_state, language)
        emptyReason == MarkerEmptyReason.FILTERED_EMPTY ->
            localizedStringResource(ViewerRes.sp_marker_no_markers_match_filter_empty_state, language)
        else -> localizedStringResource(ViewerRes.sp_marker_no_markers_available_empty_state, language)
    }
