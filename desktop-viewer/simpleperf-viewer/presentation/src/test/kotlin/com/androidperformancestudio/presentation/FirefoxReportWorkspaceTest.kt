package com.androidperformancestudio.presentation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.application.ReportTab
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FirefoxReportWorkspaceTest {
    @Test
    fun `timeline and seven tabs remain mounted while content switches`() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            val state = mutableStateOf(sampleReportState())
            val actions =
                goldenActions().copy(
                    onSelectTab = { tab -> state.value = state.value.copy(selectedTab = tab) },
                )

            setContent { ReportPage(state.value, actions) }

            onNodeWithTag("report-timeline").assertExists()
            ReportTab.entries.forEach { tab -> onNodeWithTag("report-tab-${tab.name}").assertExists() }
            onNodeWithTag("report-tab-MARKER_TABLE").performClick()
            onNodeWithTag("report-timeline").assertExists()
            onNodeWithTag("marker-table-panel").assertExists()
        }

    @Test
    fun `details toggle survives tab changes`() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            val initial = sampleReportState().let { it.copy(workspace = it.workspace.copy(detailsVisible = true)) }
            val state = mutableStateOf(initial)
            val actions =
                goldenActions().copy(
                    onDetailsVisible = { visible ->
                        state.value = state.value.copy(workspace = state.value.workspace.copy(detailsVisible = visible))
                    },
                    onSelectTab = { tab -> state.value = state.value.copy(selectedTab = tab) },
                )

            setContent { ReportPage(state.value, actions) }

            onNodeWithTag("show-details").performClick()
            onNodeWithTag("report-tab-CALL_TREE").performClick()
            onNodeWithTag("report-details").assertDoesNotExist()
        }

    @Test
    fun `timeline bottom edge accumulates vertical drag into its height`() =
        runDesktopComposeUiTest(width = 1200, height = 900) {
            val state = mutableStateOf(sampleReportState())
            var density = 1f
            val actions =
                goldenActions().copy(
                    onTimelineHeightDp = { heightDp ->
                        state.value =
                            state.value.copy(
                                workspace = state.value.workspace.copy(timelineHeightDp = heightDp),
                            )
                    },
                )

            setContent {
                density = LocalDensity.current.density
                ReportPage(state.value, actions)
            }

            onNodeWithTag("timeline-divider").performMouseInput {
                moveTo(center)
                press()
                moveBy(Offset(0f, 40f * density))
                moveBy(Offset(0f, 40f * density))
                release()
            }

            waitUntil(timeoutMillis = 1_000) { state.value.workspace.timelineHeightDp == 300 }
            assertEquals(300, state.value.workspace.timelineHeightDp)
        }
}
