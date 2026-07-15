package com.androidperformancestudio.profileanalysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FirefoxTransformCompatibilityTest {
    @Test
    fun `function identity rather than symbol text selects transform targets`() {
        val first = frame(1, FlameFunctionId(10), "same", "app")
        val second = frame(2, FlameFunctionId(20), "same", "app")
        val leaf = frame(3, FlameFunctionId(30), "leaf", "app")
        val table =
            CallStackTable(
                listOf(first, second, leaf).associateBy(CallStackFrame::frameId),
                listOf(stack(1, listOf(first.frameId, second.frameId, leaf.frameId))),
            )

        val result =
            CallStackTransformer.apply(
                table,
                listOf(CallStackTransform.MergeFunction(first.functionId)),
            )

        assertEquals(listOf(listOf("same", "leaf")), result.table.stackSymbols())
        assertEquals(
            second.frameId,
            result.table.stacks
                .single()
                .frameIdsRootToLeaf
                .first(),
        )
    }

    @Test
    fun `focus function keeps the outermost recursive occurrence while focus self keeps innermost leaf`() {
        val recursive = FlameFunctionId(10)
        val outer = frame(1, recursive, "R-outer", "app")
        val middle = frame(2, FlameFunctionId(20), "middle", "app")
        val inner = frame(3, recursive, "R-inner", "app")
        val table =
            CallStackTable(
                listOf(outer, middle, inner).associateBy(CallStackFrame::frameId),
                listOf(stack(1, listOf(outer.frameId, middle.frameId, inner.frameId))),
            )

        val focused = CallStackTransformer.apply(table, listOf(CallStackTransform.FocusFunction(recursive)))
        val selfFocused = CallStackTransformer.apply(table, listOf(CallStackTransform.FocusFunctionSelf(recursive)))

        assertEquals(listOf(listOf("R-outer", "middle", "R-inner")), focused.table.stackSymbols())
        assertEquals(listOf(listOf("R-inner")), selfFocused.table.stackSymbols())
    }

    @Test
    fun `general recursion removes the outer recursive span while direct recursion requires adjacency`() {
        val root = frame(1, FlameFunctionId(1), "root", "app")
        val outer = frame(2, FlameFunctionId(2), "R-outer", "app")
        val between = frame(3, FlameFunctionId(3), "between", "app")
        val inner = frame(4, FlameFunctionId(2), "R-inner", "app")
        val leaf = frame(5, FlameFunctionId(4), "leaf", "app")
        val table =
            CallStackTable(
                listOf(root, outer, between, inner, leaf).associateBy(CallStackFrame::frameId),
                listOf(stack(1, listOf(1, 2, 3, 4, 5))),
            )

        val collapsed =
            CallStackTransformer.apply(
                table,
                listOf(CallStackTransform.CollapseRecursion(outer.functionId)),
            )
        val directOnly =
            CallStackTransformer.apply(
                table,
                listOf(CallStackTransform.CollapseDirectRecursion(outer.functionId)),
            )

        assertEquals(listOf(listOf("root", "R-inner", "leaf")), collapsed.table.stackSymbols())
        assertEquals(table.stacks, directOnly.table.stacks)
    }

    @Test
    fun `collapse operations retain the innermost frame metadata in each contiguous run`() {
        val root = frame(1, FlameFunctionId(1), "root", "app")
        val resourceOuter = frame(2, FlameFunctionId(2), "resource-outer", "lib.so")
        val resourceInner = frame(3, FlameFunctionId(3), "resource-inner", "lib.so")
        val separator = frame(4, FlameFunctionId(4), "separator", "app")
        val resourceLast = frame(5, FlameFunctionId(5), "resource-last", "lib.so")
        val table =
            CallStackTable(
                listOf(root, resourceOuter, resourceInner, separator, resourceLast)
                    .associateBy(CallStackFrame::frameId),
                listOf(stack(1, listOf(1, 2, 3, 4, 5))),
            )

        val result =
            CallStackTransformer.apply(
                table,
                listOf(CallStackTransform.CollapseResource("lib.so")),
            )

        assertEquals(
            listOf(1L, 3L, 4L, 5L),
            result.table.stacks
                .single()
                .frameIdsRootToLeaf,
        )
        assertEquals(result.table.frame(2).functionId, result.table.frame(3).functionId)
        assertNotEquals(resourceOuter.functionId, result.table.frame(2).functionId)
        assertNotEquals(resourceInner.functionId, result.table.frame(3).functionId)
    }

    @Test
    fun `collapse resource creates one deterministic collision safe identity across branches`() {
        val root = frame(1, FlameFunctionId(1), "root", "app")
        val first = frame(2, FlameFunctionId(20), "first", "lib.so")
        val second = frame(3, FlameFunctionId(30), "second", "lib.so")
        val leaf = frame(4, FlameFunctionId(4), "leaf", "app")
        val frames = listOf(root, first, second, leaf).associateBy(CallStackFrame::frameId)
        val table =
            CallStackTable(
                frames,
                listOf(stack(1, listOf(1, 2, 4)), stack(2, listOf(1, 3, 4))),
            )

        val firstResult = CallStackTransformer.apply(table, listOf(CallStackTransform.CollapseResource("lib.so")))
        val repeatedResult = CallStackTransformer.apply(table, listOf(CallStackTransform.CollapseResource("lib.so")))
        val pseudoFunction = firstResult.table.frame(2).functionId
        val reappliedResult =
            CallStackTransformer.apply(
                firstResult.table,
                listOf(CallStackTransform.CollapseResource("lib.so")),
            )

        assertEquals(pseudoFunction, firstResult.table.frame(3).functionId)
        assertEquals(pseudoFunction, repeatedResult.table.frame(2).functionId)
        assertEquals(pseudoFunction, reappliedResult.table.frame(2).functionId)
        assertEquals(
            first.copy(functionId = pseudoFunction, collapsedResource = "lib.so"),
            firstResult.table.frame(2),
        )
        assertEquals(
            second.copy(functionId = pseudoFunction, collapsedResource = "lib.so"),
            firstResult.table.frame(3),
        )

        val collidingFrame = frame(9, pseudoFunction, "collision", "other.so")
        val collidingTable = table.copy(framesById = frames + (collidingFrame.frameId to collidingFrame))
        val collisionSafe =
            CallStackTransformer.apply(collidingTable, listOf(CallStackTransform.CollapseResource("lib.so")))
        assertNotEquals(pseudoFunction, collisionSafe.table.frame(2).functionId)
        assertEquals(collisionSafe.table.frame(2).functionId, collisionSafe.table.frame(3).functionId)

        val ordered =
            CallStackTransformer.apply(
                table,
                listOf(
                    CallStackTransform.CollapseResource("lib.so"),
                    CallStackTransform.DropFunction(first.functionId),
                ),
            )
        assertEquals(2, ordered.table.stacks.size)
        assertEquals(pseudoFunction, ordered.table.frame(2).functionId)
    }

    @Test
    fun `focus category reconnects matching nodes and preserves aligned metadata`() {
        val graphicsOuter = frame(1, FlameFunctionId(1), "graphics-outer", "app")
        val layout = frame(2, FlameFunctionId(2), "layout", "app")
        val graphicsInner = frame(3, FlameFunctionId(3), "graphics-inner", "app")
        val input =
            stack(
                sampleId = 7,
                frameIds = listOf(1, 2, 3),
                category = "SampleFallback",
                categoriesRootToLeaf = listOf("Graphics", "Layout", "Graphics"),
            )
        val table =
            CallStackTable(
                listOf(graphicsOuter, layout, graphicsInner).associateBy(CallStackFrame::frameId),
                listOf(input),
            )

        val result =
            CallStackTransformer.apply(
                table,
                listOf(
                    CallStackTransform.FocusCategory("Graphics"),
                    CallStackTransform.MergeFunction(graphicsOuter.functionId),
                ),
            )

        val rewritten = result.table.stacks.single()
        assertEquals(listOf(3L), rewritten.frameIdsRootToLeaf)
        assertEquals(listOf("Graphics"), rewritten.categoriesRootToLeaf)
        assertEquals(
            input.copy(
                frameIdsRootToLeaf = listOf(3L),
                categoriesRootToLeaf = listOf("Graphics"),
            ),
            rewritten,
        )
        assertEquals(listOf(1L, 2L, 3L), input.frameIdsRootToLeaf)
        assertEquals(listOf("Graphics", "Layout", "Graphics"), input.categoriesRootToLeaf)

        val noMatch = CallStackTransformer.apply(table, listOf(CallStackTransform.FocusCategory("Network")))
        assertTrue(noMatch.table.stacks.isEmpty())
    }

    @Test
    fun `missing function and category transforms follow Firefox empty versus no-op behavior`() {
        val only = frame(1, FlameFunctionId(1), "only", "app")
        val table = CallStackTable(mapOf(1L to only), listOf(stack(1, listOf(1), category = "UI")))
        val missing = FlameFunctionId(999)

        val emptyingTransforms =
            listOf<CallStackTransform>(
                CallStackTransform.FocusFunction(missing),
                CallStackTransform.FocusFunctionSelf(missing),
                CallStackTransform.FocusCategory("Missing"),
            )
        emptyingTransforms.forEach { transform ->
            val result = CallStackTransformer.apply(table, listOf(transform))

            assertTrue(result.table.stacks.isEmpty(), transform.toString())
            assertEquals(listOf(transform), result.appliedTransforms, transform.toString())
            assertTrue(result.invalidTransforms.isEmpty(), transform.toString())
        }

        val noOpTransforms =
            listOf<CallStackTransform>(
                CallStackTransform.MergeFunction(missing),
                CallStackTransform.DropFunction(missing),
                CallStackTransform.CollapseResource("missing.so"),
                CallStackTransform.CollapseRecursion(missing),
                CallStackTransform.CollapseDirectRecursion(missing),
                CallStackTransform.CollapseFunctionSubtree(missing),
            )
        noOpTransforms.forEach { transform ->
            val result = CallStackTransformer.apply(table, listOf(transform))

            assertEquals(table.stacks, result.table.stacks, transform.toString())
            assertEquals(listOf(transform), result.appliedTransforms, transform.toString())
            assertTrue(result.invalidTransforms.isEmpty(), transform.toString())
        }
    }
}
