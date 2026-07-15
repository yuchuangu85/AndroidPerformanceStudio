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

class LegacyFlameGraphAdapterTest {
    @Test
    fun `resolves only selected node metadata and ancestor path from snapshot truth`() {
        val query = CallStackAnalysisQuery(searchText = "child")
        val nodes =
            CallNodeTable(
                ids = longArrayOf(10, 20, 30),
                parentIndexes = intArrayOf(-1, 0, 0),
                frameIds = longArrayOf(1, 2, 999),
                depths = intArrayOf(0, 1, 1),
                inclusiveWeights = longArrayOf(8, 3, 2),
                selfWeights = longArrayOf(3, 3, 2),
                sampleCounts = longArrayOf(3, 1, 1),
                threadCounts = intArrayOf(1, 1, 1),
                categories = listOf("User", "User", "Unrelated"),
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

        val selected = snapshot.resolveLegacyNode(FlameCallNodeId(20))

        assertEquals(20L, selected?.id)
        assertEquals("child", selected?.symbolName)
        assertEquals(listOf("root", "child"), selected?.path)
        assertEquals(3L, selected?.inclusiveWeight)
        assertEquals(3L, selected?.exclusiveWeight)
        assertEquals(null, snapshot.resolveLegacyNode(FlameCallNodeId(99)))
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
