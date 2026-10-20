@file:Suppress("LongMethod", "MaxLineLength")

package com.androidperformancestudio.presentation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CpuProfilerWorkspaceTest {
    @Test
    fun `kpis derive only from recorded evidence`() {
        val report = sampleReportState().lastReadyReport!!
        val kpis = report.cpuProfilerKpis()

        assertEquals(120, kpis.sampleCount)
        assertEquals(2, kpis.threadCount)
        assertEquals(10_000, kpis.durationNanos)
        // 120 samples over 10 µs.
        assertEquals(12_000_000.0, kpis.effectiveSampleRateHz!!, 0.001)
        assertEquals("renderFrame", kpis.topFunctionName)
        assertEquals(900.0 / 2_400.0, kpis.topFunctionShare!!, 0.0001)
        assertEquals(
            listOf(
                CpuKpiKind.SAMPLES,
                CpuKpiKind.THREADS,
                CpuKpiKind.CAPTURE_DURATION,
                CpuKpiKind.EFFECTIVE_SAMPLE_RATE,
                CpuKpiKind.INVALID_SAMPLES,
                CpuKpiKind.TOP_FUNCTION_SHARE,
            ),
            kpis.visibleKinds,
        )
    }

    @Test
    fun `quality and share cards are hidden without evidence`() {
        val base = sampleReportState().lastReadyReport!!
        val report =
            base.copy(
                quality = base.quality.copy(lostSampleCount = 0, unknownSymbolSamples = 0),
                topFunctions = emptyList(),
                overview = base.overview.copy(startNanos = null, endNanosInclusive = null),
            )
        val kpis = report.cpuProfilerKpis()

        assertNull(kpis.durationNanos)
        assertNull(kpis.effectiveSampleRateHz)
        assertNull(kpis.topFunctionShare)
        assertFalse(CpuKpiKind.INVALID_SAMPLES in kpis.visibleKinds)
        assertFalse(CpuKpiKind.TOP_FUNCTION_SHARE in kpis.visibleKinds)
    }

    @Test
    fun `tab order follows the commercial layout and keeps every tab`() {
        assertEquals(ReportTab.entries.toSet(), CPU_PROFILER_TAB_ORDER.toSet())
        assertEquals(
            listOf(ReportTab.OVERVIEW, ReportTab.FLAME_GRAPH, ReportTab.CALL_TREE, ReportTab.TOP_FUNCTIONS),
            CPU_PROFILER_TAB_ORDER.take(4),
        )
    }

    @Test
    fun `formatters render readable units`() {
        assertEquals("1.50 s", formatCpuDuration(1_500_000_000))
        assertEquals("12.0 ms", formatCpuDuration(12_000_000))
        assertEquals("4.00 kHz", formatCpuRate(4_000.0))
        assertEquals("250 Hz", formatCpuRate(250.0))
        assertEquals("12.00 MHz", formatCpuRate(12_000_000.0))
        assertEquals(6, cpuKpiColumns(1064, 6))
        assertEquals("37.5%", formatCpuPercent(0.375))
        assertEquals(6, cpuKpiColumns(1400, 6))
        assertEquals(4, cpuKpiColumns(1400, 4))
        assertEquals(2, cpuKpiColumns(400, 6))
    }

    @Test
    fun `workspace shows evidence kpis and never fabricated cpu usage metrics`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            setContent { ReportPage(sampleReportState(), goldenActions()) }

            onNodeWithTag("cpu-kpi-strip").assertExists()
            CpuKpiKind.entries.forEach { onNodeWithTag("cpu-kpi-${it.name}").assertExists() }
            listOf("CPU Usage", "Average CPU", "Energy", "Temperature").forEach { forbidden ->
                assertTrue(
                    onAllNodes(hasText(forbidden, substring = true, ignoreCase = true)).fetchSemanticsNodes().isEmpty(),
                    "Unexpected metric without evidence: $forbidden",
                )
            }
        }

    @Test
    fun `export menu dispatches existing export actions`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val calls = mutableListOf<String>()
            val actions =
                goldenActions().copy(
                    onExportReport = { calls += "report" },
                    onExportPprof = { calls += "pprof" },
                )
            setContent { ReportPage(sampleReportState(), actions) }

            onNodeWithTag("cpu-export").performClick()
            onNodeWithTag("cpu-export-report").performClick()
            onNodeWithTag("cpu-export").performClick()
            onNodeWithTag("cpu-export-pprof").performClick()

            assertEquals(listOf("report", "pprof"), calls)
        }

    @Test
    fun `call tree breadcrumb and inspector expose the selected path`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val initial =
                sampleReportState(ReportTab.CALL_TREE).let {
                    it.copy(workspace = it.workspace.copy(selections = it.workspace.selections.copy(callNodeId = FlameCallNodeId(3))))
                }
            val state = mutableStateOf(initial)
            val focused = mutableListOf<String>()
            val actions =
                goldenActions().copy(
                    onSelectCallNode = { id ->
                        state.value =
                            state.value.copy(
                                workspace =
                                    state.value.workspace.copy(
                                        selections =
                                            state.value.workspace.selections
                                                .copy(callNodeId = id),
                                    ),
                            )
                    },
                    onFocusFunction = { focused += it },
                )
            setContent { ReportPage(state.value, actions) }

            onNodeWithTag("cpu-call-path-breadcrumb").assertExists()
            onNodeWithTag("cpu-breadcrumb-1").assertExists()
            onNodeWithTag("cpu-breadcrumb-2").assertExists()
            onNodeWithTag("inspector-metrics").assertExists()
            onNodeWithTag("inspector-focus-flame").performClick()
            assertEquals(listOf("SkCanvas::draw"), focused)

            onNodeWithTag("cpu-breadcrumb-2").performClick()
            assertEquals(FlameCallNodeId(2), state.value.workspace.selections.callNodeId)
        }

    @Test
    fun `call tree arrow keys move selection`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val initial =
                sampleReportState(ReportTab.CALL_TREE).let {
                    it.copy(workspace = it.workspace.copy(selections = it.workspace.selections.copy(callNodeId = FlameCallNodeId(1))))
                }
            val state = mutableStateOf(initial)
            val actions =
                goldenActions().copy(
                    onSelectCallNode = { id ->
                        state.value =
                            state.value.copy(
                                workspace =
                                    state.value.workspace.copy(
                                        selections =
                                            state.value.workspace.selections
                                                .copy(callNodeId = id),
                                    ),
                            )
                    },
                )
            setContent { ReportPage(state.value, actions) }

            onNodeWithTag("call-tree-row-1").performClick()
            onNodeWithTag("call-tree-list").performKeyInput { pressKey(Key.DirectionDown) }
            waitForIdle()
            assertEquals(FlameCallNodeId(2), state.value.workspace.selections.callNodeId)
            onNodeWithTag("call-tree-list").performKeyInput { pressKey(Key.DirectionUp) }
            waitForIdle()
            assertEquals(FlameCallNodeId(1), state.value.workspace.selections.callNodeId)
        }

    @Test
    fun `top functions table uses the unified columns and keyboard selection`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val state = mutableStateOf(sampleReportState(ReportTab.TOP_FUNCTIONS))
            val actions =
                goldenActions().copy(
                    onSelectTopFunction = { key ->
                        state.value =
                            state.value.copy(
                                workspace =
                                    state.value.workspace.copy(
                                        selections =
                                            state.value.workspace.selections
                                                .copy(topFunctionKey = key),
                                    ),
                            )
                    },
                )
            setContent { ReportPage(state.value, actions) }

            listOf("Library / Path", "Total %").forEach { onNodeWithText(it).assertExists() }
            onNodeWithTag("top-function-table").requestFocus()
            onNodeWithTag("top-function-table").performKeyInput { pressKey(Key.DirectionDown) }
            waitForIdle()
            assertEquals("renderFrame", state.value.workspace.selections.topFunctionKey)
            onNodeWithTag("top-function-row-renderFrame").assertIsSelected()
        }

    @Test
    fun `toolbar exposes import next to capture`() {
        val page = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/presentation/DeviceTargetPage.kt"))
        assertTrue(page.contains("cpu-toolbar-import"))
        assertTrue(page.contains("reportActions.onOpenSession"))
    }
}
