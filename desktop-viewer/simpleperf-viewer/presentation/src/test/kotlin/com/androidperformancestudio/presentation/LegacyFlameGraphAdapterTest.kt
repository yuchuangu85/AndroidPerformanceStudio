package com.androidperformancestudio.presentation

import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.CallStackFrame
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FlameGraphRowProjector
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.profileanalysis.FrameImplementation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyFlameGraphAdapterTest {
    @Test
    fun `bridges immutable rows and panel query without creating another flame truth`() {
        val query = CallStackAnalysisQuery(searchText = "child")
        val nodes =
            CallNodeTable(
                ids = longArrayOf(10, 20),
                parentIndexes = intArrayOf(-1, 0),
                frameIds = longArrayOf(1, 2),
                depths = intArrayOf(0, 1),
                inclusiveWeights = longArrayOf(8, 3),
                selfWeights = longArrayOf(5, 3),
                sampleCounts = longArrayOf(2, 1),
                threadCounts = intArrayOf(1, 1),
                categories = listOf("User", "User"),
                framesById =
                    mapOf(
                        1L to frame(1, 11, "root"),
                        2L to frame(2, 22, "child"),
                    ),
            )
        val snapshot =
            FlameGraphSnapshot(
                query = query,
                callNodes = nodes,
                rows = FlameGraphRowProjector.project(nodes),
                totalWeight = 8,
                emptyReason = null,
                invalidTransforms = emptyList(),
            )

        val bridged = snapshot.toLegacyNodes(FlameCallNodeId(10))

        assertEquals(listOf(10L, 20L), bridged.map(LegacyPresentationFlameNode::id))
        assertEquals(listOf("root", "child"), bridged.last().path)
        assertEquals(0L, bridged.first().startWeight)
        assertEquals(8L, bridged.first().endWeightExclusive)
        assertTrue(bridged.all(LegacyPresentationFlameNode::highlighted))
    }

    private fun frame(
        frameId: Long,
        functionId: Long,
        symbolName: String,
    ): CallStackFrame =
        CallStackFrame(
            frameId = frameId,
            functionId = FlameFunctionId(functionId),
            symbolName = symbolName,
            resource = "/lib.so",
            virtualAddress = frameId,
            implementation = FrameImplementation.NATIVE,
        )
}
