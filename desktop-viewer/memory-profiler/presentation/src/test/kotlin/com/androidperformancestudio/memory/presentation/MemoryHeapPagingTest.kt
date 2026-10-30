package com.androidperformancestudio.memory.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryHeapPagingTest {
    @Test
    fun `first middle and partial final pages expose global ranges and bounded offsets`() {
        val first = MemoryHeapPage(offset = 0, totalCount = 1201, loadedCount = 500, pageSize = 500)
        assertEquals(1, first.start)
        assertEquals(500, first.end)
        assertNull(first.previousOffset)
        assertEquals(500, first.nextOffset)
        assertTrue(first.isPaged)

        val middle = first.copy(offset = 500)
        assertEquals(501, middle.start)
        assertEquals(1000, middle.end)
        assertEquals(0, middle.previousOffset)
        assertEquals(1000, middle.nextOffset)

        val last = first.copy(offset = 1000, loadedCount = 201)
        assertEquals(1001, last.start)
        assertEquals(1201, last.end)
        assertEquals(500, last.previousOffset)
        assertNull(last.nextOffset)
    }

    @Test
    fun `empty pages do not imply that unseen rows were loaded`() {
        val empty = MemoryHeapPage(offset = 500, totalCount = 1201, loadedCount = 0, pageSize = 500)
        assertEquals(0, empty.start)
        assertEquals(0, empty.end)
        assertEquals(0, empty.previousOffset)
        assertNull(empty.nextOffset)
        assertTrue(empty.isPaged)

        val short = empty.copy(loadedCount = 10)
        assertEquals(510, short.nextOffset)
    }

    @Test
    fun `complete legacy lists do not offer paging regardless of the default page size`() {
        val legacy = MemoryHeapPage(offset = 0, totalCount = 1250, loadedCount = 1250, pageSize = 500)
        assertFalse(legacy.isPaged)
        assertNull(legacy.previousOffset)
        assertNull(legacy.nextOffset)
    }

    @Test
    fun `page boundaries do not overflow near the integer count limit`() {
        val final = MemoryHeapPage(Int.MAX_VALUE - 200, Int.MAX_VALUE, 500, 500)
        assertEquals(Int.MAX_VALUE - 199, final.start)
        assertEquals(Int.MAX_VALUE, final.end)
        assertEquals(Int.MAX_VALUE - 700, final.previousOffset)
        assertNull(final.nextOffset)
    }
}
