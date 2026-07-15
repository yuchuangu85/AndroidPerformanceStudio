package com.androidperformancestudio.presentation

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.CallStackFrame
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FlameGraphNavigationCommand
import com.androidperformancestudio.profileanalysis.FlameGraphRows
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.profileanalysis.FrameImplementation
import com.androidperformancestudio.visualization.FlameViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlameGraphKeyboardNavigationTest {
    private val keyboard = FlameGraphKeyboardNavigation

    @Test
    fun `forward and inverted vertical arrows follow Firefox call relationships`() {
        assertEquals(
            FlameGraphNavigationCommand.WIDEST_CHILD,
            command(Key.DirectionUp, CallStackDirection.FORWARD),
        )
        assertEquals(
            FlameGraphNavigationCommand.PARENT,
            command(Key.DirectionDown, CallStackDirection.FORWARD),
        )
        assertEquals(
            FlameGraphNavigationCommand.PARENT,
            command(Key.DirectionUp, CallStackDirection.INVERTED),
        )
        assertEquals(
            FlameGraphNavigationCommand.WIDEST_CHILD,
            command(Key.DirectionDown, CallStackDirection.INVERTED),
        )
    }

    @Test
    fun `horizontal arrows map to sibling commands independent of direction`() {
        assertEquals(
            FlameGraphNavigationCommand.NEXT_SIBLING,
            command(Key.DirectionRight, CallStackDirection.FORWARD),
        )
        assertEquals(
            FlameGraphNavigationCommand.PREVIOUS_SIBLING,
            command(Key.DirectionLeft, CallStackDirection.INVERTED),
        )
    }

    @Test
    fun `navigation reveals deep target with a clamped row`() {
        val deep = deepSnapshot(20)

        val scrollRow =
            FlameGraphKeyboardNavigation.scrollRowToReveal(
                deep,
                id(20),
                FlameViewport(widthPx = 100, heightPx = 48, scrollRow = 0, rowHeightPx = 16f),
            )

        assertEquals(17, scrollRow)
    }

    @Test
    fun `only arrow key down events become handled navigation commands`() {
        assertEquals(
            FlameGraphNavigationCommand.WIDEST_CHILD,
            FlameGraphKeyboardNavigation.commandFor(
                Key.DirectionUp,
                KeyEventType.KeyDown,
                CallStackDirection.FORWARD,
            ),
        )
        assertNull(
            FlameGraphKeyboardNavigation.commandFor(
                Key.DirectionUp,
                KeyEventType.KeyUp,
                CallStackDirection.FORWARD,
            ),
        )
        assertNull(
            FlameGraphKeyboardNavigation.commandFor(Key.A, KeyEventType.KeyDown, CallStackDirection.FORWARD),
        )
    }

    private fun command(
        key: Key,
        direction: CallStackDirection,
    ): FlameGraphNavigationCommand = requireNotNull(keyboard.commandFor(key, KeyEventType.KeyDown, direction))

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
