package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.presentation.MemoryProfilerState
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
