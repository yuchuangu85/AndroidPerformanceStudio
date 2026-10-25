package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.HeapClass
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapInstance
import com.androidperformancestudio.memory.model.HeapObjectArray
import com.androidperformancestudio.memory.model.HeapPrimitiveArray
import com.androidperformancestudio.memory.model.PrimitiveType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryHistogramAnalyzerTest {
    private val analyzer = MemoryHistogramAnalyzer()

    @Test
    fun `heap distribution and class heaps use recorded objects and preserve zero native evidence`() {
        val histogram =
            analyzer.histogram(
                HeapDump(
                    instances = listOf(HeapInstance(1, 10, "A", 16, nativeSizeBytes = 0), HeapInstance(2, 10, "A", 32)),
                    heapByObjectId = mapOf(1L to "App", 2L to "Zygote"),
                ),
            )
        assertEquals(setOf("App", "Zygote"), histogram.classes.single().heapNames)
        assertEquals(0L, histogram.classes.single().nativeSize)
        assertEquals(48L, histogram.summary.heapSegments.sumOf { it.size })
        assertEquals(2, histogram.summary.heapSegments.sumOf { it.objectCount })
        assertNull(histogram.classes.single().allocations)
    }

    @Test
    fun `generates class histogram from parsed heap`() {
        val heapDump =
            HeapDump(
                instances =
                    listOf(
                        HeapInstance(objectId = 1, classObjectId = 10, className = "B", shallowSize = 16),
                        HeapInstance(objectId = 2, classObjectId = 10, className = "B", shallowSize = 16),
                        HeapInstance(objectId = 3, classObjectId = 11, className = "A", shallowSize = 24),
                    ),
            )

        val histogram = analyzer.histogram(heapDump)

        assertEquals(3, histogram.summary.objectCount)
        assertEquals(56, histogram.summary.shallowSize)
        assertEquals(listOf("B", "A"), histogram.classes.map { it.className })
        assertEquals(2, histogram.classes.first().instanceCount)
        assertEquals(2L, histogram.classes.first().totalCount)
        assertNull(histogram.classes.first().allocations)
        assertNull(histogram.classes.first().allocationsSize)
        assertNull(histogram.classes.first().deallocations)
        assertNull(histogram.classes.first().shallowSizeChange)
    }

    @Test
    fun `sorts by count descending then class name ascending`() {
        val histogram =
            analyzer.histogram(
                HeapDump(
                    instances =
                        listOf(
                            HeapInstance(1, 1, "Z", 10),
                            HeapInstance(2, 2, "A", 10),
                            HeapInstance(3, 1, "Z", 10),
                            HeapInstance(4, 3, "B", 10),
                        ),
                ),
                sort = HistogramSort.COUNT,
            )

        assertEquals(listOf("Z", "A", "B"), histogram.classes.map { it.className })
    }

    @Test
    fun `sorts by shallow size descending then class name ascending`() {
        val histogram =
            analyzer.histogram(
                HeapDump(
                    instances =
                        listOf(
                            HeapInstance(1, 1, "Z", 20),
                            HeapInstance(2, 2, "A", 30),
                            HeapInstance(3, 3, "B", 30),
                        ),
                ),
                sort = HistogramSort.SHALLOW_SIZE,
            )

        assertEquals(listOf("A", "B", "Z"), histogram.classes.map { it.className })
    }

    @Test
    fun `uses parsed primitive and object array shallow sizes`() {
        val histogram =
            analyzer.histogram(
                HeapDump(
                    primitiveArrays =
                        listOf(
                            HeapPrimitiveArray(1, PrimitiveType.INT, elementCount = 3, shallowSize = 28),
                        ),
                    objectArrays =
                        listOf(
                            HeapObjectArray(2, 10, "java.lang.Object[]", elementCount = 2, shallowSize = 32),
                        ),
                ),
                sort = HistogramSort.SHALLOW_SIZE,
            )

        assertEquals(60, histogram.summary.shallowSize)
        assertEquals(listOf("java.lang.Object[]", "int[]"), histogram.classes.map { it.className })
    }

    @Test
    fun `empty heap returns empty histogram and summary`() {
        val histogram = analyzer.histogram(HeapDump())

        assertEquals(0, histogram.summary.objectCount)
        assertEquals(0, histogram.summary.shallowSize)
        assertEquals(0, histogram.summary.classCount)
        assertTrue(histogram.classes.isEmpty())
    }

    @Test
    fun `class dump records are grouped with their represented class`() {
        val histogram =
            analyzer.histogram(
                HeapDump(
                    classes =
                        listOf(
                            HeapClass(10, "java.lang.Class", instanceSize = 32),
                            HeapClass(11, "com.example.Item", instanceSize = 24),
                        ),
                ),
            )

        assertEquals(listOf("com.example.Item", "java.lang.Class"), histogram.classes.map { it.className })
        assertEquals(listOf(1, 1), histogram.classes.map { it.instanceCount })
        assertEquals(listOf(32L, 32L), histogram.classes.map { it.shallowSize })
        assertEquals(2, histogram.summary.objectCount)
    }

    @Test
    fun `class object and ordinary instances share represented class histogram row`() {
        val heap =
            HeapDump(
                classes =
                    listOf(
                        HeapClass(10, "java.lang.Class", instanceSize = 32),
                        HeapClass(11, "com.example.Item", instanceSize = 24),
                    ),
                instances = listOf(HeapInstance(21, 11, "com.example.Item", 24)),
                heapByObjectId = mapOf(10L to "Zygote", 11L to "App", 21L to "App"),
            )
        val histogram = analyzer.histogram(heap)

        val item = histogram.classes.single { it.className == "com.example.Item" }
        assertEquals(2, item.instanceCount)
        assertEquals(56L, item.shallowSize)
        assertEquals(setOf("App"), item.heapNames)
        assertEquals(
            2,
            analyzer
                .histogram(heap, heapName = "App")
                .classes
                .single()
                .instanceCount,
        )
    }

    @Test
    fun `retained size is always null in phase one`() {
        val histogram =
            analyzer.histogram(
                HeapDump(instances = listOf(HeapInstance(1, 1, "A", 10))),
            )

        assertNull(histogram.classes.single().retainedSize)
    }

    @Test
    fun `class retained size does not double count nested instances`() {
        val histogram =
            analyzer.histogram(
                heapDump =
                    HeapDump(
                        instances =
                            listOf(
                                HeapInstance(1, 10, "Node", 10, references = emptyList()),
                                HeapInstance(2, 10, "Node", 10, references = emptyList()),
                            ),
                    ),
                retainedSizes = mapOf(1L to 20L, 2L to 10L),
                immediateDominators = mapOf(1L to null, 2L to 1L),
            )

        assertEquals(20L, histogram.classes.single().retainedSize)
    }
}
