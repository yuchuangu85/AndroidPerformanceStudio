package com.androidperformancestudio.profileanalysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CallStackFilterTest {
    @Test
    fun `preview range is applied before search and implementation filters`() {
        val fixture = fixture()

        val previewOnly =
            CallStackFilter.apply(
                fixture.table,
                CallStackAnalysisQuery(previewRange = AnalysisTimeRange(10, 20)),
            )
        val allStages =
            CallStackFilter.apply(
                fixture.table,
                CallStackAnalysisQuery(
                    previewRange = AnalysisTimeRange(10, 20),
                    searchText = "libc",
                    implementation = ImplementationFilter.MANAGED,
                ),
            )

        assertEquals(listOf(fixture.renderStack), previewOnly.table.stacks)
        assertEquals(3, allStages.inputStackCount)
        assertEquals(1, allStages.afterPreviewCount)
        assertEquals(0, allStages.afterSearchCount)
        assertEquals(0, allStages.afterImplementationCount)
    }

    @Test
    fun `preview range includes start and excludes end`() {
        val fixture = fixture()

        val result =
            CallStackFilter.apply(
                fixture.table,
                CallStackAnalysisQuery(previewRange = AnalysisTimeRange(20, 30)),
            )

        assertEquals(listOf(fixture.libcStack), result.table.stacks)
        assertEquals(3, result.inputStackCount)
        assertEquals(1, result.afterPreviewCount)
        assertEquals(1, result.afterSearchCount)
        assertEquals(1, result.afterImplementationCount)
    }

    @Test
    fun `comma search uses AND semantics across symbols and resources ignoring case`() {
        val fixture = fixture()

        val crossFrameMatch =
            CallStackFilter.apply(
                fixture.table,
                CallStackAnalysisQuery(searchText = "render,draw"),
            )
        val matching =
            CallStackFilter.apply(
                fixture.table,
                CallStackAnalysisQuery(searchText = " RENDER, GRAPHICS.SO "),
            )
        val missingTerm =
            CallStackFilter.apply(
                fixture.table,
                CallStackAnalysisQuery(searchText = "render,libc"),
            )

        assertEquals(listOf(fixture.renderStack), crossFrameMatch.table.stacks)
        assertEquals(listOf(fixture.renderStack), matching.table.stacks)
        assertEquals(1, matching.afterSearchCount)
        assertTrue(missingTerm.table.stacks.isEmpty())
        assertEquals(0, missingTerm.afterSearchCount)
        assertEquals(0, missingTerm.afterImplementationCount)
    }

    @Test
    fun `blank search terms behave like no search`() {
        val fixture = fixture()

        val result = CallStackFilter.apply(fixture.table, CallStackAnalysisQuery(searchText = " ,  ,"))

        assertEquals(fixture.table.stacks, result.table.stacks)
        assertEquals(3, result.afterSearchCount)
    }

    @Test
    fun `implementation filtering shapes stacks and drops empty stacks`() {
        val fixture = fixture()

        val result =
            CallStackFilter.apply(
                fixture.table,
                CallStackAnalysisQuery(implementation = ImplementationFilter.MANAGED),
            )

        assertEquals(listOf(listOf("managedTick")), result.table.stackSymbols())
        assertEquals(3, result.afterSearchCount)
        assertEquals(1, result.afterImplementationCount)
        assertEquals(
            fixture.managedStack.copy(frameIdsRootToLeaf = listOf(MANAGED_TICK_ID)),
            result.table.stacks.single(),
        )
    }

    @Test
    fun `implementation filtering preserves matching order duplicates and sample metadata`() {
        val fixture = fixture()
        val duplicateStack =
            fixture.renderStack.copy(
                sampleId = 99,
                weight = 42,
                threadKey = "RenderThread",
                category = "Graphics",
                subcategory = "Draw",
                frameIdsRootToLeaf = listOf(ROOT_ID, RENDER_ID, DRAW_ID, RENDER_ID),
            )
        val table = fixture.table.copy(stacks = listOf(duplicateStack))

        val result =
            CallStackFilter.apply(
                table,
                CallStackAnalysisQuery(implementation = ImplementationFilter.NATIVE),
            )

        assertEquals(
            listOf(RENDER_ID, DRAW_ID, RENDER_ID),
            result.table.stacks
                .single()
                .frameIdsRootToLeaf,
        )
        assertEquals(
            duplicateStack.copy(frameIdsRootToLeaf = listOf(RENDER_ID, DRAW_ID, RENDER_ID)),
            result.table.stacks.single(),
        )
    }

    @Test
    fun `implementation filters distinguish native managed kernel and unknown frames`() {
        val frames =
            FrameImplementation.entries
                .mapIndexed { index, implementation ->
                    frame(index.toLong(), implementation.name, "mixed", implementation)
                }.associateBy(CallStackFrame::frameId)
        val stack = stack(1, 10, frames.keys.toList())
        val table = CallStackTable(frames, listOf(stack))

        val expectedByFilter =
            mapOf(
                ImplementationFilter.NATIVE to FrameImplementation.NATIVE,
                ImplementationFilter.MANAGED to FrameImplementation.MANAGED,
                ImplementationFilter.KERNEL to FrameImplementation.KERNEL,
                ImplementationFilter.UNKNOWN to FrameImplementation.UNKNOWN,
            )

        expectedByFilter.forEach { (filter, expected) ->
            val result = CallStackFilter.apply(table, CallStackAnalysisQuery(implementation = filter))

            assertEquals(listOf(listOf(expected.name)), result.table.stackSymbols())
            assertEquals(1, result.afterImplementationCount)
        }
    }

    @Test
    fun `all implementation and absent preview keep the sampled stacks`() {
        val fixture = fixture()

        val result = CallStackFilter.apply(fixture.table, CallStackAnalysisQuery())

        assertEquals(fixture.table.stacks, result.table.stacks)
        assertSame(fixture.table, result.table)
        assertEquals(3, result.inputStackCount)
        assertEquals(3, result.afterPreviewCount)
        assertEquals(3, result.afterSearchCount)
        assertEquals(3, result.afterImplementationCount)
    }

    @Test
    fun `empty and reversed preview ranges deterministically remove all stacks`() {
        val fixture = fixture()

        listOf(AnalysisTimeRange(10, 10), AnalysisTimeRange(20, 10)).forEach { range ->
            val result = CallStackFilter.apply(fixture.table, CallStackAnalysisQuery(previewRange = range))

            assertTrue(result.table.stacks.isEmpty())
            assertEquals(3, result.inputStackCount)
            assertEquals(0, result.afterPreviewCount)
            assertEquals(0, result.afterSearchCount)
            assertEquals(0, result.afterImplementationCount)
        }
    }

    @Test
    fun `empty input returns zero counts without special cases leaking`() {
        val fixture = fixture()
        val empty = fixture.table.copy(stacks = emptyList())

        val result =
            CallStackFilter.apply(
                empty,
                CallStackAnalysisQuery(
                    previewRange = AnalysisTimeRange(0, 100),
                    searchText = "render",
                    implementation = ImplementationFilter.KERNEL,
                ),
            )

        assertTrue(result.table.stacks.isEmpty())
        assertEquals(0, result.inputStackCount)
        assertEquals(0, result.afterPreviewCount)
        assertEquals(0, result.afterSearchCount)
        assertEquals(0, result.afterImplementationCount)
    }

    @Test
    fun `filtering never mutates the input table or sampled stacks`() {
        val fixture = fixture()
        val originalStacks = fixture.table.stacks.toList()
        val originalFrameIds = fixture.table.stacks.map { it.frameIdsRootToLeaf.toList() }

        val result =
            CallStackFilter.apply(
                fixture.table,
                CallStackAnalysisQuery(
                    previewRange = AnalysisTimeRange(0, 100),
                    searchText = "managed",
                    implementation = ImplementationFilter.MANAGED,
                ),
            )

        assertNotSame(fixture.table, result.table)
        assertEquals(originalStacks, fixture.table.stacks)
        assertEquals(originalFrameIds, fixture.table.stacks.map { it.frameIdsRootToLeaf })
        assertEquals(fixture.table.framesById, result.table.framesById)
    }

    private fun CallStackTable.stackSymbols(): List<List<String>> =
        stacks.map { stack -> stack.frameIdsRootToLeaf.map { frame(it).symbolName } }

    private fun fixture(): Fixture {
        val frames =
            listOf(
                frame(ROOT_ID, "root", "app.apk", FrameImplementation.UNKNOWN),
                frame(RENDER_ID, "render", "libgraphics.so", FrameImplementation.NATIVE),
                frame(DRAW_ID, "draw", "libgraphics.so", FrameImplementation.NATIVE),
                frame(LIBC_ID, "memcpy", "libc.so", FrameImplementation.NATIVE),
                frame(MANAGED_TICK_ID, "managedTick", "app.apk", FrameImplementation.MANAGED),
            ).associateBy(CallStackFrame::frameId)
        val renderStack = stack(1, 10, listOf(ROOT_ID, RENDER_ID, DRAW_ID))
        val libcStack = stack(2, 20, listOf(ROOT_ID, LIBC_ID))
        val managedStack = stack(3, 30, listOf(ROOT_ID, MANAGED_TICK_ID))
        return Fixture(
            table = CallStackTable(frames, listOf(renderStack, libcStack, managedStack)),
            renderStack = renderStack,
            libcStack = libcStack,
            managedStack = managedStack,
        )
    }

    private fun frame(
        id: Long,
        symbol: String,
        resource: String,
        implementation: FrameImplementation,
    ) = CallStackFrame(
        frameId = id,
        functionId = FlameFunctionId(id),
        symbolName = symbol,
        resource = resource,
        virtualAddress = id * 16,
        implementation = implementation,
    )

    private fun stack(
        sampleId: Long,
        timestampNanos: Long,
        frameIds: List<Long>,
    ) = WeightedCallStack(
        sampleId = sampleId,
        timestampNanos = timestampNanos,
        weight = sampleId * 10,
        threadKey = "main",
        category = "UI",
        subcategory = "Frame",
        frameIdsRootToLeaf = frameIds,
    )

    private data class Fixture(
        val table: CallStackTable,
        val renderStack: WeightedCallStack,
        val libcStack: WeightedCallStack,
        val managedStack: WeightedCallStack,
    )

    private companion object {
        const val ROOT_ID = 1L
        const val RENDER_ID = 2L
        const val DRAW_ID = 3L
        const val LIBC_ID = 4L
        const val MANAGED_TICK_ID = 5L
    }
}
