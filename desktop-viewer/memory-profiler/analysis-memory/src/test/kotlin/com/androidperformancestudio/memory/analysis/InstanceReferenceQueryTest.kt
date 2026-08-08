package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapInstance
import com.androidperformancestudio.memory.model.HeapObjectArray
import com.androidperformancestudio.memory.model.HeapRoot
import com.androidperformancestudio.memory.model.HeapRootKind
import com.androidperformancestudio.memory.model.ObjectReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstanceReferenceQueryTest {
    private val heap =
        HeapDump(
            instances =
                listOf(
                    instance(
                        1,
                        "com.example.Root",
                        refs = listOf(ref("child", 2), ref("array", 5), ref("weak", 6)),
                    ),
                    instance(
                        2,
                        "com.example.Widget",
                        primitiveFields = mapOf("mWidth" to 100L, "mHeight" to 50L),
                        refs = listOf(ref("next", 3)),
                    ),
                    instance(3, "com.example.Widget"),
                    instance(4, "com.example.Orphan"),
                    instance(6, "java.lang.ref.WeakReference", refs = listOf(ref("referent", 7))),
                    instance(7, "com.example.WeakTarget"),
                ),
            objectArrays =
                listOf(
                    HeapObjectArray(
                        objectId = 5,
                        arrayClassObjectId = 105,
                        className = "com.example.Widget[]",
                        elementCount = 2,
                        elementIds = listOf(2, 3),
                        shallowSize = 20,
                    ),
                ),
            gcRoots = listOf(HeapRoot(1, HeapRootKind.JNI_GLOBAL)),
        )
    private val query = InstanceReferenceQuery(heap)

    @Test
    fun `instance list reports index shallow retained and depth per class`() {
        val rows = query.instancesOf("com.example.Widget")

        assertEquals(2, rows.size)
        assertEquals(1, rows[0].index)
        assertEquals(2L, rows[0].objectId)
        assertEquals(10, rows[0].shallowSize)
        assertEquals(1, rows[0].depth)
        assertTrue(rows[0].reachable)
        assertEquals(2, rows[1].index)
        assertEquals(3L, rows[1].objectId)
        assertEquals(2, rows[1].depth)
    }

    @Test
    fun `array class instances include object arrays`() {
        val rows = query.instancesOf("com.example.Widget[]")

        assertEquals(1, rows.size)
        assertEquals(5L, rows[0].objectId)
        assertEquals(1, rows[0].depth)
        assertEquals(20, rows[0].shallowSize)
    }

    @Test
    fun `unreachable instances report null depth and unreachable flag`() {
        val rows = query.instancesOf("com.example.Orphan")

        assertEquals(1, rows.size)
        assertNull(rows.single().depth)
        assertFalse(rows.single().reachable)
    }

    @Test
    fun `weak reference referent is not a strong edge`() {
        val rows = query.instancesOf("com.example.WeakTarget")

        assertNull(rows.single().depth)
        assertFalse(rows.single().reachable)
    }

    @Test
    fun `detail exposes primitive and reference fields with shortest root chain`() {
        val detail = query.detailOf(2)

        assertEquals("com.example.Widget", detail?.className)
        assertEquals(1, detail?.depth)
        assertEquals("mWidth", detail?.fields?.get(0)?.name)
        assertEquals("100", detail?.fields?.get(0)?.displayValue)
        assertNull(detail?.fields?.get(0)?.targetObjectId)
        assertEquals("next", detail?.fields?.get(2)?.name)
        assertEquals(3L, detail?.fields?.get(2)?.targetObjectId)
        assertEquals("com.example.Widget", detail?.fields?.get(2)?.targetClassName)
        assertEquals(2, detail?.referenceChain?.size)
        assertEquals("GC Root (JNI_GLOBAL)", detail?.referenceChain?.first()?.fieldName)
        assertEquals(setOf("child", "[0]"), detail?.references?.map { it.name }?.toSet())
    }

    @Test
    fun `array detail reports element count and capped element references`() {
        val detail = assertNotNull(query.detailOf(5))

        assertTrue(detail.isArray)
        assertEquals(2, detail.elementCount)
        assertEquals(2, detail.fields.size)
        assertEquals("[0]", detail.fields.first().name)
        assertEquals(2L, detail.fields.first().targetObjectId)
    }

    @Test
    fun `unknown object id returns null detail`() {
        assertNull(query.detailOf(999L))
    }

    @Test
    fun `instancesOf filters by heap when a heap name is given`() {
        val heap =
            HeapDump(
                instances =
                    listOf(
                        HeapInstance(
                            objectId = 1,
                            classObjectId = 100,
                            className = "com.example.Widget",
                            shallowSize = 10,
                        ),
                        HeapInstance(
                            objectId = 2,
                            classObjectId = 100,
                            className = "com.example.Widget",
                            shallowSize = 10,
                        ),
                    ),
                heapByObjectId = mapOf(1L to "App", 2L to "Image"),
            )
        val heapQuery = InstanceReferenceQuery(heap)

        assertEquals(listOf(1L), heapQuery.instancesOf("com.example.Widget", heapName = "App").map { it.objectId })
        assertEquals(listOf(2L), heapQuery.instancesOf("com.example.Widget", heapName = "Image").map { it.objectId })
        assertEquals(2, heapQuery.instancesOf("com.example.Widget").size)
    }

    private fun instance(
        id: Long,
        className: String,
        refs: List<ObjectReference> = emptyList(),
        primitiveFields: Map<String, Long> = emptyMap(),
    ): HeapInstance =
        HeapInstance(
            objectId = id,
            classObjectId = id + 1000,
            className = className,
            shallowSize = 10,
            references = refs,
            primitiveFields = primitiveFields,
        )

    private fun ref(
        field: String,
        target: Long,
    ): ObjectReference = ObjectReference(field, target)
}
