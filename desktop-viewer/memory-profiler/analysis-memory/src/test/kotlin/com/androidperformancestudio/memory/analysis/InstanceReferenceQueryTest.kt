package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.HeapClass
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapInstance
import com.androidperformancestudio.memory.model.HeapObjectArray
import com.androidperformancestudio.memory.model.HeapPrimitiveArray
import com.androidperformancestudio.memory.model.HeapRoot
import com.androidperformancestudio.memory.model.HeapRootKind
import com.androidperformancestudio.memory.model.ObjectReference
import com.androidperformancestudio.memory.model.PrimitiveType
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
    fun `instance row keeps legacy positional constructor compatible`() {
        val row = InstanceQueryRow(1, 2, 10, null, null, false, 64, false)

        assertNull(row.className)
        assertEquals(64L, row.nativeSize)
        assertFalse(row.shallowSizeKnown)
    }

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
        assertEquals(listOf("com.example.Widget", "com.example.Widget"), rows.map { it.className })
    }

    @Test
    fun `array class instances include object arrays`() {
        val rows = query.instancesOf("com.example.Widget[]")

        assertEquals(1, rows.size)
        assertEquals(5L, rows[0].objectId)
        assertEquals(1, rows[0].depth)
        assertEquals(20, rows[0].shallowSize)
        assertEquals("com.example.Widget[]", rows[0].className)
    }

    @Test
    fun `primitive array rows keep runtime type heap scope and object id ordering`() {
        val query =
            InstanceReferenceQuery(
                HeapDump(
                    primitiveArrays =
                        listOf(
                            HeapPrimitiveArray(9, PrimitiveType.INT, 2, 8),
                            HeapPrimitiveArray(7, PrimitiveType.INT, 1, 4),
                            HeapPrimitiveArray(8, PrimitiveType.BYTE, 1, 1),
                        ),
                    heapByObjectId = mapOf(7L to "App", 8L to "App", 9L to "Image"),
                ),
            )

        val rows = query.instancesOf("int[]")
        assertEquals(listOf(7L, 9L), rows.map { it.objectId })
        assertEquals(listOf(1, 2), rows.map { it.index })
        assertEquals(listOf("int[]", "int[]"), rows.map { it.className })
        val imageRows = query.instancesOf("int[]", heapName = "Image")
        assertEquals(listOf(rows.last().copy(index = 1)), imageRows)
        assertEquals("byte[]", query.instancesOf("byte[]").single().className)
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
        assertTrue(assertNotNull(query.detailOf(7)).references.isEmpty())
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
        assertEquals(setOf("child", "Index 0"), detail?.references?.map { it.name }?.toSet())
    }

    @Test
    fun `inbound reference depths distinguish nearer roots peers and unreachable sources`() {
        val cyclic =
            heap.copy(
                instances =
                    heap.instances.map { obj ->
                        when (obj.objectId) {
                            2L -> obj.copy(references = obj.references + ref("self", 2))
                            3L -> obj.copy(references = obj.references + ref("back", 2))
                            4L -> obj.copy(references = listOf(ref("orphan", 2)))
                            else -> obj
                        }
                    },
            )
        val detail = assertNotNull(InstanceReferenceQuery(cyclic).detailOf(2))

        assertEquals(1, detail.depth)
        assertEquals(
            mapOf("child" to 0, "Index 0" to 1, "self" to 1, "back" to 2, "orphan" to null),
            detail.references.associate { it.name to it.depth },
        )
    }

    @Test
    fun `inbound references are rendered and sorted when an object is inspected`() {
        val query =
            InstanceReferenceQuery(
                HeapDump(
                    instances =
                        listOf(
                            instance(3, "ZSource", refs = listOf(ref("last", 2), ref("first", 2))),
                            instance(1, "ASource", refs = listOf(ref("middle", 2))),
                            instance(2, "Target"),
                        ),
                ),
            )

        val references = assertNotNull(query.detailOf(2)).references

        assertEquals(listOf("middle", "last, first"), references.map { it.name })
        assertEquals(listOf("ASource @0x1", "ZSource @0x3"), references.map { it.displayValue })
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
    fun `array detail supports bounded page ranges`() {
        val detail = assertNotNull(query.detailOf(5, arrayStart = 1, arrayLimit = 1))

        assertEquals(2, detail.elementCount)
        assertEquals("[1]", detail.fields.single().name)
        assertEquals(3L, detail.fields.single().targetObjectId)
        assertEquals(1, detail.arrayPageSize)
    }

    @Test
    fun `array page range retains absolute indexes and requested page size at the final partial page`() {
        val longArray =
            InstanceReferenceQuery(
                HeapDump(
                    objectArrays =
                        listOf(
                            HeapObjectArray(
                                objectId = 50,
                                arrayClassObjectId = 105,
                                elementCount = 450,
                                elementIds = (1L..450L).toList(),
                            ),
                        ),
                ),
            )

        val detail = assertNotNull(longArray.detailOf(50, arrayStart = 400))

        assertEquals(200, detail.arrayPageSize)
        assertEquals(50, detail.fields.size)
        assertEquals("[400]", detail.fields.first().name)
        assertEquals("[449]", detail.fields.last().name)
    }

    @Test
    fun `unknown object id returns null detail`() {
        assertNull(query.detailOf(999L))
    }

    @Test
    fun `object detail preserves unknown shallow size and estimated native size`() {
        val objectQuery =
            InstanceReferenceQuery(
                HeapDump(
                    instances =
                        listOf(
                            HeapInstance(
                                objectId = 42,
                                classObjectId = 100,
                                className = "com.example.Bitmap",
                                shallowSize = 0,
                                shallowSizeKnown = false,
                                nativeSizeBytes = 4096,
                            ),
                        ),
                ),
            )

        val row = objectQuery.instancesOf("com.example.Bitmap").single()
        val detail = assertNotNull(objectQuery.detailOf(42))
        assertFalse(row.shallowSizeKnown)
        assertEquals(4096L, row.nativeSize)
        assertFalse(detail.shallowSizeKnown)
        assertEquals(4096L, detail.nativeSize)
        assertNull(detail.depth)
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

    @Test
    fun `class dump rows join instances of represented class while details keep physical type`() {
        val query =
            InstanceReferenceQuery(
                HeapDump(
                    classes =
                        listOf(
                            HeapClass(10, "java.lang.Class", instanceSize = 32),
                            HeapClass(11, "com.example.Item", instanceSize = 24),
                        ),
                    instances = listOf(instance(21, "com.example.Item")),
                    heapByObjectId = mapOf(10L to "Zygote", 11L to "App", 21L to "App"),
                ),
            )

        val classRows = query.instancesOf("java.lang.Class")
        assertEquals(listOf(10L), classRows.map { it.objectId })
        assertEquals("java.lang.Class", classRows.single().className)
        assertEquals("java.lang.Class", classRows.single().representedClassName)
        val itemRows = query.instancesOf("com.example.Item", heapName = "App")
        assertEquals(listOf(11L, 21L), itemRows.map { it.objectId })
        assertEquals(listOf(1, 2), itemRows.map { it.index })
        assertEquals(listOf(32L, 10L), itemRows.map { it.shallowSize })
        assertEquals(listOf("java.lang.Class", "com.example.Item"), itemRows.map { it.className })
        assertEquals(listOf("com.example.Item", null), itemRows.map { it.representedClassName })
        assertEquals("java.lang.Class", query.detailOf(11)?.className)
    }

    @Test
    fun `class dump runtime type is known even without Class size metadata`() {
        val query = InstanceReferenceQuery(HeapDump(classes = listOf(HeapClass(11, "com.example.Item"))))

        val row = query.instancesOf("com.example.Item").single()

        assertEquals(11L, row.objectId)
        assertEquals(1, row.index)
        assertEquals("java.lang.Class", row.className)
        assertEquals("com.example.Item", row.representedClassName)
        assertFalse(row.shallowSizeKnown)
    }

    @Test
    fun `static class edges remain navigable without materializing a full graph`() {
        val query =
            InstanceReferenceQuery(
                HeapDump(
                    classes =
                        listOf(
                            HeapClass(10, "java.lang.Class", instanceSize = 32),
                            HeapClass(11, "com.example.Holder", staticReferences = listOf(ref("static child", 2))),
                        ),
                    instances = listOf(instance(2, "com.example.Child")),
                    gcRoots = listOf(HeapRoot(11, HeapRootKind.JNI_GLOBAL)),
                ),
            )

        val detail = assertNotNull(query.detailOf(2))

        assertEquals(1, detail.depth)
        assertEquals(listOf("GC Root (JNI_GLOBAL)", "static child"), detail.referenceChain.map { it.fieldName })
        assertEquals(listOf(11L), detail.references.map { it.targetObjectId })
    }

    @Test
    fun `long same-class chain exposes cached breadth-first depths`() {
        val count = 5_000
        val query =
            InstanceReferenceQuery(
                HeapDump(
                    instances =
                        (1..count).map { id ->
                            instance(
                                id.toLong(),
                                "Node",
                                refs = if (id < count) listOf(ref("next", id + 1L)) else emptyList(),
                            )
                        },
                    gcRoots = listOf(HeapRoot(1, HeapRootKind.JNI_GLOBAL)),
                ),
            )

        val rows = query.instancesOf("Node")

        assertEquals(count, rows.size)
        assertEquals(0, rows.first().depth)
        assertEquals(count - 1, rows.last().depth)
        assertEquals(count, query.detailOf(count.toLong())?.referenceChain?.size)
    }

    @Test
    fun `sparse array inbound scan retains absolute field indexes`() {
        val ids = MutableList(50_000) { 0L }
        ids[20_000] = 2L
        ids[49_999] = 2L
        val query =
            InstanceReferenceQuery(
                HeapDump(
                    instances = listOf(instance(2, "Target")),
                    objectArrays = listOf(HeapObjectArray(1, 100, "Target[]", ids.size, ids)),
                ),
            )

        assertEquals(listOf("Index 20000, 49999"), query.detailOf(2)?.references?.map { it.name })
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
