package com.androidperformancestudio.memory.presentation

import com.androidperformancestudio.memory.model.ClassStats
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryProfilerPresenterTest {
    @Test
    fun `count sort is descending and stable by class name`() {
        val sorted = MemoryProfilerPresenter.sortClasses(sampleClasses(), MemoryHistogramSort.Count)

        assertEquals(
            listOf("java.lang.String", "kotlin.ByteArray", "android.graphics.Bitmap", "com.example.Tiny"),
            sorted.map { it.className },
        )
    }

    @Test
    fun `shallow sort is descending and stable by class name`() {
        val sorted = MemoryProfilerPresenter.sortClasses(sampleClasses(), MemoryHistogramSort.Shallow)

        assertEquals(
            listOf("android.graphics.Bitmap", "kotlin.ByteArray", "java.lang.String", "com.example.Tiny"),
            sorted.map { it.className },
        )
    }

    @Test
    fun `presenter applies selected histogram sort without mutating other state`() {
        val input =
            MemoryProfilerState(
                classes = sampleClasses(),
                sort = MemoryHistogramSort.Shallow,
                cleanupWarning = "rm failed for /data/local/tmp/heap.hprof",
            )

        val presented = MemoryProfilerPresenter.present(input)

        assertEquals(MemoryHistogramSort.Shallow, presented.sort)
        assertEquals("rm failed for /data/local/tmp/heap.hprof", presented.cleanupWarning)
        assertEquals("android.graphics.Bitmap", presented.classes.first().className)
        assertEquals("java.lang.String", input.classes.first().className)
    }

    private fun sampleClasses(): List<ClassStats> =
        listOf(
            ClassStats("java.lang.String", instanceCount = 12, shallowSize = 2_048L),
            ClassStats("kotlin.ByteArray", instanceCount = 12, shallowSize = 4_096L),
            ClassStats("android.graphics.Bitmap", instanceCount = 3, shallowSize = 4_096L),
            ClassStats("com.example.Tiny", instanceCount = 1, shallowSize = 8L),
        )
}
