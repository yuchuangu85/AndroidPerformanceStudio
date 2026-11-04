package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.presentation.MemoryProfilerState
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapSummary
import com.androidperformancestudio.ui.UiAiContext
import com.androidperformancestudio.ui.UiAiEvidence
import com.androidperformancestudio.ui.boundedAiContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MemoryAiContextTest {
    @Test
    fun `canonical selection preserves metrics missing states and rejects stale ID`() {

        val state = MemoryProfilerState(activeSnapshotId="snapshot-1",summary=HeapSummary(4,32,1),classes=listOf(ClassStats("Example",4,32,null)),displayedClasses=listOf(ClassStats("Example",4,32,null)),selectedClassName="Example")
        val context = memoryAiContext(state)!!
        assertTrue(context.currentSelection)
        assertTrue("\"retainedBytes\":null" in context.evidence.single().payloadJson)
        assertTrue("\"shallowBytes\":32" in context.evidence.single().payloadJson)
        assertEquals(null,memoryAiContext(state.copy(selectedClassName="stale")))
        assertFalse(context.evidence.single().id==memoryAiContext(state.copy(activeSnapshotId="snapshot-2"))!!.evidence.single().id)
    }

    @Test
    fun `bounded context rejects oversized UTF8 payload and summary`() {
        val payloadTooLarge = UiAiContext("memory", "title", false, listOf(UiAiEvidence("id", "kind", "ok", "x".repeat(8 * 1024 + 1))))
        val summaryTooLarge = UiAiContext("memory", "title", false, listOf(UiAiEvidence("id", "kind", "\u4e2d".repeat(700), "{}")))
        assertNull(boundedAiContext(payloadTooLarge))
        assertNull(boundedAiContext(summaryTooLarge))
    }

    @Test
    fun `limits apply to total evidence and preserve the canonical context at the boundary`() {
        val evidence = UiAiEvidence("id", "kind", "s".repeat(1024), "x".repeat(4096))
        val context = UiAiContext("memory", "title", false, listOf(evidence, evidence.copy(id = "other")))
        assertSame(context, boundedAiContext(context))
        assertNull(boundedAiContext(context.copy(evidence = context.evidence + evidence.copy(id = "extra"))))
        assertNull(boundedAiContext(context.copy(evidence = listOf(evidence, evidence.copy(summary = "s".repeat(1025))))))
        assertNull(boundedAiContext(null))
    }

    @Test
    fun `histogram summary keeps eight structured rows and reports omitted classes`() {
        val classes = List(22) { ClassStats("Example$it", 4, 32, null) }
        val state = MemoryProfilerState(
            activeSnapshotId = "snapshot-1",
            summary = HeapSummary(88, 704, 22),
            classes = classes,
            displayedClasses = classes,
        )
        val context = boundedAiContext(memoryAiContext(state))!!
        val payload = context.evidence.single().payloadJson
        assertFalse(context.currentSelection)
        assertTrue("\"includedClassCount\":8" in payload)
        assertTrue("\"omittedClassCount\":14" in payload)
        assertFalse("Example8" in payload)
        assertTrue(payload.toByteArray(Charsets.UTF_8).size <= 8 * 1024)
    }
}
