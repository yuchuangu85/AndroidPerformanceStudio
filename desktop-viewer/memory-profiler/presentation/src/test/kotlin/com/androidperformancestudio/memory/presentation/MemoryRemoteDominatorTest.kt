package com.androidperformancestudio.memory.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryRemoteDominatorTest {
    @Test
    fun `a missing or empty child page does not prove that a remote parent is a leaf`() {
        val unknown = MemoryProfilerState(remoteDominators = true)
        assertTrue(unknown.remoteDominatorCanExpand(42))
        assertEquals(0, unknown.remoteDominatorChildRequest(42))

        val empty = unknown.copy(dominatorChildren = mapOf(42L to emptyList()))
        assertTrue(empty.remoteDominatorCanExpand(42))
        assertEquals(0, empty.remoteDominatorChildRequest(42))

        val leaf = empty.copy(dominatorChildTotals = mapOf(42L to 0))
        assertFalse(leaf.remoteDominatorCanExpand(42))
        assertNull(leaf.remoteDominatorChildRequest(42))
    }

    @Test
    fun `cached child pages do not reload on expansion and pending pages retain their offset`() {
        val child = row(44, parent = 42)
        val cached =
            MemoryProfilerState(
                remoteDominators = true,
                dominatorChildren = mapOf(42L to listOf(child)),
                dominatorChildTotals = mapOf(42L to 1000),
                dominatorChildOffsets = mapOf(42L to 500),
            )
        assertTrue(cached.remoteDominatorCanExpand(42))
        assertNull(cached.remoteDominatorChildRequest(42))
        assertEquals(500, cached.copy(dominatorChildren = emptyMap()).remoteDominatorChildRequest(42))
    }

    @Test
    fun `failed queries preserve parent semantics and can retry the current offset with cached children`() {
        val failed =
            MemoryProfilerState(
                remoteDominators = true,
                failedDominatorChildren = setOf(42L),
                dominatorChildOffsets = mapOf(42L to 500),
            )
        assertTrue(failed.remoteDominatorCanExpand(42))
        assertEquals(500, failed.remoteDominatorChildRequest(42))

        val cached =
            failed.copy(
                dominatorChildren = mapOf(42L to listOf(row(44, 42))),
                dominatorChildTotals = mapOf(42L to 1000),
            )
        assertTrue(cached.remoteDominatorCanExpand(42))
        assertEquals(500, cached.remoteDominatorChildRequest(42))
    }

    @Test
    fun `root pages and child pages retain source order without inferring roots from loaded parents`() {
        val root = row(42, parent = 43)
        val sibling = row(43)
        val child = row(44, parent = 42)
        val secondChild = row(45, parent = 42)
        val state =
            MemoryProfilerState(
                remoteDominators = true,
                dominatorRows = listOf(root, sibling),
                dominatorChildren = mapOf(42L to listOf(secondChild, child)),
                dominatorChildTotals = mapOf(42L to 2),
            )

        val collapsed = remoteDominatorEntries(state, emptySet())
        assertEquals(
            listOf(42L, 43L),
            collapsed.filterIsInstance<MemoryRemoteDominatorEntry.Node>().map { it.row.objectId },
        )

        val expanded = remoteDominatorEntries(state, setOf(42))
        assertEquals(
            listOf("object:42", "object:45", "object:44", "children:42", "object:43"),
            expanded.map { it.key },
        )
        assertEquals(listOf(0, 1, 1, 1, 0), expanded.map { it.depth })
    }

    @Test
    fun `cycles do not duplicate rows or recurse indefinitely`() {
        val root = row(42)
        val state =
            MemoryProfilerState(
                remoteDominators = true,
                dominatorRows = listOf(root),
                dominatorChildren = mapOf(42L to listOf(row(43, 42)), 43L to listOf(root)),
                dominatorChildTotals = mapOf(42L to 1, 43L to 1),
            )
        val entries = remoteDominatorEntries(state, setOf(42, 43))
        assertEquals(
            listOf(42L, 43L),
            entries.filterIsInstance<MemoryRemoteDominatorEntry.Node>().map { it.row.objectId },
        )
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
    }

    private fun row(
        id: Long,
        parent: Long? = null,
    ): MemoryDominatorRow = MemoryDominatorRow(id, "sample.Object$id", 16, 1000 - id, 0, parent)
}
