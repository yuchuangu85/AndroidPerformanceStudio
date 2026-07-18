package com.androidperformancestudio.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.analysis.DiagnosticFinding
import com.androidperformancestudio.analysis.DiagnosticSeverity
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.application.ReportTab
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FirefoxExistingPanelsTest {
    @Test
    fun `overview contains diagnostics without a diagnostics tab`() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            val base = sampleReportState()
            val report =
                requireNotNull(base.lastReadyReport).copy(
                    diagnostics =
                        listOf(
                            DiagnosticFinding(
                                ruleId = "data-quality",
                                title = "Unwind quality needs attention",
                                severity = DiagnosticSeverity.WARNING,
                                conclusion = "Some samples could not be unwound.",
                                evidence = emptyList(),
                                recommendations = listOf("Collect symbols before the next capture."),
                            ),
                        ),
                )
            val state = base.copy(loadState = ReportLoadState.Ready(report), lastReadyReport = report)

            setContent { ReportPage(state, goldenActions()) }

            onNodeWithText("Data quality").assertExists()
            onNodeWithText("Recommendations").assertExists()
            onNodeWithTag("report-tab-DIAGNOSTICS").assertDoesNotExist()
        }

    @Test
    fun `flame graph has one shared filter toolbar`() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            setContent { ReportPage(sampleReportState(ReportTab.FLAME_GRAPH), goldenActions()) }

            assertEquals(1, onAllNodesWithTag("stack-toolbar").fetchSemanticsNodes().size)
        }
}
