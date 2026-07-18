@file:Suppress("MaxLineLength")

package com.androidperformancestudio.presentation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.storage.MarkerAvailability
import com.androidperformancestudio.storage.MarkerLane
import com.androidperformancestudio.storage.MarkerProjectionSnapshot
import com.androidperformancestudio.storage.PanelProjection
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class MarkerPanelsComposeUiTest {
    @Test
    fun `table selection is visible in marker chart after tab switch`() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            val state = mutableStateOf(markerReportState())
            val actions =
                goldenActions().copy(
                    onSelectTab = { tab -> state.value = state.value.copy(selectedTab = tab) },
                    onSelectMarker = { id ->
                        val workspace = state.value.workspace
                        state.value =
                            state.value.copy(
                                workspace =
                                    workspace.copy(
                                        selections = workspace.selections.copy(markerId = id),
                                    ),
                            )
                    },
                )
            setContent { ReportPage(state.value, actions) }

            onNodeWithTag("report-tab-MARKER_TABLE").performClick()
            onNodeWithTag("marker-row-7").performClick()
            onNodeWithTag("report-tab-MARKER_CHART").performClick()
            onNodeWithTag("marker-glyph-7").assertIsSelected()
        }

    @Test
    fun `not collected remains visible instead of hiding marker tabs`() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            val base = markerReportState()
            val report = requireNotNull(base.lastReadyReport)
            val empty = MarkerProjectionSnapshot(MarkerAvailability.NOT_COLLECTED, null, emptyList(), emptyList())
            val updated = report.copy(markers = PanelProjection.Ready(empty))
            val state = base.copy(loadState = ReportLoadState.Ready(updated), lastReadyReport = updated)

            setContent { ReportPage(state, goldenActions()) }

            onNodeWithText("Markers were not collected for this session.").assertExists()
            onNodeWithTag("report-tab-MARKER_TABLE").assertExists()
        }
}

internal fun markerReportState() =
    sampleReportState(ReportTab.MARKER_CHART).let { base ->
        val report = requireNotNull(base.lastReadyReport)
        val markers = listOf(marker(id = 7, interval = false, start = 30, end = 31), marker(id = 8, interval = true, start = 40, end = 70))
        val snapshot =
            MarkerProjectionSnapshot(
                availability = MarkerAvailability.AVAILABLE,
                emptyReason = null,
                markers = markers,
                lanes = listOf(MarkerLane("simpleperf:7421:7440", "RenderThread", markers.map { it.id })),
            )
        val updated = report.copy(markers = PanelProjection.Ready(snapshot))
        base.copy(loadState = ReportLoadState.Ready(updated), lastReadyReport = updated)
    }
