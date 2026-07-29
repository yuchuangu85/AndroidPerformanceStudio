@file:Suppress("LongMethod", "MaxLineLength")

package com.androidperformancestudio.presentation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
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
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameGraphRowProjector
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.storage.CallTreeNode
import com.androidperformancestudio.storage.DataQualitySummary
import com.androidperformancestudio.storage.PanelProjection
import com.androidperformancestudio.storage.ProfileOverview
import com.androidperformancestudio.storage.ThreadSummary
import com.androidperformancestudio.storage.ThreadTimelineTrack
import com.androidperformancestudio.storage.TimelineBucket
import com.androidperformancestudio.storage.TopFunction
import com.androidperformancestudio.ui.macOsDeviceTargetStyle
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ReportWorkspaceBehaviorTest {
    @Test
    fun `top function rows reserve only one third of their former vertical padding`() =
        runDesktopComposeUiTest(width = 1100, height = 760) {
            var densityFactor = 1f
            val baseState = sampleReportState(ReportTab.TOP_FUNCTIONS)
            val baseReport = requireNotNull(baseState.lastReadyReport)
            val firstFunction = baseReport.topFunctions.single()
            val report =
                baseReport.copy(
                    topFunctions = listOf(firstFunction, firstFunction.copy(symbolName = "drawFrame")),
                )
            val state =
                baseState.copy(
                    loadState = ReportLoadState.Ready(report),
                    lastReadyReport = report,
                )
            setContent {
                densityFactor = LocalDensity.current.density
                ReportPage(
                    state = state,
                    actions = goldenActions(),
                )
            }

            val firstRow = onNodeWithTag("top-function-row-renderFrame").fetchSemanticsNode().boundsInRoot
            val secondRow = onNodeWithTag("top-function-row-drawFrame").fetchSemanticsNode().boundsInRoot

            assertTrue(
                firstRow.height <= 42f * densityFactor + 1f,
                "Top function row remains too tall: ${firstRow.height} px at density $densityFactor",
            )
            assertTrue(
                secondRow.top - firstRow.bottom <= densityFactor + 1f,
                "Top function row gap remains too large: ${secondRow.top - firstRow.bottom} px",
            )
        }

    @Test
    fun `report stays inside device workspace and navigation exposes honest pending panels`() =
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

            onNodeWithText("Device & Target").assertDoesNotExist()
            onNodeWithText("Close report").assertDoesNotExist()
            onNodeWithText("Gallery capture").assertExists()
            onNodeWithText("sessions/gallery-capture").assertExists()
            onNodeWithContentDescription("Overview").assertIsSelected()
            onNodeWithText("Top threads").assertExists()
            onNodeWithTag("report-timeline").assertExists()

            onNodeWithContentDescription("Stack chart").performClick()
            onNodeWithContentDescription("Stack chart").assertIsSelected()
            onNodeWithTag("stack-chart-panel").assertExists()

            onNodeWithContentDescription("Top functions").performClick()

            onNodeWithContentDescription("Top functions").assertIsSelected()
            onNodeWithText("Filter Stacks").assertExists()
        }

    @Test
    fun `call tree matches Firefox columns ordering expansion and toggle behavior`() =
        runDesktopComposeUiTest(width = 1100, height = 760) {
            var selectedNode: FlameCallNodeId? = null
            setContent {
                ReportPage(
                    state = sampleReportState(ReportTab.CALL_TREE),
                    actions = goldenActions().copy(onSelectCallNode = { selectedNode = it }),
                )
            }

            onNodeWithText("Total").assertExists()
            onNodeWithText("Self").assertExists()
            onNodeWithText("91.7%").assertExists()
            onNodeWithText("2,200").assertExists()
            onNodeWithText("Invert Call Stack").assertExists()
            onNodeWithText("Filter Stacks").assertExists()
            assertEquals(2, onAllNodesWithText("/system/lib64/libui.so").fetchSemanticsNodes().size)
            onNodeWithTag("call-tree-row-1").performClick()
            assertEquals(FlameCallNodeId(1), selectedNode)
            onNodeWithText("renderFrame").assertExists()

            onNodeWithContentDescription("Collapse main").performClick()

            onNodeWithText("renderFrame").assertDoesNotExist()
            onNodeWithContentDescription("Expand main").assertExists()
        }

    @Test
    fun `Firefox call tree expands and sorts the heaviest path`() {
        val nodes = sampleReportState().lastReadyReport!!.callTree

        assertEquals(setOf(1L, 2L, 3L), nodes.firefoxInitialExpandedIds())
        assertEquals(3L, nodes.firefoxInitialSelectedId())
        assertEquals(
            listOf(1L, 2L, 3L, 4L, 5L, 6L),
            nodes.visibleNodes(nodes.firefoxInitialExpandedIds()).map(CallTreeNode::id),
        )
        assertEquals("91.7%", nodes.first().firefoxTotalPercent(totalWeight = 2_400))
        assertEquals("2,200", nodes.first().inclusiveWeight.firefoxWeight())
        assertEquals("Total (samples)", listOf("samples").firefoxTotalColumnLabel())
        assertEquals("Total", listOf("cpu-cycles").firefoxTotalColumnLabel())
    }

    @Test
    fun `expanding a visible call tree row keeps its viewport position`() =
        runDesktopComposeUiTest(width = 1100, height = 760) {
            setContent {
                ReportPage(
                    state = scrollingCallTreeState(),
                    actions = goldenActions(),
                )
            }

            onNodeWithTag("call-tree-list").performScrollToIndex(71)
            waitForIdle()
            val target = onNodeWithText("root-70")
            val before = target.fetchSemanticsNode().boundsInRoot.top

            onNodeWithContentDescription("Expand root-70").performClick()
            waitForIdle()

            onNodeWithText("child-70").assertExists()
            val after = onNodeWithText("root-70").fetchSemanticsNode().boundsInRoot.top
            assertTrue(kotlin.math.abs(after - before) <= 1f, "expanded row moved from $before to $after")
        }

    @Test
    fun `Firefox timeline exposes aligned process and thread tracks with selection and range commit`() =
        runDesktopComposeUiTest(width = 1100, height = 760) {
            var selectedThreads = emptySet<Int>()
            var committedRange: Pair<Long?, Long?>? = null
            val state = sampleReportState()
            setContent {
                TimelineReport(
                    state = state,
                    report = requireNotNull(state.lastReadyReport),
                    actions =
                        goldenActions().copy(
                            onThreads = { selectedThreads = it },
                            onTimeRange = { start, end -> committedRange = start to end },
                        ),
                    style = macOsDeviceTargetStyle(dark = false),
                )
            }

            onNodeWithText("2 / 2 tracks").assertExists()
            onNodeWithText("main").assertExists()
            onNodeWithText("RenderThread").assertExists()
            onNodeWithText("(7421)").assertExists()
            onNodeWithText("(7440)").assertExists()
            onNodeWithContentDescription("Timeline track main (7421)").assertIsSelected()

            onNodeWithText("RenderThread").performClick()
            assertEquals(setOf(7440), selectedThreads)

            onNodeWithContentDescription("Thread activity graph main").performMouseInput {
                moveTo(centerLeft)
                press()
                moveTo(centerRight)
                release()
            }
            assertEquals(true, committedRange?.let { (start, end) -> start != null && end != null && start < end })
        }
}

private fun scrollingCallTreeState(): ReportState {
    val base = sampleReportState(ReportTab.CALL_TREE)
    val nodes =
        buildList {
            repeat(90) { index ->
                val rootId = index * 2L + 1L
                val childId = rootId + 1L
                val weight = 10_000L - index
                add(CallTreeNode(rootId, null, 0, "root-$index", "/lib/root-$index.so", weight, 1, 1, 1))
                add(CallTreeNode(childId, rootId, 1, "child-$index", "/lib/child-$index.so", weight, weight, 1, 1))
            }
        }
    val report = base.lastReadyReport!!.copy(callTree = nodes)
    return base.copy(
        loadState = ReportLoadState.Ready(report),
        lastReadyReport = report,
        workspace =
            base.workspace.copy(
                selections = base.workspace.selections.copy(callNodeId = FlameCallNodeId(1)),
            ),
    )
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
                    CallTreeNode(1, null, 0, "main", "/system/lib64/libui.so", 2_200, 100, 110, 2),
                    CallTreeNode(2, 1, 1, "renderFrame", "/system/lib64/libui.so", 1_600, 250, 80, 2),
                    CallTreeNode(3, 2, 2, "SkCanvas::draw", "/system/lib64/libhwui.so", 900, 700, 45, 2),
                    CallTreeNode(4, 2, 2, "eglSwapBuffers", "/system/lib64/libEGL.so", 700, 650, 35, 1),
                    CallTreeNode(5, 1, 1, "MessageQueue::next", "/system/lib64/libutils.so", 600, 500, 30, 1),
                    CallTreeNode(6, null, 0, "__schedule", "[kernel.kallsyms]", 200, 200, 10, 2),
                ),
            flameGraph = emptyFlameGraphSnapshot(),
            stackChart = PanelProjection.Failed("TEST_STACK_CHART_UNAVAILABLE", "Fixture has no stack chart"),
            markers = PanelProjection.Failed("TEST_MARKERS_UNAVAILABLE", "Fixture has no markers"),
            diagnostics = emptyList(),
            timelineTracks =
                listOf(
                    ThreadTimelineTrack(
                        id = "legacy:7421",
                        processId = 7421,
                        threadId = 7421,
                        name = "main",
                        buckets =
                            timelineBuckets(
                                weights = listOf(90, 180, 260, 400, 360, 210, 80, 20),
                                samples = listOf(5, 9, 13, 18, 15, 10, 7, 3),
                            ),
                    ),
                    ThreadTimelineTrack(
                        id = "legacy:7440",
                        processId = 7421,
                        threadId = 7440,
                        name = "RenderThread",
                        buckets =
                            timelineBuckets(
                                weights = listOf(0, 40, 130, 90, 210, 180, 110, 40),
                                samples = listOf(0, 2, 7, 5, 10, 8, 5, 3),
                            ),
                    ),
                ),
        )
    return ReportState(loadState = ReportLoadState.Ready(report), lastReadyReport = report, selectedTab = selectedTab)
}

private fun timelineBuckets(
    weights: List<Long>,
    samples: List<Long>,
): List<TimelineBucket> =
    weights.mapIndexed { index, weight ->
        val start = 10_001L * index / weights.size
        val end = 10_001L * (index + 1) / weights.size
        TimelineBucket(start, end, samples[index], weight)
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
