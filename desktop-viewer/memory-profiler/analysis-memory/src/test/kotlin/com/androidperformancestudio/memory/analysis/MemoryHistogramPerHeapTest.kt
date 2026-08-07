package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryHistogramPerHeapTest {
    private val heap =
        HeapDump(
            instances =
                listOf(
                    instance(1, "com.example.A", 10),
                    instance(2, "com.example.B", 20),
                    instance(3, "com.example.A", 30),
                ),
            heapByObjectId = mapOf(1L to "App", 2L to "Image", 3L to "App"),
        )

    @Test
    fun `histogram filters objects by heap name`() {
        val app = MemoryHistogramAnalyzer().histogram(heap, heapName = "App")

        assertEquals(2, app.summary.objectCount)
        val classA = app.classes.single { it.className == "com.example.A" }
        assertEquals(2, classA.instanceCount)
        assertEquals(40L, classA.shallowSize)
        assertTrue(app.classes.none { it.className == "com.example.B" })

        val image = MemoryHistogramAnalyzer().histogram(heap, heapName = "Image")
        assertEquals(1, image.summary.objectCount)
        assertEquals("com.example.B", image.classes.single().className)
    }

    @Test
    fun `histogram without heap name aggregates all objects`() {
        val all = MemoryHistogramAnalyzer().histogram(heap)

        assertEquals(3, all.summary.objectCount)
        assertEquals(2, all.classes.size)
    }

    @Test
    fun `heapNamesOf returns present heaps in canonical order`() {
        assertEquals(listOf("App", "Image"), MemoryHistogramAnalyzer().heapNamesOf(heap))
    }

    private fun instance(
        id: Long,
        className: String,
        shallowSize: Long,
    ): HeapInstance =
        HeapInstance(
            objectId = id,
            classObjectId = id + 1000,
            className = className,
            shallowSize = shallowSize,
        )
}
