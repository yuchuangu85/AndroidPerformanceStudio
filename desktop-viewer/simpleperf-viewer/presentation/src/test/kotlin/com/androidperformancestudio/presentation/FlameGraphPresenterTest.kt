package com.androidperformancestudio.presentation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.androidperformancestudio.profileanalysis.AnalysisTimeRange
import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.CallStackFrame
import com.androidperformancestudio.profileanalysis.CallStackTransform
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FlameGraphRows
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.profileanalysis.FrameImplementation
import com.androidperformancestudio.visualization.FlameGraphIntent
import com.androidperformancestudio.visualization.FlameViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FlameGraphPresenterTest {
    private val snapshot = snapshot()

    @Test
    fun `pointer intents preserve full horizontal scale and map to semantic actions`() {
        assertEquals(FlameGraphPanelAction.Select(null), FlameGraphPresenter.actionFor(FlameGraphIntent.Select(null)))
        assertEquals(
            FlameGraphPanelAction.Select(FlameCallNodeId(2)),
            FlameGraphPresenter.actionFor(FlameGraphIntent.Select(FlameCallNodeId(2))),
        )
        assertEquals(
            FlameGraphPanelAction.OpenDetails(FlameCallNodeId(2)),
            FlameGraphPresenter.actionFor(FlameGraphIntent.OpenDetails(FlameCallNodeId(2))),
        )
        val anchor = Offset(48f, 24f)
        val contextAction =
            FlameGraphPresenter.actionFor(FlameGraphIntent.OpenContextMenu(FlameCallNodeId(2), anchor))
        assertEquals(FlameGraphPanelAction.OpenContextMenu(FlameCallNodeId(2), anchor), contextAction)
    }

    @Test
    fun `transform shortcuts dispatch exact semantic transform actions`() {
        val expected =
            FlameGraphPanelAction.ApplyTransform(
                CallStackTransform.FocusCallNode(
                    requireNotNull(FlameGraphContextCommands.pathFor(snapshot, FlameCallNodeId(2))),
                ),
            )

        assertEquals(
            expected,
            FlameGraphPresenter.keyAction(
                Key.F,
                KeyEventType.KeyDown,
                snapshot,
                FlameCallNodeId(2),
                hasContextMenu = false,
                hasTooltip = false,
                shiftPressed = true,
            ),
        )
        assertNull(
            FlameGraphPresenter.keyAction(
                Key.F,
                KeyEventType.KeyDown,
                snapshot,
                FlameCallNodeId(2),
                hasContextMenu = false,
                hasTooltip = false,
                controlPressed = true,
                shiftPressed = true,
            ),
        )
    }

    @Test
    fun `keyboard opens details dismisses topmost transient and copies function`() {
        assertEquals(
            FlameGraphPanelAction.OpenDetails(FlameCallNodeId(2)),
            FlameGraphPresenter.keyAction(
                Key.Enter,
                KeyEventType.KeyDown,
                snapshot,
                FlameCallNodeId(2),
                hasContextMenu = false,
                hasTooltip = false,
            ),
        )
        assertEquals(
            FlameGraphPanelAction.CloseDetails,
            FlameGraphPresenter.keyAction(
                Key.Escape,
                KeyEventType.KeyDown,
                snapshot,
                FlameCallNodeId(2),
                hasContextMenu = false,
                hasTooltip = false,
                hasDetails = true,
            ),
        )
        assertEquals(
            FlameGraphPanelAction.DismissContextMenu,
            FlameGraphPresenter.keyAction(
                Key.Escape,
                KeyEventType.KeyDown,
                snapshot,
                FlameCallNodeId(2),
                hasContextMenu = true,
                hasTooltip = true,
            ),
        )
        assertEquals(
            FlameGraphPanelAction.DismissTooltip,
            FlameGraphPresenter.keyAction(
                Key.Escape,
                KeyEventType.KeyDown,
                snapshot,
                FlameCallNodeId(2),
                hasContextMenu = false,
                hasTooltip = true,
            ),
        )
        assertEquals(
            FlameGraphPanelAction.Copy("child"),
            FlameGraphPresenter.keyAction(
                Key.C,
                KeyEventType.KeyDown,
                snapshot,
                FlameCallNodeId(2),
                hasContextMenu = false,
                hasTooltip = false,
                controlPressed = true,
            ),
        )
    }

    @Test
    fun `arrows remain controller authoritative and reveal the new selection`() {
        val action =
            FlameGraphPresenter.keyAction(
                Key.DirectionDown,
                KeyEventType.KeyDown,
                snapshot,
                FlameCallNodeId(1),
                hasContextMenu = false,
                hasTooltip = false,
            )
        assertIs<FlameGraphPanelAction.Navigate>(action)
        assertEquals(1, FlameGraphPresenter.scrollRowToReveal(snapshot, FlameCallNodeId(2), FlameViewport(800, 16, 0)))
    }

    @Test
    fun `tooltip is complete and safe for preview and zero totals`() {
        val previewSnapshot =
            snapshot.copy(
                query = snapshot.query.copy(previewRange = AnalysisTimeRange(1, 2)),
                totalWeight = 0,
            )

        val facts = requireNotNull(previewSnapshot.tooltipFacts(FlameCallNodeId(2)))

        assertEquals("child", facts.function)
        assertEquals("Native", facts.category)
        assertEquals(FrameImplementation.NATIVE, facts.implementation)
        assertEquals("libchild.so", facts.resource)
        assertEquals(6, facts.inclusiveWeight)
        assertEquals(6, facts.selfWeight)
        assertEquals(1, facts.sampleCount)
        assertEquals(1, facts.selfSampleCount)
        assertEquals(listOf(FlameGraphTooltipCategorySamples("Native", 1, 1)), facts.categorySamples)
        assertEquals(1, facts.threadCount)
        assertEquals(0.0, facts.percentage)
        assertEquals(6, facts.previewRangeWeight)
    }

    @Test
    fun `tooltip derives Firefox category and sample breakdown from simpleperf frames`() {
        val facts = requireNotNull(simpleperfCategorySnapshot().tooltipFacts(FlameCallNodeId(1)))

        assertEquals("User", facts.category)
        assertEquals(54, facts.sampleCount)
        assertEquals(0, facts.selfSampleCount)
        assertEquals(
            listOf(
                FlameGraphTooltipCategorySamples("User", running = 12, self = 0),
                FlameGraphTooltipCategorySamples("Native", running = 32, self = 0),
                FlameGraphTooltipCategorySamples("JIT", running = 10, self = 0),
            ),
            facts.categorySamples,
        )
    }

    @Test
    fun `missing selection is safe for details and clipboard`() {
        assertNull(
            FlameGraphPresenter.keyAction(
                Key.Enter,
                KeyEventType.KeyDown,
                snapshot,
                null,
                hasContextMenu = false,
                hasTooltip = false,
            ),
        )
        assertNull(FlameGraphPresenter.copyText(snapshot, FlameCallNodeId(404)))
    }

    private fun snapshot(): FlameGraphSnapshot {
        val frames =
            mapOf(
                10L to CallStackFrame(10, FlameFunctionId(10), "root", "libroot.so", 0, FrameImplementation.NATIVE),
                20L to CallStackFrame(20, FlameFunctionId(20), "child", "libchild.so", 8, FrameImplementation.NATIVE),
            )
        return FlameGraphSnapshot(
            query = CallStackAnalysisQuery(direction = CallStackDirection.FORWARD),
            callNodes =
                CallNodeTable(
                    ids = longArrayOf(1, 2),
                    parentIndexes = intArrayOf(-1, 0),
                    frameIds = longArrayOf(10, 20),
                    depths = intArrayOf(0, 4),
                    inclusiveWeights = longArrayOf(10, 6),
                    selfWeights = longArrayOf(4, 6),
                    sampleCounts = longArrayOf(2, 1),
                    threadCounts = intArrayOf(1, 1),
                    categories = listOf("Native", "Native"),
                    framesById = frames,
                ),
            rows =
                FlameGraphRows(
                    listOf(intArrayOf(0), intArrayOf(1)),
                    doubleArrayOf(0.0, 0.0),
                    doubleArrayOf(1.0, 0.6),
                    true,
                ),
            totalWeight = 10,
            emptyReason = null,
            invalidTransforms = emptyList(),
        )
    }

    private fun simpleperfCategorySnapshot(): FlameGraphSnapshot {
        val frames =
            mapOf(
                10L to CallStackFrame(10, FlameFunctionId(10), "root", "app_process", 0, FrameImplementation.NATIVE),
                20L to CallStackFrame(20, FlameFunctionId(20), "user", "base.apk", 0, FrameImplementation.MANAGED),
                30L to CallStackFrame(30, FlameFunctionId(30), "native", "libui.so", 0, FrameImplementation.NATIVE),
                40L to
                    CallStackFrame(
                        40,
                        FlameFunctionId(40),
                        "jit",
                        "[JIT app cache]",
                        0,
                        FrameImplementation.MANAGED,
                    ),
            )
        return FlameGraphSnapshot(
            query = CallStackAnalysisQuery(),
            callNodes =
                CallNodeTable(
                    ids = longArrayOf(1, 2, 3, 4),
                    parentIndexes = intArrayOf(-1, 0, 0, 0),
                    frameIds = longArrayOf(10, 20, 30, 40),
                    depths = intArrayOf(0, 1, 1, 1),
                    inclusiveWeights = longArrayOf(54_000_000, 12_000_000, 32_000_000, 10_000_000),
                    selfWeights = longArrayOf(0, 12_000_000, 32_000_000, 10_000_000),
                    sampleCounts = longArrayOf(54, 12, 32, 10),
                    threadCounts = intArrayOf(1, 1, 1, 1),
                    categories = listOf(null, null, null, null),
                    framesById = frames,
                ),
            rows =
                FlameGraphRows(
                    nodeIndexesByRow = listOf(intArrayOf(0), intArrayOf(1, 2, 3)),
                    starts = doubleArrayOf(0.0, 0.0, 0.22, 0.81),
                    ends = doubleArrayOf(1.0, 0.22, 0.81, 1.0),
                    startsAtBottom = true,
                ),
            totalWeight = 54_000_000,
            emptyReason = null,
            invalidTransforms = emptyList(),
        )
    }
}
