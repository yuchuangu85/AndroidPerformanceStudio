@file:Suppress("MaxLineLength")

package com.androidperformancestudio.presentation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class FirefoxReportAccessibilityTest {
    @Test
    fun `details never leaks selection from the previous tab`() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            val base = markerReportState()
            val state =
                mutableStateOf(
                    base.copy(
                        selectedTab = ReportTab.FLAME_GRAPH,
                        workspace =
                            base.workspace.copy(
                                selections = base.workspace.selections.copy(callNodeId = FlameCallNodeId(3), markerId = null),
                            ),
                    ),
                )
            val actions = goldenActions().copy(onSelectTab = { state.value = state.value.copy(selectedTab = it) })
            setContent { ReportPage(state.value, actions) }

            onNodeWithTag("report-tab-MARKER_TABLE").performClick()

            onNodeWithText("Function details").assertDoesNotExist()
            onNodeWithText("Select a marker to inspect details.").assertExists()
        }

    @Test
    fun `timeline divider exposes bounded progress semantics`() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            setContent { ReportPage(sampleReportState(), goldenActions()) }

            onNodeWithTag("timeline-divider")
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ProgressBarRangeInfo,
                        ProgressBarRangeInfo(220f, 120f..480f),
                    ),
                )
            onNodeWithContentDescription("Drag to resize timeline").assertExists()
        }
}
