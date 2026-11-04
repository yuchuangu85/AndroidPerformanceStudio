package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.HeapClass
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapField
import com.androidperformancestudio.memory.model.HeapInstance
import com.androidperformancestudio.memory.model.HeapObjectArray
import com.androidperformancestudio.memory.model.HeapPrimitiveArray
import com.androidperformancestudio.memory.model.HeapPrimitiveArrayValueSource
import com.androidperformancestudio.memory.model.HeapRoot
import com.androidperformancestudio.memory.model.HeapRootKind
import com.androidperformancestudio.memory.model.HeapValueKind
import com.androidperformancestudio.memory.model.ObjectReference
import com.androidperformancestudio.memory.model.PrimitiveType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstanceValueMetadataTest {
    @Test
    fun `field and detail metadata defaults preserve positional constructors`() {
        val field = FieldValue("value", "7", null, null, 2)
        val detail =
            InstanceQueryDetail(
                1,
                "Root",
                8,
                null,
                null,
                false,
                null,
                fields = listOf(field),
                referenceChain = emptyList(),
            )

        assertEquals(HeapValueKind.UNKNOWN, field.valueKind)
        assertNull(field.shallowSize)
        assertNull(field.retainedSize)
        assertNull(field.nativeSize)
        assertNull(field.retainedNativeSize)
        assertNull(field.representedClassName)
        assertNull(field.stringPreview)
        assertNull(field.childCount)
        assertEquals(HeapValueKind.UNKNOWN, detail.valueKind)
        assertNull(detail.retainedNativeSize)
    }

    @Test
    @Suppress("LongMethod")
    fun `declared and inherited primitive types decode bits and retain declaration order with object fields`() {
        val declarations =
            listOf(
                HeapField("next", PrimitiveType.OBJECT),
                HeapField("signedByte", PrimitiveType.BYTE),
                HeapField("enabled", PrimitiveType.BOOLEAN),
                HeapField("signedShort", PrimitiveType.SHORT),
                HeapField("letter", PrimitiveType.CHAR),
                HeapField("ratio", PrimitiveType.FLOAT),
                HeapField("fraction", PrimitiveType.DOUBLE),
                HeapField("integer", PrimitiveType.INT),
                HeapField("long", PrimitiveType.LONG),
                HeapField("nothing", PrimitiveType.OBJECT),
            )
        val typedQuery =
            InstanceReferenceQuery(
                HeapDump(
                    classes =
                        listOf(
                            HeapClass(100, "Root", superClassObjectId = 200, instanceFields = declarations),
                            HeapClass(
                                200,
                                "Parent",
                                instanceFields = listOf(HeapField("inherited", PrimitiveType.LONG)),
                            ),
                            HeapClass(300, "Child"),
                        ),
                    instances =
                        listOf(
                            HeapInstance(
                                1,
                                100,
                                "Root",
                                32,
                                references = listOf(ref("nothing", 0), ref("next", 2)),
                                primitiveFields =
                                    linkedMapOf(
                                        "inherited" to 99L,
                                        "long" to Long.MIN_VALUE,
                                        "integer" to 0xffffffffL,
                                        "fraction" to (-2.5).toRawBits(),
                                        "ratio" to 1.5f.toRawBits().toLong(),
                                        "letter" to 65L,
                                        "signedShort" to 65534L,
                                        "enabled" to 1L,
                                        "signedByte" to 255L,
                                    ),
                            ),
                            HeapInstance(2, 300, "Child", 8, nativeSizeBytes = 64),
                        ),
                    gcRoots = listOf(HeapRoot(1, HeapRootKind.JNI_GLOBAL)),
                    objectRetainedSizes = mapOf(1L to 128L, 2L to 96L),
                ),
            )

        val detail = assertNotNull(typedQuery.detailOf(1))
        assertEquals(declarations.map { it.name } + "inherited", detail.fields.map { it.name })
        assertEquals(HeapValueKind.OBJECT, detail.valueKind)
        val primitives = detail.fields.filter { it.valueKind == HeapValueKind.PRIMITIVE }
        assertEquals(
            listOf("-1", "true", "-2", "A", "1.5", "-2.5", "-1", Long.MIN_VALUE.toString(), "99"),
            primitives.map { it.displayValue },
        )
        assertEquals(listOf(1L, 1L, 2L, 2L, 4L, 8L, 4L, 8L, 8L), primitives.map { it.shallowSize })
        primitives.forEach { field ->
            assertEquals(field.shallowSize, field.retainedSize)
            assertEquals(0L, field.nativeSize)
            assertEquals(0, field.depth)
            assertEquals(0, field.childCount)
            assertNull(field.retainedNativeSize)
        }
        val target = detail.fields.first()
        assertEquals(HeapValueKind.OBJECT, target.valueKind)
        assertEquals(8L, target.shallowSize)
        assertEquals(96L, target.retainedSize)
        assertEquals(64L, target.nativeSize)
        assertEquals(1, target.depth)
        assertEquals(0, target.childCount)
        assertNull(target.retainedNativeSize)
        val nullField = detail.fields.single { it.name == "nothing" }
        assertEquals(HeapValueKind.NULL, nullField.valueKind)
        assertEquals("null", nullField.displayValue)
        assertEquals(0L, nullField.shallowSize)
        assertEquals(0L, nullField.retainedSize)
        assertEquals(0L, nullField.nativeSize)
        assertNull(nullField.depth)
        assertNull(nullField.retainedNativeSize)
    }

    @Test
    fun `missing primitive types targets and unknown object sizes remain unavailable`() {
        val query =
            InstanceReferenceQuery(
                HeapDump(
                    instances =
                        listOf(
                            instance(
                                1,
                                "Root",
                                listOf(ref("known", 2), ref("missing", 999), ref("zeroNative", 3)),
                                mapOf("raw" to 255L),
                            ),
                            HeapInstance(2, 100, "UnknownSize", shallowSizeKnown = false),
                            HeapInstance(3, 101, "KnownZeroNative", 8, nativeSizeBytes = 0),
                        ),
                ),
            )
        val fields = assertNotNull(query.detailOf(1)).fields.associateBy { it.name }

        assertEquals("255", fields.getValue("raw").displayValue)
        assertEquals(HeapValueKind.PRIMITIVE, fields.getValue("raw").valueKind)
        assertNull(fields.getValue("raw").shallowSize)
        assertNull(fields.getValue("raw").retainedSize)
        assertEquals(HeapValueKind.UNKNOWN, fields.getValue("missing").valueKind)
        assertEquals(0L, fields.getValue("zeroNative").nativeSize)
        assertEquals(8L, fields.getValue("zeroNative").shallowSize)
        assertNull(fields.getValue("zeroNative").retainedNativeSize)
        for (name in listOf("known", "missing")) {
            val field = fields.getValue(name)
            assertNull(field.shallowSize)
            assertNull(field.retainedSize)
            assertNull(field.nativeSize)
            assertNull(field.retainedNativeSize)
            assertNull(field.childCount)
        }
    }

    @Test
    fun `shadowed primitive evidence uses the surviving inherited type without fabricating the lost value`() {
        val query =
            InstanceReferenceQuery(
                HeapDump(
                    classes =
                        listOf(
                            HeapClass(
                                100,
                                "Child",
                                superClassObjectId = 101,
                                instanceFields =
                                    listOf(
                                        HeapField("value", PrimitiveType.BYTE),
                                        HeapField("next", PrimitiveType.OBJECT),
                                    ),
                            ),
                            HeapClass(101, "Parent", instanceFields = listOf(HeapField("value", PrimitiveType.LONG))),
                        ),
                    instances =
                        listOf(
                            HeapInstance(
                                1,
                                100,
                                "Child",
                                references = listOf(ref("next", 0)),
                                primitiveFields = mapOf("value" to 255L),
                            ),
                        ),
                ),
            )
        val fields = assertNotNull(query.detailOf(1)).fields

        assertEquals(listOf("next", "value"), fields.map { it.name })
        assertEquals("255", fields.last().displayValue)
        assertEquals(8L, fields.last().shallowSize)
    }

    @Test
    fun `class roots fields and inbound sources preserve runtime type separately from represented labels`() {
        val query =
            InstanceReferenceQuery(
                HeapDump(
                    classes =
                        listOf(
                            HeapClass(10, "java.lang.Class", 32),
                            HeapClass(11, "sample.Represented", staticReferences = listOf(ref("static", 1))),
                        ),
                    instances = listOf(instance(1, "Root", refs = listOf(ref("type", 11)))),
                    objectRetainedSizes = mapOf(11L to 128L),
                ),
            )
        val root = assertNotNull(query.detailOf(11))
        val field = assertNotNull(query.detailOf(1)).fields.single()

        assertEquals(HeapValueKind.CLASS, root.valueKind)
        assertEquals("java.lang.Class", root.className)
        assertEquals("sample.Represented", root.representedClassName)
        assertEquals(HeapValueKind.CLASS, field.valueKind)
        assertEquals("java.lang.Class", field.targetClassName)
        assertEquals("sample.Represented", field.representedClassName)
        assertEquals(11L, field.targetObjectId)
        assertEquals(32L, field.shallowSize)
        assertEquals(128L, field.retainedSize)
        assertEquals("0xb \u00b7 sample.Represented", field.displayValue)
        assertNull(field.nativeSize)
        assertNull(field.retainedNativeSize)

        val inbound = assertNotNull(query.detailOf(1)).references.single()
        assertEquals(HeapValueKind.CLASS, inbound.valueKind)
        assertEquals("java.lang.Class", inbound.targetClassName)
        assertEquals("sample.Represented", inbound.representedClassName)
        assertEquals(11L, inbound.targetObjectId)
        assertEquals("sample.Represented @0xb", inbound.displayValue)
    }

    @Test
    fun `inbound grouping preserves raw field names array indexes and source metrics sorted by depth then id`() {
        val query =
            InstanceReferenceQuery(
                HeapDump(
                    instances =
                        listOf(
                            instance(30, "ZSource", listOf(ref("last", 99), ref("first", 99), ref("array", 40))),
                            HeapInstance(
                                2,
                                100,
                                "ASource",
                                8,
                                references = listOf(ref("middle", 99)),
                                nativeSizeBytes = 7,
                            ),
                            instance(1, "Unreachable", listOf(ref("orphan", 99))),
                            instance(99, "Target"),
                        ),
                    objectArrays = listOf(HeapObjectArray(40, 100, "Target[]", 4, listOf(99, 0, 0, 99), 24)),
                    gcRoots = listOf(HeapRoot(30, HeapRootKind.JNI_GLOBAL), HeapRoot(2, HeapRootKind.JNI_GLOBAL)),
                    objectRetainedSizes = mapOf(2L to 80L, 40L to 256L, 99L to 900L),
                ),
            )
        val references = assertNotNull(query.detailOf(99)).references

        assertEquals(listOf(2L, 30L, 40L, 1L), references.map { it.targetObjectId })
        assertEquals(listOf("middle", "last, first", "Index 0, 3", "orphan"), references.map { it.name })
        assertEquals(listOf(0, 0, 1, null), references.map { it.depth })
        assertEquals(8L, references.first().shallowSize)
        assertEquals(80L, references.first().retainedSize)
        assertEquals(7L, references.first().nativeSize)
        assertEquals(HeapValueKind.ARRAY, references[2].valueKind)
        assertEquals(24L, references[2].shallowSize)
        assertEquals(256L, references[2].retainedSize)
        references.forEach {
            assertNull(it.retainedNativeSize)
            assertNull(it.childCount)
        }
    }

    @Test
    fun `primitive array pages read only their bounded absolute range and retain typed metrics`() {
        val reads = mutableListOf<Pair<Int, Int>>()
        val source =
            object : HeapPrimitiveArrayValueSource {
                override fun read(
                    start: Int,
                    count: Int,
                ): LongArray {
                    reads += start to count
                    return LongArray(count) { start + it.toLong() }
                }
            }
        val query =
            InstanceReferenceQuery(
                HeapDump(
                    primitiveArrays = listOf(HeapPrimitiveArray(1, PrimitiveType.INT, 450, valueSource = source)),
                    gcRoots = listOf(HeapRoot(1, HeapRootKind.JNI_GLOBAL)),
                ),
            )
        val detail = assertNotNull(query.detailOf(1, arrayStart = 400, arrayLimit = 1000))

        assertEquals(listOf(400 to 50), reads)
        assertEquals(HeapValueKind.ARRAY, detail.valueKind)
        assertEquals(50, detail.fields.size)
        assertEquals("[400]", detail.fields.first().name)
        assertEquals("400", detail.fields.first().displayValue)
        assertEquals("449", detail.fields.last().displayValue)
        detail.fields.forEach {
            assertEquals(HeapValueKind.PRIMITIVE, it.valueKind)
            assertEquals(4L, it.shallowSize)
            assertEquals(4L, it.retainedSize)
            assertEquals(0L, it.nativeSize)
            assertEquals(0, it.depth)
            assertNull(it.retainedNativeSize)
        }
        assertTrue(assertNotNull(query.detailOf(1, arrayStart = 450)).fields.isEmpty())
        assertEquals(listOf(400 to 50), reads)
    }

    @Test
    fun `primitive arrays without a payload source never invent element values`() {
        val query =
            InstanceReferenceQuery(HeapDump(primitiveArrays = listOf(HeapPrimitiveArray(1, PrimitiveType.BYTE, 5))))
        val detail = assertNotNull(query.detailOf(1))

        assertEquals(HeapValueKind.ARRAY, detail.valueKind)
        assertEquals(5, detail.elementCount)
        assertTrue(detail.fields.isEmpty())
        assertNull(detail.stringPreview)
    }

    @Test
    fun `string previews decode bounded backing payloads and remain absent without evidence`() {
        val reads = mutableListOf<Pair<Int, Int>>()
        val characters = "XhelloY".map { it.code.toLong() }.toLongArray()
        val source =
            object : HeapPrimitiveArrayValueSource {
                override fun read(
                    start: Int,
                    count: Int,
                ): LongArray {
                    reads += start to count
                    return characters.copyOfRange(start, start + count)
                }
            }
        val query =
            InstanceReferenceQuery(
                HeapDump(
                    instances =
                        listOf(
                            instance(
                                1,
                                "java.lang.String",
                                listOf(ref("value", 2)),
                                mapOf("offset" to 1L, "count" to 5L),
                            ),
                            instance(3, "java.lang.String", listOf(ref("value", 4))),
                            instance(5, "java.lang.String"),
                            instance(6, "Root", listOf(ref("title", 1))),
                        ),
                    primitiveArrays =
                        listOf(
                            HeapPrimitiveArray(2, PrimitiveType.CHAR, characters.size, valueSource = source),
                            HeapPrimitiveArray(4, PrimitiveType.BYTE, 5),
                        ),
                ),
            )

        val detail = assertNotNull(query.detailOf(1))
        assertEquals(HeapValueKind.STRING, detail.valueKind)
        assertEquals("\"hello\"", detail.stringPreview)
        assertEquals(listOf(1 to 5), reads)
        val field = assertNotNull(query.detailOf(6)).fields.single()
        assertEquals(HeapValueKind.STRING, field.valueKind)
        assertEquals("\"hello\"", field.stringPreview)
        assertNull(assertNotNull(query.detailOf(3)).stringPreview)
        assertNull(assertNotNull(query.detailOf(5)).stringPreview)
    }

    @Test
    fun `string preview truncation bounds payload reads and decodes signed bytes as UTF8`() {
        val reads = mutableListOf<Pair<Int, Int>>()
        val source =
            object : HeapPrimitiveArrayValueSource {
                override fun read(
                    start: Int,
                    count: Int,
                ): LongArray {
                    reads += start to count
                    return LongArray(count) { 'a'.code.toLong() }
                }
            }
        val bytes = "caf\u00e9".toByteArray(Charsets.UTF_8)
        val byteSource =
            object : HeapPrimitiveArrayValueSource {
                override fun read(
                    start: Int,
                    count: Int,
                ): LongArray = LongArray(count) { bytes[start + it].toLong() }
            }
        val query =
            InstanceReferenceQuery(
                HeapDump(
                    instances =
                        listOf(
                            instance(1, "java.lang.String", listOf(ref("value", 2))),
                            instance(3, "java.lang.String", listOf(ref("value", 4))),
                        ),
                    primitiveArrays =
                        listOf(
                            HeapPrimitiveArray(2, PrimitiveType.CHAR, 1500, valueSource = source),
                            HeapPrimitiveArray(4, PrimitiveType.BYTE, bytes.size, valueSource = byteSource),
                        ),
                ),
            )

        assertEquals("\"" + "a".repeat(1023) + "...\"", assertNotNull(query.detailOf(1)).stringPreview)
        assertEquals(listOf(0 to 1024), reads)
        assertEquals("\"caf\u00e9\"", assertNotNull(query.detailOf(3)).stringPreview)
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
