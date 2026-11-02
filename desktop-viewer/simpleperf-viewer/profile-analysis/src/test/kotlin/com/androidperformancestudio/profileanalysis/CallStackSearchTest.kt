package com.androidperformancestudio.profileanalysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallStackSearchTest {
    @Test
    fun `legacy positional frame constructors default source metadata to absent`() {
        val frame = CallStackFrame(1, FlameFunctionId(1), "tick", "app.apk", 16, FrameImplementation.MANAGED)
        val collapsed =
            CallStackFrame(1, FlameFunctionId(1), "tick", "app.apk", 16, FrameImplementation.MANAGED, "app.apk")

        listOf(frame, collapsed).forEach {
            assertNull(it.sourceFile)
            assertNull(it.originalSourceFile)
            assertFalse(it.matchesStackSearch("tick.kt"))
        }
        assertEquals("app.apk", collapsed.collapsedResource)
    }

    @Test
    fun `single term matches symbol resource generated source or original source ignoring case`() {
        val frame = sourceFrame(1)

        listOf("TICK", "APP.APK", "/GENERATED/", "BUNDLE.JS", "WEBPACK:///", "SCREEN.TSX").forEach { term ->
            assertTrue(frame.matchesStackSearch(term), term)
        }
        listOf("missing.kt", "bundle.js,Screen.tsx", "bundle.*", "app.apk/generated").forEach { term ->
            assertFalse(frame.matchesStackSearch(term), term)
        }
    }

    @Test
    fun `source metadata is optional and does not suppress existing field matches`() {
        val originalOnly = sourceFrame(1).copy(sourceFile = null)
        val generatedOnly = sourceFrame(2).copy(originalSourceFile = null)

        assertTrue(originalOnly.matchesStackSearch("screen.tsx"))
        assertFalse(originalOnly.matchesStackSearch("bundle.js"))
        assertTrue(generatedOnly.matchesStackSearch("bundle.js"))
        assertFalse(generatedOnly.matchesStackSearch("screen.tsx"))
        listOf(originalOnly, generatedOnly).forEach {
            assertTrue(it.matchesStackSearch("tick"))
            assertTrue(it.matchesStackSearch("app.apk"))
        }
    }

    @Test
    fun `comma terms retain stacks by AND while individual frames match by OR`() {
        val generated = sourceFrame(1).copy(originalSourceFile = null)
        val original = sourceFrame(2).copy(symbolName = "render", resource = "libui.so", sourceFile = null)
        val table =
            CallStackTable(
                framesById = mapOf(1L to generated, 2L to original),
                stacks = listOf(stack(1, listOf(1, 2)), stack(2, listOf(1)), stack(3, listOf(2))),
            )
        val search = " BUNDLE.JS, SCREEN.TSX "
        val terms = parseFlameSearchTerms(search)

        val filtered = CallStackFilter.apply(table, CallStackAnalysisQuery(searchText = search))

        assertEquals(listOf(1L), filtered.table.stacks.map { it.sampleId })
        assertEquals(1, filtered.afterSearchCount)
        assertTrue(terms.any(generated::matchesStackSearch))
        assertTrue(terms.any(original::matchesStackSearch))
        assertFalse(terms.all(generated::matchesStackSearch))
        assertFalse(terms.all(original::matchesStackSearch))
    }

    @Test
    fun `missing source term drops a stack even when other fields match`() {
        val frame = sourceFrame(1)
        val table = CallStackTable(mapOf(1L to frame), listOf(stack(1, listOf(1))))

        val filtered =
            CallStackFilter.apply(table, CallStackAnalysisQuery(searchText = "tick,app.apk,screen.tsx,missing.kt"))

        assertTrue(filtered.table.stacks.isEmpty())
        assertEquals(0, filtered.afterSearchCount)
    }

    @Test
    fun `source search cannot retain frames removed by implementation filtering`() {
        val managed = sourceFrame(1)
        val native =
            sourceFrame(2).copy(
                symbolName = "render",
                resource = "libui.so",
                implementation = FrameImplementation.NATIVE,
                sourceFile = "native/render.cpp",
                originalSourceFile = null,
            )
        val table = CallStackTable(mapOf(1L to managed, 2L to native), listOf(stack(1, listOf(1, 2))))

        listOf("bundle.js", "screen.tsx", "render.cpp,screen.tsx").forEach { term ->
            val filtered =
                CallStackFilter.apply(
                    table,
                    CallStackAnalysisQuery(searchText = term, implementation = ImplementationFilter.NATIVE),
                )

            assertTrue(filtered.table.stacks.isEmpty(), term)
            assertEquals(1, filtered.afterImplementationCount)
            assertEquals(0, filtered.afterSearchCount)
        }
        val filtered =
            CallStackFilter.apply(
                table,
                CallStackAnalysisQuery(searchText = "RENDER.CPP", implementation = ImplementationFilter.NATIVE),
            )
        assertEquals(
            listOf(2L),
            filtered.table.stacks
                .single()
                .frameIdsRootToLeaf,
        )
    }

    @Test
    fun `source metadata survives frame copy and immutable table projection`() {
        val frame = sourceFrame(1)
        val copied = frame.copy(symbolName = "updated")
        val frames = mutableMapOf(1L to copied)
        val table = CallStackTable(frames, listOf(stack(1, listOf(1))))
        frames.clear()

        val filtered = CallStackFilter.apply(table, CallStackAnalysisQuery(searchText = "bundle.js,screen.tsx"))

        assertEquals(frame.sourceFile, copied.sourceFile)
        assertEquals(frame.originalSourceFile, copied.originalSourceFile)
        assertEquals(copied, filtered.table.frame(1))
        assertEquals(listOf(1L), filtered.table.stacks.map { it.sampleId })
        assertEquals(frame, frame.copy())
        assertNull(copied.copy(sourceFile = null).sourceFile)
        assertNull(copied.copy(originalSourceFile = null).originalSourceFile)
    }

    private fun sourceFrame(id: Long) =
        CallStackFrame(
            frameId = id,
            functionId = FlameFunctionId(id),
            symbolName = "tick",
            resource = "app.apk",
            virtualAddress = id,
            implementation = FrameImplementation.MANAGED,
            sourceFile = "/generated/bundle.js",
            originalSourceFile = "webpack:///src/Screen.tsx",
        )

    private fun stack(
        id: Long,
        frameIds: List<Long>,
    ) = WeightedCallStack(id, id, 1, "thread", null, null, frameIds)
}
