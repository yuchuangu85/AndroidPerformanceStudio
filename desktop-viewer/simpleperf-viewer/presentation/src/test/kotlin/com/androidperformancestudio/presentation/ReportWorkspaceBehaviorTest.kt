@file:Suppress("LongMethod", "MaxLineLength")

package com.androidperformancestudio.presentation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.application.DeviceTargetState
import com.androidperformancestudio.application.ReportArtifact
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.application.ReportSessionSummary
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.capture.CaptureState
import com.androidperformancestudio.capture.SamplingParameters
import com.androidperformancestudio.capture.SamplingTemplate
import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.FlameGraphRowProjector
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.storage.CallTreeNode
import com.androidperformancestudio.storage.DataQualitySummary
import com.androidperformancestudio.storage.ProfileOverview
import com.androidperformancestudio.storage.ThreadSummary
import com.androidperformancestudio.storage.TimelineBucket
import com.androidperformancestudio.storage.TopFunction
import java.nio.file.Path
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ReportWorkspaceBehaviorTest {
    @Test
    fun `report stays inside device workspace and left navigation switches the right result`() =
        runDesktopComposeUiTest(width = 1100, height = 760) {
            val reportState = mutableStateOf(sampleReportState())
            val reportActions =
                goldenActions().copy(
                    onSelectTab = { tab -> reportState.value = reportState.value.copy(selectedTab = tab) },
                )

            setContent {
                HomeScreen(
                    state = DeviceTargetState(),
                    captureState = CaptureState.Idle,
                    reportState = reportState.value,
                    actions = emptyDeviceActions(),
                    reportActions = reportActions,
                )
            }

            onNodeWithText("Device & Target").assertExists()
            onNodeWithText("Close report").assertDoesNotExist()
            onNodeWithText("Gallery capture").assertExists()
            onNodeWithText("sessions/gallery-capture").assertExists()
            onNodeWithContentDescription("Overview").assertIsSelected()
            onNodeWithText("Top threads").assertExists()
            onNodeWithText("Timeline").assertDoesNotExist()

            onNodeWithContentDescription("Timeline").performMouseInput { moveTo(center) }
            waitUntil(timeoutMillis = 2_000) {
                onAllNodesWithText("Timeline").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Timeline").assertExists()

            onNodeWithContentDescription("Top functions").performClick()

            onNodeWithContentDescription("Top functions").assertIsSelected()
            onNodeWithText("Search function or library").assertExists()
        }
}

internal fun sampleReportState(selectedTab: ReportTab = ReportTab.OVERVIEW): ReportState {
    val overview =
        ProfileOverview(
            startNanos = 0,
            endNanosInclusive = 10_000,
            sampleCount = 120,
            totalEventWeight = 2_400,
            processCount = 1,
            threadCount = 2,
            eventTypes = listOf("cpu-cycles"),
        )
    val topFunction =
        TopFunction(
            symbolName = "renderFrame",
            filePath = "/system/lib64/libui.so",
            inclusiveWeight = 1_800,
            exclusiveWeight = 900,
            sampleCount = 90,
            threadCount = 2,
        )
    val threads =
        listOf(
            ThreadSummary(7421, 7421, "main", 80, 1_600),
            ThreadSummary(7421, 7440, "RenderThread", 40, 800),
        )
    val report =
        ReportData(
            session =
                ReportSessionSummary(
                    name = "Gallery capture",
                    directory = Path.of("sessions/gallery-capture"),
                    metadata = emptyMap(),
                    artifacts =
                        listOf(
                            ReportArtifact(
                                "perf.data",
                                Path.of("sessions/gallery-capture/perf.data"),
                                true,
                            ),
                        ),
                ),
            sessionOverview = overview,
            overview = overview,
            quality =
                DataQualitySummary(
                    sampleCount = 120,
                    reportedSampleCount = 120,
                    lostSampleCount = 1,
                    unwindErrorSamples = 0,
                    unknownSymbolSamples = 3,
                    emptyStackSamples = 0,
                    unknownRecords = 0,
                    unwindErrors = emptyList(),
                ),
            sessionThreads = threads,
            topThreads = threads,
            topFunctions = listOf(topFunction),
            timeline = listOf(TimelineBucket(0, 10_001, 120, 2_400)),
            callTree =
                listOf(
                    CallTreeNode(1, null, 0, "main", "/system/lib64/libui.so", 2_400, 600, 120, 2),
                    CallTreeNode(2, 1, 1, "renderFrame", "/system/lib64/libui.so", 1_800, 900, 90, 2),
                ),
            flameGraph = emptyFlameGraphSnapshot(),
            diagnostics = emptyList(),
        )
    return ReportState(loadState = ReportLoadState.Ready(report), lastReadyReport = report, selectedTab = selectedTab)
}

private fun emptyDeviceActions() =
    DeviceTargetActions(
        onRefresh = {},
        onSelectDevice = {},
        onSearch = {},
        onSelectPackage = {},
        onSelectProcess = {},
        onSelectThread = {},
        onContinue = {},
        onBack = {},
        onSelectTemplate = { _: SamplingTemplate -> },
        onUpdateSamplingParameters = { _: SamplingParameters -> },
        onStartCapture = {},
        onStopCapture = {},
        onCancelCapture = {},
    )

private fun emptyFlameGraphSnapshot(): FlameGraphSnapshot {
    val query = CallStackAnalysisQuery()
    val nodes =
        CallNodeTable(
            ids = longArrayOf(),
            parentIndexes = intArrayOf(),
            frameIds = longArrayOf(),
            depths = intArrayOf(),
            inclusiveWeights = longArrayOf(),
            selfWeights = longArrayOf(),
            sampleCounts = longArrayOf(),
            threadCounts = intArrayOf(),
            categories = emptyList(),
            framesById = emptyMap(),
        )
    return FlameGraphSnapshot(
        query = query,
        callNodes = nodes,
        rows = FlameGraphRowProjector.project(nodes, query.direction),
        totalWeight = 0,
        emptyReason = null,
        invalidTransforms = emptyList(),
    )
}
