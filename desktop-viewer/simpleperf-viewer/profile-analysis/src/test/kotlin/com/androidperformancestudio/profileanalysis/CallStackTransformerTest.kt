package com.androidperformancestudio.profileanalysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class CallStackTransformerTest {
    @Test
    fun `all approved transforms rewrite sampled stacks with Firefox semantics`() {
        val fixture = fixture()
        val cases = transformCases(fixture.table.stackSymbols())

        cases.forEach { case ->
            val result = CallStackTransformer.apply(fixture.table, listOf(case.transform))

            assertEquals(case.expected, result.table.stackSymbols(), case.transform.toString())
            assertEquals(listOf(case.transform), result.appliedTransforms, case.transform.toString())
            assertTrue(result.invalidTransforms.isEmpty(), case.transform.toString())
            assertEquals(4, result.inputStackCount, case.transform.toString())
            assertEquals(case.expected.size, result.outputStackCount, case.transform.toString())
        }
    }

    @Test
    fun `ordered transforms compose against the current transformed stacks`() {
        val fixture = fixture()
        val transforms =
            listOf(
                CallStackTransform.MergeFunction(B),
                CallStackTransform.FocusFunction(A),
                CallStackTransform.CollapseFunctionSubtree(D),
            )

        val result = CallStackTransformer.apply(fixture.table, transforms)

        assertEquals(listOf(listOf("A", "C"), listOf("A", "D")), result.table.stackSymbols())
        assertEquals(transforms, result.appliedTransforms)
        assertTrue(result.invalidTransforms.isEmpty())
    }

    @Test
    fun `call node transforms require a full structural path in the current table`() {
        val fixture = fixture()
        val structurallyMissing = CallStackTransform.FocusCallNode(path(listOf(ROOT, A, C)))
        val removedByPriorTransform = CallStackTransform.MergeCallNode(path(listOf(ROOT, A, B)))
        val transforms =
            listOf(
                structurallyMissing,
                CallStackTransform.MergeFunction(A),
                removedByPriorTransform,
                CallStackTransform.FocusCallNode(CallNodePath(emptyList())),
            )

        val result = CallStackTransformer.apply(fixture.table, transforms)

        assertEquals(listOf(CallStackTransform.MergeFunction(A)), result.appliedTransforms)
        assertEquals(
            listOf(
                structurallyMissing,
                removedByPriorTransform,
                CallStackTransform.FocusCallNode(CallNodePath(emptyList())),
            ),
            result.invalidTransforms,
        )
        assertEquals(
            fixture.table
                .stackSymbols()
                .replaceAt(0, listOf("root", "B", "C"))
                .replaceAt(1, listOf("root", "D")),
            result.table.stackSymbols(),
        )
    }

    @Test
    fun `merging a root call node drops a stack when no frames remain`() {
        val frame = frame(100, ROOT, "root", "app")
        val stack = stack(77, listOf(frame.frameId))
        val table = CallStackTable(mapOf(frame.frameId to frame), listOf(stack))

        val result = CallStackTransformer.apply(table, listOf(CallStackTransform.MergeCallNode(path(listOf(ROOT)))))

        assertTrue(result.table.stacks.isEmpty())
        assertEquals(1, result.inputStackCount)
        assertEquals(0, result.outputStackCount)
    }

    @Test
    fun `rewrites preserve every sample field except frames and never mutate input`() {
        val fixture = fixture()
        val originalStacks = fixture.table.stacks.toList()
        val originalFrameIds = originalStacks.map { it.frameIdsRootToLeaf.toList() }

        val result =
            CallStackTransformer.apply(
                fixture.table,
                listOf(CallStackTransform.FocusFunction(A)),
            )

        val original = fixture.table.stacks.first()
        val rewritten = result.table.stacks.first()
        assertEquals(original.copy(frameIdsRootToLeaf = listOf(A_FRAME, B_FRAME, C_FRAME)), rewritten)
        assertNotSame(original, rewritten)
        assertEquals(originalStacks, fixture.table.stacks)
        assertEquals(originalFrameIds, fixture.table.stacks.map { it.frameIdsRootToLeaf })
        assertEquals(fixture.table.framesById, result.table.framesById)
    }

    @Test
    fun `result snapshots applied and invalid transform lists`() {
        val fixture = fixture()
        val valid = CallStackTransform.MergeFunction(B)
        val invalid = CallStackTransform.MergeCallNode(path(listOf(ROOT, D)))
        val transforms = mutableListOf<CallStackTransform>(valid, invalid)

        val result = CallStackTransformer.apply(fixture.table, transforms)
        transforms.clear()

        assertEquals(listOf(valid), result.appliedTransforms)
        assertEquals(listOf(invalid), result.invalidTransforms)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (result.appliedTransforms as MutableList<CallStackTransform>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (result.invalidTransforms as MutableList<CallStackTransform>).clear()
        }
    }

    private data class TransformCase(
        val transform: CallStackTransform,
        val expected: List<List<String>>,
    )

    private fun transformCases(unchanged: List<List<String>>): List<TransformCase> =
        listOf(
            TransformCase(
                CallStackTransform.FocusCallNode(path(listOf(ROOT, A, B))),
                listOf(listOf("B", "C")),
            ),
            TransformCase(
                CallStackTransform.FocusFunction(A),
                listOf(listOf("A", "B", "C"), listOf("A", "D")),
            ),
            TransformCase(CallStackTransform.FocusFunctionSelf(D), listOf(listOf("D"))),
            TransformCase(
                CallStackTransform.MergeCallNode(path(listOf(ROOT, A, B))),
                unchanged.replaceAt(0, listOf("root", "A", "C")),
            ),
            TransformCase(
                CallStackTransform.MergeFunction(R),
                unchanged.replaceAt(2, listOf("root", "leaf")),
            ),
            TransformCase(CallStackTransform.DropFunction(A), unchanged.drop(2)),
            TransformCase(
                CallStackTransform.CollapseResource("libX.so"),
                unchanged.replaceAt(3, listOf("root", "libX:b", "leaf")),
            ),
            TransformCase(
                CallStackTransform.CollapseRecursion(R),
                unchanged.replaceAt(2, listOf("root", "R", "leaf")),
            ),
            TransformCase(
                CallStackTransform.CollapseDirectRecursion(R),
                unchanged.replaceAt(2, listOf("root", "R", "leaf")),
            ),
            TransformCase(
                CallStackTransform.CollapseFunctionSubtree(A),
                unchanged
                    .replaceAt(0, listOf("root", "A"))
                    .replaceAt(1, listOf("root", "A")),
            ),
            TransformCase(
                CallStackTransform.FocusCategory("UI"),
                listOf(unchanged[0], unchanged[2]),
            ),
        )

    private data class Fixture(
        val table: CallStackTable,
    )

    private fun fixture(): Fixture {
        val frames =
            listOf(
                frame(ROOT_FRAME, ROOT, "root", "app"),
                frame(A_FRAME, A, "A", "app"),
                frame(B_FRAME, B, "B", "app"),
                frame(C_FRAME, C, "C", "app"),
                frame(D_FRAME, D, "D", "app"),
                frame(R_OUTER_FRAME, R, "R", "app"),
                frame(R_MIDDLE_FRAME, R, "R", "app"),
                frame(R_INNER_FRAME, R, "R", "app"),
                frame(LIB_X_A_FRAME, LIB_X_A, "libX:a", "libX.so"),
                frame(LIB_X_B_FRAME, LIB_X_B, "libX:b", "libX.so"),
                frame(LEAF_FRAME, LEAF, "leaf", "app"),
            ).associateBy(CallStackFrame::frameId)
        val stacks =
            listOf(
                stack(1, listOf(ROOT_FRAME, A_FRAME, B_FRAME, C_FRAME), category = "UI"),
                stack(2, listOf(ROOT_FRAME, A_FRAME, D_FRAME), category = "Worker"),
                stack(
                    3,
                    listOf(ROOT_FRAME, R_OUTER_FRAME, R_MIDDLE_FRAME, R_INNER_FRAME, LEAF_FRAME),
                    category = "UI",
                ),
                stack(4, listOf(ROOT_FRAME, LIB_X_A_FRAME, LIB_X_B_FRAME, LEAF_FRAME), category = "IO"),
            )
        return Fixture(CallStackTable(frames, stacks))
    }
}

internal fun CallStackTable.stackSymbols(): List<List<String>> =
    stacks.map { stack -> stack.frameIdsRootToLeaf.map { frame(it).symbolName } }

private fun <T> List<T>.replaceAt(
    index: Int,
    replacement: T,
): List<T> = mapIndexed { itemIndex, item -> if (itemIndex == index) replacement else item }

private fun path(functions: List<FlameFunctionId>): CallNodePath = CallNodePath(functions)

internal fun frame(
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
        virtualAddress = frameId * 16,
        implementation = FrameImplementation.NATIVE,
    )

internal fun stack(
    sampleId: Long,
    frameIds: List<Long>,
    category: String? = "UI",
): WeightedCallStack =
    WeightedCallStack(
        sampleId = sampleId,
        timestampNanos = sampleId * 100,
        weight = sampleId * 10,
        threadKey = "thread-$sampleId",
        category = category,
        subcategory = "subcategory-$sampleId",
        frameIdsRootToLeaf = frameIds,
    )

private val ROOT = FlameFunctionId(1)
private val A = FlameFunctionId(2)
private val B = FlameFunctionId(3)
private val C = FlameFunctionId(4)
private val D = FlameFunctionId(5)
private val R = FlameFunctionId(6)
private val LIB_X_A = FlameFunctionId(7)
private val LIB_X_B = FlameFunctionId(8)
private val LEAF = FlameFunctionId(9)

private const val ROOT_FRAME = 101L
private const val A_FRAME = 102L
private const val B_FRAME = 103L
private const val C_FRAME = 104L
private const val D_FRAME = 105L
private const val R_OUTER_FRAME = 106L
private const val R_MIDDLE_FRAME = 107L
private const val R_INNER_FRAME = 108L
private const val LIB_X_A_FRAME = 109L
private const val LIB_X_B_FRAME = 110L
private const val LEAF_FRAME = 111L
