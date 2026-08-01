package com.androidperformancestudio.memory.hprof

import com.androidperformancestudio.memory.model.PrimitiveType
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HprofParserTest {
    private val parser = HprofParser()

    @Test
    fun `reads header id size and timestamp`() {
        val heap = parser.parse(HprofFixtureBuilder(idSize = 4).build())

        assertEquals("JAVA PROFILE 1.0.3", heap.format)
        assertEquals(4, heap.idSize)
        assertEquals(1234L, heap.timestampMillis)
    }

    @Test
    fun `supports required top-level and heap sub-record tags`() {
        val fixture =
            HprofFixtureBuilder()
                .string(1, "Example")
                .loadClass(2, 1)
                .stackFrame()
                .stackTrace()
                .heapDump(
                    rootJniGlobal(),
                    rootJniLocal(),
                    rootJavaFrame(),
                    rootNativeStack(),
                    rootStickyClass(),
                    rootThreadBlock(),
                    rootMonitorUsed(),
                    rootThreadObject(),
                    rootUnknown(),
                    HprofFixtureBuilder().classDump(2, 24),
                    HprofFixtureBuilder().instanceDump(3, 2),
                ).heapDumpSegment(
                    HprofFixtureBuilder().objectArrayDump(4, 2, listOf(3)),
                    HprofFixtureBuilder().primitiveArrayDump(5, PrimitiveType.INT, 3),
                ).heapDumpEnd()
                .build()

        val heap = parser.parse(fixture)

        assertEquals("Example", heap.classes.single().name)
        assertEquals("Example", heap.instances.single().className)
        assertEquals("Example", heap.objectArrays.single().className)
        assertEquals("int[]", heap.primitiveArrays.single().className)
        assertEquals(emptyList(), heap.warnings)
    }

    @Test
    fun `records warning for unknown top-level tag and skips it`() {
        val heap =
            parser.parse(
                HprofFixtureBuilder()
                    .unknownRecord()
                    .string(1, "Example")
                    .build(),
            )

        assertEquals("JAVA PROFILE 1.0.3", heap.format)
        assertTrue(
            heap.warnings
                .single()
                .message
                .contains("Unknown top-level"),
        )
    }

    @Test
    fun `records warning for unknown heap sub-record without crashing`() {
        val heap =
            parser.parse(
                HprofFixtureBuilder()
                    .heapDump(HprofFixtureBuilder().unknownSubRecord())
                    .build(),
            )

        assertTrue(
            heap.warnings
                .single()
                .message
                .contains("Unknown heap dump sub-record"),
        )
    }

    @Test
    fun `supports four-byte and eight-byte ids`() {
        val fourByte =
            parser.parse(
                HprofFixtureBuilder(idSize = 4)
                    .string(1, "Four")
                    .loadClass(0x7fffffff, 1)
                    .heapDump(HprofFixtureBuilder(idSize = 4).classDump(0x7fffffff, 8))
                    .build(),
            )
        val eightByte =
            parser.parse(
                HprofFixtureBuilder(idSize = 8)
                    .string(1, "Eight")
                    .loadClass(0x1_0000_0000, 1)
                    .heapDump(HprofFixtureBuilder(idSize = 8).classDump(0x1_0000_0000, 8))
                    .build(),
            )

        assertEquals(0x7fffffff, fourByte.classes.single().objectId)
        assertEquals(0x1_0000_0000, eightByte.classes.single().objectId)
    }

    @Test
    fun `throws parse error for truncated hprof`() {
        val fixture =
            HprofFixtureBuilder()
                .heapDump(HprofFixtureBuilder().primitiveArrayDump(1, PrimitiveType.LONG, 2))
                .build()

        assertFailsWith<HprofParseException> {
            parser.parse(fixture.copyOf(fixture.size - 3))
        }
    }

    @Test
    fun `parses class instance object array and primitive array records`() {
        val heap =
            parser.parse(
                HprofFixtureBuilder()
                    .string(1, "Sample")
                    .loadClass(2, 1)
                    .heapDump(
                        HprofFixtureBuilder().classDump(2, 32),
                        HprofFixtureBuilder().instanceDump(3, 2, byteArrayOf(1, 2)),
                        HprofFixtureBuilder().objectArrayDump(4, 2, listOf(3, 3)),
                        HprofFixtureBuilder().primitiveArrayDump(5, PrimitiveType.BYTE, 4),
                    ).build(),
            )

        val instance = heap.instances.single()
        val objectArray = heap.objectArrays.single()
        assertEquals(32L, instance.shallowSize)
        assertTrue(instance.fieldBytes.isEmpty())
        assertEquals(24L, objectArray.shallowSize)
        assertEquals(listOf(3L, 3L), objectArray.elementIds)
        assertEquals(20L, heap.primitiveArrays.single().shallowSize)
    }

    @Test
    fun `resolves class metadata when records arrive out of order`() {
        val heap =
            parser.parse(
                HprofFixtureBuilder()
                    .heapDump(
                        HprofFixtureBuilder().instanceDump(3, 2, byteArrayOf(1, 2)),
                        HprofFixtureBuilder().objectArrayDump(4, 2, listOf(3)),
                        HprofFixtureBuilder().classDump(2, 40),
                    ).loadClass(2, 1)
                    .string(1, "LateClass")
                    .build(),
            )

        assertEquals("LateClass", heap.classes.single().name)
        assertEquals("LateClass", heap.instances.single().className)
        assertEquals(40L, heap.instances.single().shallowSize)
        assertEquals("LateClass", heap.objectArrays.single().className)
    }

    @Test
    fun `parses converted Android sample hprof resource without warnings`() {
        val heap = parser.parse(testResource("hprof/android-converted-sample.hprof"))

        assertEquals("JAVA PROFILE 1.0.2", heap.format)
        assertEquals("com.example.ConvertedSample", heap.classes.single().name)
        assertEquals(1, heap.instances.size)
        assertEquals("com.example.ConvertedSample", heap.instances.single().className)
        assertEquals(24L, heap.instances.single().shallowSize)
        assertEquals(emptyList(), heap.warnings)
    }

    @Test
    fun `continues through Android extension records and parses following objects`() {
        val extensions =
            listOf(0x89, 0x8a, 0x8b, 0x8c, 0x8d, 0x90)
                .mapIndexed { index, tag -> HprofFixtureBuilder().androidRoot(tag, 100L + index) }
        val fixture =
            HprofFixtureBuilder()
                .string(1, "com.example.AndroidObject")
                .string(3, "app")
                .loadClass(2, 1)
                .heapDump(
                    HprofFixtureBuilder().androidHeapDumpInfo(heapId = 1, heapNameStringId = 3),
                    *extensions.toTypedArray(),
                    HprofFixtureBuilder().androidJniMonitorRoot(106),
                    HprofFixtureBuilder().classDump(2, 24),
                    HprofFixtureBuilder().instanceDump(4, 2),
                    HprofFixtureBuilder().androidPrimitiveArrayNoData(5, PrimitiveType.BYTE, 10),
                ).build()

        val heap = parser.parse(fixture)

        assertEquals("com.example.AndroidObject", heap.instances.single().className)
        assertEquals(10, heap.primitiveArrays.single().elementCount)
        assertEquals(26L, heap.primitiveArrays.single().shallowSize)
        assertEquals(emptyList(), heap.warnings)
    }

    @Test
    fun `retains gc roots static references and decoded instance fields`() {
        val builder = HprofFixtureBuilder()
        val fixture =
            builder
                .string(1, "com.example.Holder")
                .string(2, "child")
                .string(3, "count")
                .loadClass(10, 1)
                .heapDump(
                    builder.androidRoot(0x05, 10),
                    builder.classDump(
                        classId = 10,
                        instanceSize = 24,
                        staticObjectFields = listOf(2L to 20L),
                        instanceFields =
                            listOf(
                                2L to PrimitiveType.OBJECT,
                                3L to PrimitiveType.INT,
                            ),
                    ),
                    builder.instanceDump(
                        objectId = 20,
                        classId = 10,
                        bytes = builder.objectValue(21) + builder.intValue(42),
                    ),
                    builder.instanceDump(objectId = 21, classId = 10),
                ).build()

        val heap = parser.parse(fixture)

        assertEquals(10L, heap.gcRoots.single().objectId)
        assertEquals(
            20L,
            heap.classes
                .single()
                .staticReferences
                .single()
                .targetObjectId,
        )
        assertEquals(
            21L,
            heap.instances
                .first { it.objectId == 20L }
                .references
                .single()
                .targetObjectId,
        )
        assertEquals(42L, heap.instances.first { it.objectId == 20L }.primitiveFields["count"])
    }

    private fun rootJniGlobal(): ByteArray = byteArrayOf(0x01) + id(1) + id(2)

    private fun rootJniLocal(): ByteArray = byteArrayOf(0x02) + id(1) + int(1) + int(1)

    private fun rootJavaFrame(): ByteArray = byteArrayOf(0x03) + id(1) + int(1) + int(1)

    private fun rootStickyClass(): ByteArray = byteArrayOf(0x05) + id(1)

    private fun rootNativeStack(): ByteArray = byteArrayOf(0x04) + id(1) + int(1)

    private fun rootThreadBlock(): ByteArray = byteArrayOf(0x06) + id(1) + int(1)

    private fun rootMonitorUsed(): ByteArray = byteArrayOf(0x07) + id(1)

    private fun rootThreadObject(): ByteArray = byteArrayOf(0x08) + id(1) + int(1) + int(1)

    private fun rootUnknown(): ByteArray = byteArrayOf(0xff.toByte()) + id(1)

    private fun id(value: Long): ByteArray = int(value.toInt())

    private fun int(value: Int): ByteArray =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

    private fun testResource(path: String): Path {
        val resource = assertNotNull(javaClass.classLoader.getResource(path))
        return Path.of(resource.toURI())
    }
}
