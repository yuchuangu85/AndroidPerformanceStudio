package com.androidperformancestudio.presentation

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.CallStackFrame
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FlameGraphRows
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.profileanalysis.FrameImplementation
import com.androidperformancestudio.visualization.FlameViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlameGraphKeyboardNavigationTest {
    @Test
    fun `forward and inverted vertical arrows follow Firefox call relationships`() {
        val forward = snapshot(CallStackDirection.FORWARD)
        val inverted = snapshot(CallStackDirection.INVERTED)

        assertEquals(id(2), target(forward, id(1), Key.DirectionUp))
        assertEquals(id(1), target(forward, id(2), Key.DirectionDown))
        assertEquals(id(1), target(inverted, id(2), Key.DirectionUp))
        assertEquals(id(2), target(inverted, id(1), Key.DirectionDown))
    }

    @Test
    fun `horizontal arrows use eligible siblings and boundaries are not consumed`() {
        val snapshot = snapshot(CallStackDirection.FORWARD)

        assertEquals(id(4), target(snapshot, id(2), Key.DirectionRight))
        assertEquals(id(2), target(snapshot, id(4), Key.DirectionLeft))
        assertNull(FlameGraphKeyboardNavigation.navigate(snapshot, id(2), command(Key.DirectionLeft), viewport()))
        assertNull(FlameGraphKeyboardNavigation.navigate(snapshot, id(1), command(Key.DirectionDown), viewport()))
        assertNull(FlameGraphKeyboardNavigation.navigate(snapshot, id(4), command(Key.DirectionUp), viewport()))
    }

    @Test
    fun `navigation reveals deep target with a clamped row`() {
        val deep = deepSnapshot(20)

        val result =
            FlameGraphKeyboardNavigation.navigate(
                deep,
                id(19),
                FlameGraphNavigationCommand.UP,
                FlameViewport(widthPx = 100, heightPx = 48, scrollRow = 0, rowHeightPx = 16f),
            )

        assertEquals(id(20), result?.targetNodeId)
        assertEquals(17, result?.scrollRow)
    }

    @Test
    fun `only arrow key down events become handled navigation commands`() {
        assertEquals(
            FlameGraphNavigationCommand.UP,
            FlameGraphKeyboardNavigation.commandFor(Key.DirectionUp, KeyEventType.KeyDown),
        )
        assertNull(FlameGraphKeyboardNavigation.commandFor(Key.DirectionUp, KeyEventType.KeyUp))
        assertNull(FlameGraphKeyboardNavigation.commandFor(Key.A, KeyEventType.KeyDown))
    }

    private fun target(
        snapshot: FlameGraphSnapshot,
        selected: FlameCallNodeId,
        key: Key,
    ): FlameCallNodeId? =
        FlameGraphKeyboardNavigation
            .navigate(snapshot, selected, command(key), viewport())
            ?.targetNodeId

    private fun command(key: Key): FlameGraphNavigationCommand =
        requireNotNull(FlameGraphKeyboardNavigation.commandFor(key, KeyEventType.KeyDown))

    private fun viewport() = FlameViewport(widthPx = 100, heightPx = 32, scrollRow = 0, rowHeightPx = 16f)

    private fun snapshot(direction: CallStackDirection): FlameGraphSnapshot {
        // root -> wide, narrow, sibling; wide -> leaf
        val parents = intArrayOf(-1, 0, 0, 0, 1)
        val widths = doubleArrayOf(1.0, 0.6, 0.0005, 0.3, 0.6)
        return snapshot(parents, widths, direction)
    }

    private fun deepSnapshot(size: Int): FlameGraphSnapshot =
        snapshot(
            parentIndexes = IntArray(size) { it - 1 },
            widths = DoubleArray(size) { 1.0 },
            direction = CallStackDirection.FORWARD,
        )

    private fun snapshot(
        parentIndexes: IntArray,
        widths: DoubleArray,
        direction: CallStackDirection,
    ): FlameGraphSnapshot {
        val ids = LongArray(parentIndexes.size) { it + 1L }
        val frames = ids.associateWith(::frame)
        val depths = IntArray(ids.size)
        ids.indices.forEach { index ->
            depths[index] = parentIndexes[index].takeIf { it >= 0 }?.let { depths[it] + 1 } ?: 0
        }
        return FlameGraphSnapshot(
            query = CallStackAnalysisQuery(direction = direction),
            callNodes =
                CallNodeTable(
                    ids = ids,
                    parentIndexes = parentIndexes,
                    frameIds = ids,
                    depths = depths,
                    inclusiveWeights = LongArray(ids.size) { 1 },
                    selfWeights = LongArray(ids.size),
                    sampleCounts = LongArray(ids.size),
                    threadCounts = IntArray(ids.size),
                    categories = List(ids.size) { null },
                    framesById = frames,
                ),
            rows =
                FlameGraphRows(
                    nodeIndexesByRow =
                        depths.indices
                            .groupBy(depths::get)
                            .toSortedMap()
                            .values
                            .map { it.toIntArray() },
                    starts = DoubleArray(ids.size),
                    ends = widths,
                    startsAtBottom = direction == CallStackDirection.FORWARD,
                ),
            totalWeight = 1,
            emptyReason = null,
            invalidTransforms = emptyList(),
        )
    }

    private fun frame(id: Long) =
        CallStackFrame(
            frameId = id,
            functionId = FlameFunctionId(id),
            symbolName = "frame-$id",
            resource = "app.so",
            virtualAddress = id,
            implementation = FrameImplementation.NATIVE,
        )

    private fun id(value: Long) = FlameCallNodeId(value)
}
