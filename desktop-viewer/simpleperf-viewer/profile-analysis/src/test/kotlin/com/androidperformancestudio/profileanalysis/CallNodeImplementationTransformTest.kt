package com.androidperformancestudio.profileanalysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CallNodeImplementationTransformTest {
    @Test
    fun `inverted focus matches a visible leaf suffix and keeps callers above the target`() {
        val source = table(listOf(1, 2, 3, 4), listOf(1, 5, 3, 4))
        val transform =
            CallStackTransform.FocusCallNode(path(4, 2), ImplementationFilter.SCRIPT, CallStackDirection.INVERTED)
        val result = CallStackTransformer.apply(source, listOf(transform))
        assertTrue(result.invalidTransforms.isEmpty())
        assertEquals(
            listOf(1L, 2L),
            result.table.stacks
                .single()
                .frameIdsRootToLeaf,
        )
        assertEquals(
            listOf("category1", "category2"),
            result.table.stacks
                .single()
                .categoriesRootToLeaf,
        )
        assertEquals(
            source.stacks.first().weight,
            result.table.stacks
                .single()
                .weight,
        )
    }

    @Test
    fun `inverted focus cannot match a suffix that skips a visible leaf`() {
        val source = table(listOf(1, 2, 3, 4))
        val transform =
            CallStackTransform.FocusCallNode(path(2), ImplementationFilter.SCRIPT, CallStackDirection.INVERTED)
        val result = CallStackTransformer.apply(source, listOf(transform))
        assertEquals(listOf(transform), result.invalidTransforms)
        assertSame(source, result.table)
    }

    @Test
    fun `script focus ignores hidden ancestors but retains raw descendants and sample metadata`() {
        val source = table(listOf(1, 2, 3, 4), listOf(1, 5, 3, 4))
        val transform = CallStackTransform.FocusCallNode(path(2), ImplementationFilter.SCRIPT)

        val result = CallStackTransformer.apply(source, listOf(transform))

        assertEquals(listOf(transform), result.appliedTransforms)
        assertTrue(result.invalidTransforms.isEmpty())
        assertEquals(
            source.stacks.first().copy(
                frameIdsRootToLeaf = listOf(2, 3, 4),
                categoriesRootToLeaf = listOf("category2", "category3", "category4"),
            ),
            result.table.stacks.single(),
        )
        val visible =
            CallStackFilter.apply(result.table, CallStackAnalysisQuery(implementation = ImplementationFilter.SCRIPT))
        assertEquals(
            listOf(2L, 4L),
            visible.table.stacks
                .single()
                .frameIdsRootToLeaf,
        )
    }

    @Test
    fun `script merge removes the raw target only on the matching visible parent path`() {
        val source = table(listOf(1, 2, 3, 4), listOf(1, 5, 3, 4))
        val transform = CallStackTransform.MergeCallNode(path(2, 4), ImplementationFilter.SCRIPT)

        val result = CallStackTransformer.apply(source, listOf(transform))

        assertEquals(listOf(transform), result.appliedTransforms)
        assertTrue(result.invalidTransforms.isEmpty())
        assertEquals(
            source.stacks.first().copy(
                frameIdsRootToLeaf = listOf(1, 2, 3),
                categoriesRootToLeaf = listOf("category1", "category2", "category3"),
            ),
            result.table.stacks.first(),
        )
        assertSame(source.stacks.last(), result.table.stacks.last())
    }

    @Test
    fun `native context includes kernel frames and ignores managed and unknown frames`() {
        val source = table(listOf(2, 1, 7, 6, 4))
        val focus = CallStackTransform.FocusCallNode(path(1, 6), ImplementationFilter.NATIVE)
        val merge = CallStackTransform.MergeCallNode(path(1, 6), ImplementationFilter.NATIVE)

        val focused = CallStackTransformer.apply(source, listOf(focus))
        val merged = CallStackTransformer.apply(source, listOf(merge))

        assertTrue(focused.invalidTransforms.isEmpty())
        assertTrue(merged.invalidTransforms.isEmpty())
        assertEquals(
            listOf(6L, 4L),
            focused.table.stacks
                .single()
                .frameIdsRootToLeaf,
        )
        assertEquals(
            listOf(2L, 1L, 7L, 4L),
            merged.table.stacks
                .single()
                .frameIdsRootToLeaf,
        )
    }

    @Test
    fun `default all and script context still require the complete visible path prefix`() {
        val source = table(listOf(1, 2, 3, 4))
        val transforms =
            listOf(
                CallStackTransform.FocusCallNode(path(2)),
                CallStackTransform.MergeCallNode(path(2)),
                CallStackTransform.FocusCallNode(path(4), ImplementationFilter.SCRIPT),
                CallStackTransform.MergeCallNode(path(4), ImplementationFilter.SCRIPT),
            )

        val result = CallStackTransformer.apply(source, transforms)

        assertEquals(transforms, result.invalidTransforms)
        assertTrue(result.appliedTransforms.isEmpty())
        assertSame(source, result.table)
    }

    @Test
    fun `implementation contextual transforms compose against the current raw stack`() {
        val source = table(listOf(1, 2, 3, 4))
        val transforms =
            listOf(
                CallStackTransform.MergeCallNode(path(2), ImplementationFilter.SCRIPT),
                CallStackTransform.FocusCallNode(path(4), ImplementationFilter.SCRIPT),
            )

        val result = CallStackTransformer.apply(source, transforms)

        assertEquals(transforms, result.appliedTransforms)
        assertTrue(result.invalidTransforms.isEmpty())
        assertEquals(
            listOf(4L),
            result.table.stacks
                .single()
                .frameIdsRootToLeaf,
        )
        assertEquals(
            listOf("category4"),
            result.table.stacks
                .single()
                .categoriesRootToLeaf,
        )
    }

    private fun path(vararg functions: Long): CallNodePath = CallNodePath(functions.map(::FlameFunctionId))

    private fun table(vararg paths: List<Long>): CallStackTable =
        CallStackTable(
            framesById =
                (1L..7L).associateWith { id ->
                    frame(id, FlameFunctionId(id), "frame$id", "fixture").copy(
                        implementation =
                            when (id) {
                                2L, 4L, 5L -> FrameImplementation.MANAGED
                                6L -> FrameImplementation.KERNEL
                                7L -> FrameImplementation.UNKNOWN
                                else -> FrameImplementation.NATIVE
                            },
                    )
                },
            stacks =
                paths.mapIndexed { index, frames ->
                    stack(index + 1L, frames, categoriesRootToLeaf = frames.map { "category$it" })
                },
        )
}
