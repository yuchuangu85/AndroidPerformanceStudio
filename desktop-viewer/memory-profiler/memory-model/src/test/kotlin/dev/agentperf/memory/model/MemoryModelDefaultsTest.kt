package dev.agentperf.memory.model

import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemoryModelDefaultsTest {
    @Test
    fun `heap dump defaults are phase one safe`() {
        val heapDump = HeapDump()

        assertNull(heapDump.rawHprofFile)
        assertNull(heapDump.convertedHprofFile)
        assertEquals(emptyList(), heapDump.leakSuspects)
        assertEquals(emptyList(), heapDump.classes)
        assertEquals(emptyList(), heapDump.instances)
        assertEquals(emptyList(), heapDump.objectArrays)
        assertEquals(emptyList(), heapDump.primitiveArrays)
        assertEquals("", heapDump.id)
        assertEquals("", heapDump.packageName)
        assertEquals(0, heapDump.pid)
        assertEquals(Instant.EPOCH, heapDump.capturedAt)
        assertEquals(HeapSummary(), heapDump.heapSummary)
        assertEquals(emptyList(), heapDump.topClasses)
    }

    @Test
    fun `heap dump stores raw and converted hprof paths independently`() {
        val raw = Path.of("raw.hprof")
        val converted = Path.of("converted.hprof")

        assertEquals(raw, HeapDump(rawHprofFile = raw).rawHprofFile)
        assertNull(HeapDump(rawHprofFile = raw).convertedHprofFile)
        assertEquals(converted, HeapDump(rawHprofFile = raw, convertedHprofFile = converted).convertedHprofFile)
    }

    @Test
    fun `class stats retained size is unavailable until phase two`() {
        val stats = ClassStats(className = "Example", instanceCount = 1, shallowSize = 24L)

        assertNull(stats.retainedSize)
    }

    @Test
    fun `size fields are stored as raw bytes`() {
        val stats = ClassStats(className = "Example", shallowSize = 1_048_576L)
        val summary = HeapSummary(objectCount = 1, shallowSize = 1_048_576L, classCount = 1)

        assertEquals(1_048_576L, stats.shallowSize)
        assertEquals(1_048_576L, summary.shallowSize)
    }
}
