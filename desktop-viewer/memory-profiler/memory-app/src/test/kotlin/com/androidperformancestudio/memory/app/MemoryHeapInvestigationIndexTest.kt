package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapInstance
import com.androidperformancestudio.memory.model.HeapObjectArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryHeapInvestigationIndexTest {
    @Test
    fun `dominator rows retain parent depth and object metadata without a second object map`() {
        val heap =
            HeapDump(
                instances =
                    listOf(
                        HeapInstance(1, 100, "Root", 10),
                        HeapInstance(2, 100, "Child", 20),
                        HeapInstance(4, 100, "Orphan", 40),
                    ),
                objectArrays =
                    listOf(
                        HeapObjectArray(3, 101, "Child[]", 0, emptyList(), 16),
                    ),
                objectRetainedSizes = mapOf(1L to 46L, 2L to 36L, 3L to 16L, 4L to 40L),
                objectImmediateDominators = mapOf(1L to null, 2L to 1L, 3L to 2L, 4L to null),
            )

        val index = MemoryHeapInvestigationIndex(heap)
        assertFalse(index.dominatorsInitialized)

        val rows = index.dominators.associateBy { it.objectId }

        assertTrue(index.dominatorsInitialized)
        assertEquals(0, rows.getValue(1L).depth)
        assertEquals(1, rows.getValue(2L).depth)
        assertEquals(2, rows.getValue(3L).depth)
        assertEquals(0, rows.getValue(4L).depth)
        assertEquals(2L, rows.getValue(3L).parentObjectId)
        assertEquals("Child[]", rows.getValue(3L).className)
        assertEquals(16L, rows.getValue(3L).shallowSize)
    }
}
