package com.androidperformancestudio.arttrace

import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.CallStackFilter
import com.androidperformancestudio.profileanalysis.matchesStackSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the interval-resampling invariant: a method's inclusive (total) time equals the sum of
 * every interval it sits on the stack, its exclusive (self) time equals the sum of intervals where
 * it is the leaf, and call counts come from enter events.
 */
class ArtTraceAnalysisProjectionTest {
    @Test
    fun `interval resampling yields correct self and inclusive durations`() {
        val analysis = analysis()

        val table = ArtTraceCallStackProjector.toCallStackTable(analysis)

        // [A] 10ms -> [A, B] 20ms -> [A] 10ms; final [40,50) has an empty stack and is dropped.
        assertEquals(3, table.stacks.size)
        assertEquals(listOf(1L), table.stacks[0].frameIdsRootToLeaf)
        assertEquals(10_000_000L, table.stacks[0].weight)
        assertEquals(listOf(1L, 2L), table.stacks[1].frameIdsRootToLeaf)
        assertEquals(20_000_000L, table.stacks[1].weight)
        assertEquals(listOf(1L), table.stacks[2].frameIdsRootToLeaf)
        assertEquals(10_000_000L, table.stacks[2].weight)

        val top = MethodTopMethodsReducer.topMethods(table, analysis).associateBy { it.symbolName }
        val activity = assertNotNull(top["android.app.Activity.onCreate"])
        assertEquals(20_000, activity.selfMicros) // 10ms + 10ms
        assertEquals(40_000, activity.totalMicros) // 10 + 20 + 10 ms
        assertEquals(1, activity.callCount)

        val draw = assertNotNull(top["android.view.View.draw"])
        assertEquals(20_000, draw.selfMicros) // the whole 20ms interval
        assertEquals(20_000, draw.totalMicros)
        assertEquals(1, draw.callCount)
    }

    @Test
    fun `flame graph snapshot has duration-scaled weights and no empty reason`() {
        val analysis = analysis()
        val table = ArtTraceCallStackProjector.toCallStackTable(analysis)
        val snapshot = ArtTraceFlameGraphBuilder.build(table)

        assertEquals(40_000_000L, snapshot.totalWeight)
        assertNull(snapshot.emptyReason)
        assertTrue(snapshot.callNodes.size > 0)
        assertTrue(snapshot.rows.rowCount > 0)
    }

    @Test
    fun `method source files reach immutable frames and flame graph snapshots unchanged`() {
        val original = analysis()
        val methods =
            original.methods.toMutableMap().apply {
                this[1L] = getValue(1L).copy(sourceFile = "src/screens/LaunchScreen.kt")
            }
        val table = ArtTraceCallStackProjector.toCallStackTable(original.copy(methods = methods))
        methods.clear()
        val frame = table.frame(1L)

        assertEquals("src/screens/LaunchScreen.kt", frame.sourceFile)
        assertEquals("android/app/Activity", frame.resource)
        assertEquals("android.app.Activity.onCreate", frame.symbolName)
        assertNull(frame.originalSourceFile)
        assertTrue(frame.matchesStackSearch("LAUNCHSCREEN.KT"))
        assertFalse(frame.matchesStackSearch("Activity.java"))

        val snapshot = ArtTraceFlameGraphBuilder.build(table)
        assertEquals(frame, snapshot.callNodes.framesById.getValue(1L))
        assertEquals(
            "View.java",
            snapshot.callNodes.framesById
                .getValue(2L)
                .sourceFile,
        )
    }

    @Test
    fun `source search filters ART intervals with comma AND and per frame OR semantics`() {
        val table = ArtTraceCallStackProjector.toCallStackTable(analysis())
        val query = CallStackAnalysisQuery(searchText = " ACTIVITY.JAVA, VIEW.JAVA ")

        val filtered = CallStackFilter.apply(table, query)

        assertEquals(listOf(table.stacks[1]), filtered.table.stacks)
        assertEquals(
            listOf(1L, 2L),
            filtered.table.stacks
                .single()
                .frameIdsRootToLeaf,
        )
        assertEquals(
            20_000_000L,
            filtered.table.stacks
                .single()
                .weight,
        )
        assertTrue(table.frame(1L).matchesStackSearch("activity.java"))
        assertFalse(table.frame(1L).matchesStackSearch("view.java"))
        assertTrue(table.frame(2L).matchesStackSearch("view.java"))
        assertFalse(table.frame(2L).matchesStackSearch("activity.java"))
        assertEquals(
            20_000_000L,
            ArtTraceFlameGraphBuilder.build(filtered.table, query).totalWeight,
        )
        assertTrue(
            CallStackFilter
                .apply(table, query.copy(searchText = "activity.java,missing.kt"))
                .table.stacks
                .isEmpty(),
        )
    }

    @Test
    fun `empty method source files stay empty without inferring a file from the class`() {
        val original = analysis()
        val methods = original.methods.mapValues { (_, method) -> method.copy(sourceFile = "") }
        val table = ArtTraceCallStackProjector.toCallStackTable(original.copy(methods = methods))

        original.methods.forEach { (id, method) ->
            val frame = table.frame(id)
            assertEquals("", frame.sourceFile)
            assertNull(frame.originalSourceFile)
            assertEquals(method.className, frame.resource)
            assertFalse(frame.matchesStackSearch(method.sourceFile))
        }
        assertTrue(
            CallStackFilter
                .apply(table, CallStackAnalysisQuery(searchText = ".java"))
                .table.stacks
                .isEmpty(),
        )
    }

    @Test
    fun `unknown method fallback has no invented source metadata`() {
        val original = analysis()
        val table = ArtTraceCallStackProjector.toCallStackTable(original.copy(methods = original.methods - 2L))
        val frame = table.frame(2L)

        assertTrue(frame.matchesStackSearch("0x2"))
        assertEquals("", frame.resource)
        assertEquals("", frame.sourceFile)
        assertNull(frame.originalSourceFile)
        assertFalse(frame.matchesStackSearch("View.java"))
        assertEquals(listOf(1L, 2L), table.stacks[1].frameIdsRootToLeaf)
        assertEquals(20_000_000L, table.stacks[1].weight)
    }

    @Test
    fun `empty analysis produces an empty flame graph with a reason`() {
        val analysis =
            ArtTraceAnalysis(
                header = ArtTraceHeader(version = 4, startTimeNanos = 0, clockSource = ArtClockSource.SINGLE),
                methods = emptyMap(),
                threads = emptyMap(),
                events = emptyList(),
                startTimeNanos = 0,
                endTimeNanos = 0,
                warnings = emptyList(),
            )
        val table = ArtTraceCallStackProjector.toCallStackTable(analysis)
        assertEquals(0, table.stacks.size)
        val snapshot = ArtTraceFlameGraphBuilder.build(table)
        assertEquals(0L, snapshot.totalWeight)
        assertNotNull(snapshot.emptyReason)
    }

    private fun analysis(): ArtTraceAnalysis {
        val methods =
            mapOf(
                1L to ArtMethod(1, "android/app/Activity", "onCreate", "(Landroid/os/Bundle;)V", "Activity.java"),
                2L to ArtMethod(2, "android/view/View", "draw", "(Landroid/graphics/Canvas;)V", "View.java"),
            )
        val threads = mapOf(1 to ArtThread(1, "main"))
        val events =
            listOf(
                ArtTraceEvent(threadId = 1, methodId = 1, action = ArtTraceAction.ENTER, timeNanos = 0L),
                ArtTraceEvent(threadId = 1, methodId = 2, action = ArtTraceAction.ENTER, timeNanos = 10_000_000L),
                ArtTraceEvent(threadId = 1, methodId = 2, action = ArtTraceAction.EXIT, timeNanos = 30_000_000L),
                ArtTraceEvent(threadId = 1, methodId = 1, action = ArtTraceAction.EXIT, timeNanos = 40_000_000L),
            )
        return ArtTraceAnalysis(
            header = ArtTraceHeader(version = 4, startTimeNanos = 0, clockSource = ArtClockSource.SINGLE),
            methods = methods,
            threads = threads,
            events = events,
            startTimeNanos = 0L,
            endTimeNanos = 50_000_000L,
            warnings = emptyList(),
        )
    }
}
