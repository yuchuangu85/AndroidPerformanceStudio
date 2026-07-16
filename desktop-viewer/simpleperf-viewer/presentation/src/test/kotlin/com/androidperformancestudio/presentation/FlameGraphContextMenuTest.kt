package com.androidperformancestudio.presentation

import androidx.compose.ui.input.key.Key
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
import com.androidperformancestudio.storage.CallTreeNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlameGraphContextMenuTest {
    @Test
    fun `full target exposes exact transform commands copy and history actions`() {
        val snapshot = recursiveSnapshot()
        val target = FlameCallNodeId(13)

        val entries = FlameGraphContextCommands.entries(snapshot, target, hasTransforms = true)
        val transforms =
            entries.mapNotNull { entry ->
                (entry.command as? FlameGraphContextCommand.ApplyTransform)?.transform
            }

        assertEquals(
            listOf(
                CallStackTransform.MergeFunction(FUNCTION_A),
                CallStackTransform.MergeCallNode(FlameGraphContextCommands.pathFor(snapshot, target)!!),
                CallStackTransform.FocusFunction(FUNCTION_A),
                CallStackTransform.FocusCallNode(FlameGraphContextCommands.pathFor(snapshot, target)!!),
                CallStackTransform.FocusFunctionSelf(FUNCTION_A),
                CallStackTransform.FocusCategory("Graphics"),
                CallStackTransform.CollapseFunctionSubtree(FUNCTION_A),
                CallStackTransform.CollapseResource("/system/liba.so"),
                CallStackTransform.CollapseRecursion(FUNCTION_A),
                CallStackTransform.CollapseDirectRecursion(FUNCTION_A),
                CallStackTransform.DropFunction(FUNCTION_A),
            ),
            transforms,
        )
        assertTrue(entries.any { it.command == FlameGraphContextCommand.Copy("A") })
        assertTrue(entries.any { it.command == FlameGraphContextCommand.Undo })
        assertTrue(entries.any { it.command == FlameGraphContextCommand.Clear })
    }

    @Test
    fun `target facts and direction remove inapplicable commands`() {
        val snapshot = simpleSnapshot(direction = CallStackDirection.INVERTED)
        val entries = FlameGraphContextCommands.entries(snapshot, FlameCallNodeId(21), hasTransforms = false)
        val transforms =
            entries.mapNotNull { entry ->
                (entry.command as? FlameGraphContextCommand.ApplyTransform)?.transform
            }

        assertFalse(transforms.any { it is CallStackTransform.FocusCallNode })
        assertFalse(transforms.any { it is CallStackTransform.MergeCallNode })
        assertFalse(transforms.any { it is CallStackTransform.FocusCategory })
        assertFalse(transforms.any { it is CallStackTransform.CollapseResource })
        assertFalse(transforms.any { it is CallStackTransform.CollapseRecursion })
        assertFalse(transforms.any { it is CallStackTransform.CollapseDirectRecursion })
        assertFalse(entries.any { it.command == FlameGraphContextCommand.Undo })
        assertFalse(entries.any { it.command == FlameGraphContextCommand.Clear })
    }

    @Test
    fun `Firefox transform shortcuts resolve to the same menu command objects`() {
        val snapshot = recursiveSnapshot()
        val target = FlameCallNodeId(13)
        val byShortcut =
            FlameGraphContextCommands
                .entries(snapshot, target, hasTransforms = true)
                .filter { it.shortcut != null }
                .associate { it.shortcut to it.command }

        val keyCases =
            listOf(
                Key.F to true,
                Key.F to false,
                Key.S to true,
                Key.M to true,
                Key.M to false,
                Key.D to false,
                Key.C to true,
                Key.R to false,
                Key.R to true,
                Key.C to false,
                Key.G to false,
            )
        keyCases.forEach { (key, shiftPressed) ->
            val expectedShortcut = FlameGraphContextCommands.shortcutLabel(key, shiftPressed)
            assertEquals(
                byShortcut[expectedShortcut],
                FlameGraphContextCommands.commandForShortcut(snapshot, target, key, shiftPressed),
            )
        }
        assertNull(FlameGraphContextCommands.commandForShortcut(snapshot, target, Key.X, shiftPressed = false))
    }

    @Test
    fun `call tree selection derives ancestor expansion and visible scroll index from shared node id`() {
        val nodes =
            listOf(
                callTreeNode(id = 1, parentId = null, depth = 0),
                callTreeNode(id = 2, parentId = 1, depth = 1),
                callTreeNode(id = 3, parentId = 2, depth = 2),
                callTreeNode(id = 4, parentId = 1, depth = 1),
            )
        val selected = FlameCallNodeId(3)
        val expanded = nodes.selectedPathIds(selected)
        val visible = nodes.visibleNodes(expanded)

        assertEquals(setOf(1L, 2L, 3L), expanded)
        assertEquals(2, visible.selectedNodeIndex(selected))
    }
}

private fun recursiveSnapshot(): FlameGraphSnapshot {
    val frames =
        mapOf(
            1L to frame(1, FUNCTION_A, "A", "/system/liba.so"),
            2L to frame(2, FUNCTION_B, "B", "/system/libb.so"),
        )
    return snapshot(
        direction = CallStackDirection.FORWARD,
        ids = longArrayOf(10, 11, 12, 13),
        parents = intArrayOf(-1, 0, 1, 2),
        frameIds = longArrayOf(1, 2, 1, 1),
        selfWeights = longArrayOf(0, 0, 0, 5),
        categories = listOf("Graphics", "Graphics", "Graphics", "Graphics"),
        frames = frames,
    )
}

private fun simpleSnapshot(direction: CallStackDirection): FlameGraphSnapshot =
    snapshot(
        direction = direction,
        ids = longArrayOf(20, 21),
        parents = intArrayOf(-1, 0),
        frameIds = longArrayOf(1, 2),
        selfWeights = longArrayOf(0, 5),
        categories = listOf(null, null),
        frames =
            mapOf(
                1L to frame(1, FUNCTION_A, "A", ""),
                2L to frame(2, FUNCTION_B, "B", ""),
            ),
    )

@Suppress("LongParameterList")
private fun snapshot(
    direction: CallStackDirection,
    ids: LongArray,
    parents: IntArray,
    frameIds: LongArray,
    selfWeights: LongArray,
    categories: List<String?>,
    frames: Map<Long, CallStackFrame>,
): FlameGraphSnapshot {
    val size = ids.size
    return FlameGraphSnapshot(
        query = CallStackAnalysisQuery(direction = direction),
        callNodes =
            CallNodeTable(
                ids = ids,
                parentIndexes = parents,
                frameIds = frameIds,
                depths = IntArray(size) { it },
                inclusiveWeights = LongArray(size) { 10L },
                selfWeights = selfWeights,
                sampleCounts = LongArray(size) { 1L },
                threadCounts = IntArray(size) { 1 },
                categories = categories,
                framesById = frames,
            ),
        rows =
            FlameGraphRows(
                nodeIndexesByRow = List(size) { row -> intArrayOf(row) },
                starts = DoubleArray(size),
                ends = DoubleArray(size) { 1.0 },
                startsAtBottom = true,
            ),
        totalWeight = 10,
        emptyReason = null,
        invalidTransforms = emptyList(),
    )
}

private fun frame(
    frameId: Long,
    functionId: FlameFunctionId,
    symbol: String,
    resource: String,
): CallStackFrame =
    CallStackFrame(
        frameId = frameId,
        functionId = functionId,
        symbolName = symbol,
        resource = resource,
        virtualAddress = frameId,
        implementation = FrameImplementation.NATIVE,
    )

private fun callTreeNode(
    id: Long,
    parentId: Long?,
    depth: Int,
): CallTreeNode =
    CallTreeNode(
        id = id,
        parentId = parentId,
        depth = depth,
        symbolName = "node-$id",
        filePath = "",
        inclusiveWeight = 1,
        exclusiveWeight = 1,
        sampleCount = 1,
        threadCount = 1,
    )

private val FUNCTION_A = FlameFunctionId(100)
private val FUNCTION_B = FlameFunctionId(200)
