package com.androidperformancestudio.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.capture.CaptureState
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CpuProfilerPendingCardsTest {
    private val session = Path.of("/tmp/aps-session")

    @Test
    fun `cards are visible before any session with empty values`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            setContent { ReportPage(ReportState(), goldenActions()) }

            onNodeWithTag("cpu-kpi-strip-pending").assertExists()
            CPU_BASE_KPI_KINDS.forEach { onNodeWithTag("cpu-kpi-${it.name}").assertExists() }
            // Quality and share cards need recorded evidence, so they never appear as placeholders.
            onNodeWithTag("cpu-kpi-${CpuKpiKind.INVALID_SAMPLES.name}").assertDoesNotExist()
            onNodeWithTag("cpu-kpi-${CpuKpiKind.TOP_FUNCTION_SHARE.name}").assertDoesNotExist()
            assertEquals(CPU_BASE_KPI_KINDS.size, onAllNodes(hasText("—")).fetchSemanticsNodes().size)
            assertEquals(
                CPU_BASE_KPI_KINDS.size,
                onAllNodes(hasText("Waiting for a capture or imported session")).fetchSemanticsNodes().size,
            )
        }

    @Test
    fun `pending cards describe capture and parsing progress`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val capture = mutableStateOf<CaptureState>(CaptureState.Recording(session, "simpleperf record"))
            val report = mutableStateOf(ReportState())
            setContent {
                ReportWorkspace(
                    state = report.value,
                    actions = goldenActions(),
                    style = currentSimpleperfViewerColors(false),
                    modifier = Modifier.fillMaxSize(),
                    captureState = capture.value,
                )
            }

            assertTrue(onAllNodes(hasText("Recording…")).fetchSemanticsNodes().size >= CPU_BASE_KPI_KINDS.size)
            capture.value = CaptureState.Pulling(session)
            waitForIdle()
            assertTrue(onAllNodes(hasText("Pulling perf.data…")).fetchSemanticsNodes().size >= CPU_BASE_KPI_KINDS.size)
            capture.value = CaptureState.Completed(session, session.resolve("perf.data"))
            report.value = ReportState(loadState = ReportLoadState.Loading(session))
            waitForIdle()
            assertEquals(CPU_BASE_KPI_KINDS.size, onAllNodes(hasText("Parsing session…")).fetchSemanticsNodes().size)
        }

    @Test
    fun `cards fill in place with real values when the report becomes ready`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val report = mutableStateOf(ReportState(loadState = ReportLoadState.Loading(session)))
            setContent { ReportPage(report.value, goldenActions()) }

            val pendingBounds = onNodeWithTag("cpu-kpi-${CpuKpiKind.SAMPLES.name}").fetchSemanticsNode().boundsInRoot
            report.value = sampleReportState()
            waitForIdle()

            onNodeWithTag("cpu-kpi-strip-pending").assertDoesNotExist()
            onNodeWithTag("cpu-kpi-strip").assertExists()
            onNodeWithTag("cpu-kpi-${CpuKpiKind.SAMPLES.name}").assert(hasContentDescription("Samples: 120"))
            val readyBounds = onNodeWithTag("cpu-kpi-${CpuKpiKind.SAMPLES.name}").fetchSemanticsNode().boundsInRoot
            assertEquals(pendingBounds.left, readyBounds.left)
            assertEquals(pendingBounds.top, readyBounds.top)
        }

    @Test
    fun `failed sessions keep cards and the existing recovery action`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var closed = false
            val failed =
                ReportState(
                    loadState =
                        ReportLoadState.Failed(
                            session,
                            com.androidperformancestudio.model.StudioError(
                                category = com.androidperformancestudio.model.ErrorCategory.IO,
                                code = "SESSION_BROKEN",
                                message = "perf.data truncated",
                            ),
                        ),
                )
            setContent { ReportPage(failed, goldenActions().copy(onCloseSession = { closed = true })) }

            onNodeWithTag("cpu-kpi-strip-pending").assertExists()
            assertEquals(
                CPU_BASE_KPI_KINDS.size,
                onAllNodes(hasText("Session did not load")).fetchSemanticsNodes().size,
            )
            onNodeWithText("SESSION_BROKEN: perf.data truncated").assertExists()
            onNodeWithText("Back").performClick()
            assertTrue(closed)
        }
}
