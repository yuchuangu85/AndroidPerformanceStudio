package com.androidperformancestudio.profileanalysis

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CallTreeProjectorTest {
    @Test
    fun `forward and inverted projections preserve their own path and self semantics`() {
        val table =
            tableOf(
                stack(1, 4, "main", listOf(ROOT, A, LEAF), listOf("Runtime", "UI", "Graphics")),
                stack(2, 2, "render", listOf(ROOT, B, OTHER_LEAF), listOf("Runtime", "IO", "Network")),
            )

        val forward = CallTreeProjector.project(table, CallStackDirection.FORWARD)
        val inverted = CallTreeProjector.project(table, CallStackDirection.INVERTED)

        assertEquals(listOf("root"), forward.rootNames())
        assertEquals(listOf("leaf", "otherLeaf"), inverted.rootNames())
        assertEquals(4, forward.selfWeight(listOf("root", "A", "leaf")))
        assertEquals(0, forward.selfWeight(listOf("root")))
        assertEquals(4, inverted.selfWeight(listOf("leaf", "A", "root")))
        assertEquals(0, inverted.selfWeight(listOf("leaf")))
        assertEquals("UI", forward.category(listOf("root", "A")))
        assertEquals("Runtime", inverted.category(listOf("leaf", "A", "root")))
    }

    @Test
    fun `roots and siblings use locale independent symbol then function ordering`() {
        val table =
            tableOf(
                stack(1, 1, "one", listOf(ROOT, ZETA)),
                stack(2, 1, "two", listOf(ROOT, LOWER_ALPHA)),
                stack(3, 1, "three", listOf(ROOT, UPPER_ALPHA)),
                stack(4, 1, "four", listOf(BETA)),
            )

        val projection = CallTreeProjector.project(table, CallStackDirection.FORWARD)

        assertEquals(listOf("beta", "root"), projection.rootNames())
        assertEquals(listOf("Alpha", "alpha", "zeta"), projection.childNames("root"))
    }

    @Test
    fun `stable IDs depend on ordered function path rather than sample or input order`() {
        val original =
            tableOf(
                stack(90, 3, "one", listOf(ROOT, A, LEAF)),
                stack(12, 4, "two", listOf(ROOT, B, OTHER_LEAF)),
            )
        val reorderedAndReweighted =
            original.copy(
                stacks =
                    listOf(
                        original.stacks[1].copy(sampleId = 2, weight = 99),
                        original.stacks[0].copy(sampleId = 1, weight = 1),
                    ),
            )

        val first = CallTreeProjector.project(original, CallStackDirection.FORWARD)
        val second = CallTreeProjector.project(reorderedAndReweighted, CallStackDirection.FORWARD)

        assertEquals(first.idFor(listOf(ROOT, A)), second.idFor(listOf(ROOT, A)))
        assertEquals(first.idFor(listOf(ROOT, A, LEAF)), second.idFor(listOf(ROOT, A, LEAF)))
        assertNotEquals(first.idFor(listOf(ROOT, A)), first.idFor(listOf(ROOT, B)))
    }

    @Test
    fun `collision probing is deterministic across input order`() {
        val table =
            tableOf(
                stack(1, 1, "one", listOf(ROOT, A)),
                stack(2, 1, "two", listOf(ROOT, B)),
            )
        val reversed = table.copy(stacks = table.stacks.reversed())

        val first = CallTreeProjector.project(table, CallStackDirection.FORWARD) { 7L }
        val second = CallTreeProjector.project(reversed, CallStackDirection.FORWARD) { 7L }

        assertEquals(first.idFor(listOf(ROOT)), second.idFor(listOf(ROOT)))
        assertEquals(first.idFor(listOf(ROOT, A)), second.idFor(listOf(ROOT, A)))
        assertEquals(first.idFor(listOf(ROOT, B)), second.idFor(listOf(ROOT, B)))
        assertNotEquals(first.idFor(listOf(ROOT)), first.idFor(listOf(ROOT, A)))
        assertNotEquals(first.idFor(listOf(ROOT, A)), first.idFor(listOf(ROOT, B)))
    }

    @Test
    fun `aggregates weights samples threads and dominant per-node categories safely`() {
        val table =
            tableOf(
                stack(1, Long.MAX_VALUE, "main", listOf(ROOT, A), listOf("Runtime", "Zulu")),
                stack(2, 20, "main", listOf(ROOT, A), listOf("Runtime", "Alpha")),
                stack(3, 20, "worker", listOf(ROOT, A), listOf("Runtime", "Alpha")),
                stack(4, -50, "ignored-weight", listOf(ROOT, A), listOf("Other", "Zulu")),
                stack(5, 0, "zero-weight", listOf(ROOT, A), listOf("Other", "Zulu")),
            )

        val projection = CallTreeProjector.project(table, CallStackDirection.FORWARD)
        val root = projection.indexFor(listOf(ROOT))
        val child = projection.indexFor(listOf(ROOT, A))

        assertEquals(Long.MAX_VALUE, projection.inclusiveWeights[root])
        assertEquals(Long.MAX_VALUE, projection.inclusiveWeights[child])
        assertEquals(Long.MAX_VALUE, projection.selfWeights[child])
        assertEquals(5, projection.sampleCounts[child])
        assertEquals(4, projection.threadCounts[child])
        assertEquals("Runtime", projection.categories[root])
        assertEquals("Zulu", projection.categories[child])
    }

    @Test
    fun `duplicate function metadata chooses a deterministic canonical frame`() {
        val duplicateA = frame(200, A, "A older", "z.so")
        val canonicalA = frame(100, A, "A", "a.so")
        val frames =
            listOf(frame(ROOT.value, ROOT, "root"), duplicateA, canonicalA)
                .associateBy(CallStackFrame::frameId)
        val firstStack = stackFromFrameIds(1, 1, "one", listOf(ROOT.value, duplicateA.frameId))
        val secondStack = stackFromFrameIds(1, 1, "one", listOf(ROOT.value, canonicalA.frameId))
        val first = CallStackTable(frames, listOf(firstStack))
        val second = CallStackTable(frames, listOf(secondStack))

        val firstProjection = CallTreeProjector.project(first, CallStackDirection.FORWARD)
        val secondProjection = CallTreeProjector.project(second, CallStackDirection.FORWARD)

        assertEquals(100L, firstProjection.frameId(listOf(ROOT, A)))
        assertEquals(100L, secondProjection.frameId(listOf(ROOT, A)))
        assertEquals(
            firstProjection.idFor(listOf(ROOT, A)),
            secondProjection.idFor(listOf(ROOT, A)),
        )
    }

    @Test
    fun `projected arrays remain stable when callers mutate defensive copies`() {
        val projection = CallTreeProjector.project(tableOf(stack(1, 1, "main", listOf(ROOT, A))))
        val expectedIds = projection.ids
        val expectedParents = projection.parentIndexes

        projection.ids.fill(0)
        projection.parentIndexes.fill(99)

        assertContentEquals(expectedIds, projection.ids)
        assertContentEquals(expectedParents, projection.parentIndexes)
    }

    private fun tableOf(vararg stacks: WeightedCallStack): CallStackTable {
        val frames =
            listOf(
                frame(ROOT.value, ROOT, "root"),
                frame(A.value, A, "A"),
                frame(B.value, B, "B"),
                frame(LEAF.value, LEAF, "leaf"),
                frame(OTHER_LEAF.value, OTHER_LEAF, "otherLeaf"),
                frame(ZETA.value, ZETA, "zeta"),
                frame(LOWER_ALPHA.value, LOWER_ALPHA, "alpha"),
                frame(UPPER_ALPHA.value, UPPER_ALPHA, "Alpha"),
                frame(BETA.value, BETA, "beta"),
            ).associateBy(CallStackFrame::frameId)
        return CallStackTable(frames, stacks.toList())
    }

    private fun stack(
        sampleId: Long,
        weight: Long,
        thread: String,
        functions: List<FlameFunctionId>,
        categories: List<String?>? = null,
    ): WeightedCallStack {
        val frameIds = functions.map(FlameFunctionId::value)
        return stackFromFrameIds(sampleId, weight, thread, frameIds, categories)
    }

    private fun stackFromFrameIds(
        sampleId: Long,
        weight: Long,
        thread: String,
        frameIds: List<Long>,
        categories: List<String?>? = null,
    ) = WeightedCallStack(
        sampleId = sampleId,
        timestampNanos = sampleId,
        weight = weight,
        threadKey = thread,
        category = "sample-fallback",
        subcategory = null,
        frameIdsRootToLeaf = frameIds,
        categoriesRootToLeaf = categories,
    )

    private fun frame(
        frameId: Long,
        functionId: FlameFunctionId,
        symbol: String,
        resource: String = "app.so",
    ) = CallStackFrame(frameId, functionId, symbol, resource, frameId, FrameImplementation.NATIVE)

    private fun CallNodeTable.rootNames(): List<String> {
        val parents = parentIndexes
        val frames = frameIds
        return parents.indices.filter { parents[it] == -1 }.map { framesById.getValue(frames[it]).symbolName }
    }

    private fun CallNodeTable.childNames(parentName: String): List<String> {
        val parents = parentIndexes
        val frames = frameIds
        val parentIndex = parents.indices.single { framesById.getValue(frames[it]).symbolName == parentName }
        return parents.indices.filter { parents[it] == parentIndex }.map { framesById.getValue(frames[it]).symbolName }
    }

    private fun CallNodeTable.indexFor(functions: List<FlameFunctionId>): Int {
        val id = idFor(functions).value
        return ids.indexOf(id)
    }

    private fun CallNodeTable.idFor(functions: List<FlameFunctionId>): FlameCallNodeId {
        val path = CallNodePath(functions)
        return requireNotNull(findByPath(path))
    }

    private fun CallNodeTable.selfWeight(names: List<String>): Long = selfWeights[indexForNames(names)]

    private fun CallNodeTable.category(names: List<String>): String? = categories[indexForNames(names)]

    private fun CallNodeTable.frameId(functions: List<FlameFunctionId>): Long = frameIds[indexFor(functions)]

    private fun CallNodeTable.indexForNames(names: List<String>): Int {
        val parents = parentIndexes
        val frames = frameIds
        var parent = -1
        names.forEach { name ->
            parent =
                parents.indices.single { index ->
                    parents[index] == parent && framesById.getValue(frames[index]).symbolName == name
                }
        }
        return parent
    }

    private companion object {
        val ROOT = FlameFunctionId(1)
        val A = FlameFunctionId(2)
        val B = FlameFunctionId(3)
        val LEAF = FlameFunctionId(4)
        val OTHER_LEAF = FlameFunctionId(5)
        val ZETA = FlameFunctionId(6)
        val LOWER_ALPHA = FlameFunctionId(8)
        val UPPER_ALPHA = FlameFunctionId(7)
        val BETA = FlameFunctionId(9)
    }
}
