@file:Suppress("MagicNumber")

package com.androidperformancestudio.memory.hprof

import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapPrimitiveArrayValueSource
import com.androidperformancestudio.memory.model.PrimitiveType
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HprofPrimitiveArrayValueSourceTest {
    private val parser = HprofParser()

    @Test
    fun `byte parsing reads all primitive types with four and eight byte ids`() {
        for (idSize in listOf(4, 8)) {
            assertPrimitiveValues(parser.parse(primitiveFixture(idSize)))
        }
    }

    @Test
    fun `streamed parsing reads all primitive types across mapped record bases with both id widths`() {
        for (idSize in listOf(4, 8)) {
            withHprof(primitiveFixture(idSize)) { file ->
                assertPrimitiveValues(parser.parse(file))
            }
        }
    }

    @Test
    fun `streamed parsing preserves byte parser metadata heap names warnings and progress`() {
        for (idSize in listOf(4, 8)) {
            val bytes = primitiveFixture(idSize)
            withHprof(bytes) { file ->
                val progress = mutableListOf<Int>()
                val streamed = parser.parse(file, progress::add)
                val buffered = parser.parse(bytes)

                assertEquals(withoutValueSources(buffered), withoutValueSources(streamed))
                assertEquals(file, streamed.rawHprofFile)
                assertEquals(0, progress.first())
                assertEquals(100, progress.last())
                assertTrue(progress.zipWithNext().all { (before, after) -> before <= after })
                assertEquals(
                    listOf("App", "Image"),
                    streamed.primitiveArrays.map { streamed.heapByObjectId[it.objectId] }.distinct(),
                )
                assertEquals(1, streamed.warnings.size)
            }
        }
    }

    @Test
    fun `converted sample fixture has identical streamed and byte metadata`() {
        val resource = assertNotNull(javaClass.classLoader.getResource("hprof/android-converted-sample.hprof"))
        val file = Path.of(resource.toURI())

        assertEquals(
            withoutValueSources(parser.parse(Files.readAllBytes(file))),
            withoutValueSources(parser.parse(file)),
        )
    }

    @Test
    fun `each primitive type supports independent bounded pages and rejects invalid ranges`() {
        val pageSize = HeapPrimitiveArrayValueSource.MAX_READ_ELEMENTS
        for ((type, examples) in primitiveCases()) {
            val expected = LongArray(pageSize * 2 + 7) { examples[it % examples.size] }
            val bytes = HprofFixtureBuilder().heapDump(arrayRecord(1L, type, expected)).build()
            withHprof(bytes) { file ->
                for (heap in listOf(parser.parse(bytes), parser.parse(file))) {
                    val source = assertNotNull(heap.primitiveArrays.single().valueSource)
                    assertContentEquals(expected.copyOfRange(0, pageSize), source.read(0, pageSize))
                    assertContentEquals(expected.copyOfRange(pageSize, pageSize * 2), source.read(pageSize, pageSize))
                    assertContentEquals(expected.takeLast(7).toLongArray(), source.read(pageSize * 2, 7))
                    assertContentEquals(expected.copyOfRange(3, 8), source.read(3, 5))
                    assertContentEquals(longArrayOf(), source.read(0, 0))
                    assertContentEquals(longArrayOf(), source.read(expected.size, 0))
                    assertNull(source.read(-1, 1))
                    assertNull(source.read(0, -1))
                    assertNull(source.read(0, pageSize + 1))
                    assertNull(source.read(expected.size, 1))
                    assertNull(source.read(expected.size + 1, 0))
                    assertNull(source.read(expected.size - 1, 2))
                    assertNull(source.read(Int.MAX_VALUE, 1))
                    assertNull(source.read(1, Int.MAX_VALUE))
                    assertNull(source.read(Int.MIN_VALUE, Int.MIN_VALUE))

                    assertNotNull(source.read(0, 1))[0] = 99L
                    assertContentEquals(expected.copyOfRange(0, 1), source.read(0, 1))
                }
            }
        }
    }

    @Test
    fun `nodata remains unavailable while an evidenced empty array permits empty reads`() {
        val builder = HprofFixtureBuilder()
        val bytes =
            builder
                .heapDump(
                    builder.androidPrimitiveArrayNoData(1, PrimitiveType.BYTE, 4),
                    builder.androidPrimitiveArrayNoData(2, PrimitiveType.LONG, 0),
                    arrayRecord(3, PrimitiveType.CHAR, longArrayOf()),
                ).build()
        withHprof(bytes) { file ->
            for (heap in listOf(parser.parse(bytes), parser.parse(file))) {
                assertNull(heap.primitiveArrays[0].valueSource)
                assertNull(heap.primitiveArrays[1].valueSource)
                val source = assertNotNull(heap.primitiveArrays[2].valueSource)
                assertContentEquals(longArrayOf(), source.read(0, 0))
                assertNull(source.read(0, 1))
                assertNull(source.read(1, 0))
            }
        }
    }

    @Test
    fun `truncated payloads invalid counts and oversized byte spans fail during parsing`() {
        val invalidRecords =
            listOf(
                arrayRecord(1, PrimitiveType.LONG, longArrayOf(1), declaredCount = 2),
                arrayRecord(1, PrimitiveType.LONG, longArrayOf(), declaredCount = -1),
                arrayRecord(1, PrimitiveType.LONG, longArrayOf(), declaredCount = Int.MAX_VALUE),
            )
        for (record in invalidRecords) {
            val bytes =
                HprofFixtureBuilder()
                    .heapDump(record)
                    .string(2, "Following records cannot supply missing payload")
                    .build()
            assertFailsWith<HprofParseException> { parser.parse(bytes) }
            withHprof(bytes) { file -> assertFailsWith<HprofParseException> { parser.parse(file) } }
        }
        val valid = HprofFixtureBuilder().heapDump(arrayRecord(1, PrimitiveType.INT, longArrayOf(42))).build()
        val truncated = valid.copyOf(valid.size - 1)
        assertFailsWith<HprofParseException> { parser.parse(truncated) }
        withHprof(truncated) { file -> assertFailsWith<HprofParseException> { parser.parse(file) } }
    }

    @Test
    fun `file sources reject missing truncated resized and modified evidence including empty reads`() {
        val changes: List<(Path) -> Unit> =
            listOf(
                { Files.delete(it) },
                { file -> FileChannel.open(file, StandardOpenOption.WRITE).use { it.truncate(1L) } },
                { file -> Files.write(file, byteArrayOf(0), StandardOpenOption.APPEND) },
                { file ->
                    val previous = Files.getLastModifiedTime(file)
                    Files.setLastModifiedTime(file, FileTime.fromMillis(previous.toMillis() + 2000))
                },
                { file ->
                    val replacement = Files.readAllBytes(file).also { it[it.lastIndex] = 43 }
                    val previous = Files.getLastModifiedTime(file)
                    Files.write(file, replacement)
                    Files.setLastModifiedTime(file, FileTime.fromMillis(previous.toMillis() + 2000))
                },
            )
        val bytes = HprofFixtureBuilder().heapDump(arrayRecord(1, PrimitiveType.INT, longArrayOf(42))).build()
        for (change in changes) {
            withHprof(bytes) { file ->
                val source =
                    assertNotNull(
                        parser
                            .parse(file)
                            .primitiveArrays
                            .single()
                            .valueSource,
                    )
                assertContentEquals(longArrayOf(42), source.read(0, 1))
                change(file)
                assertNull(source.read(0, 1))
                assertNull(source.read(0, 0))
            }
        }
    }

    @Test
    fun `file replacement with identical size and mtime is rejected when file keys are available`() {
        val bytes = HprofFixtureBuilder().heapDump(arrayRecord(1, PrimitiveType.INT, longArrayOf(42))).build()
        withHprof(bytes) { file ->
            val original = Files.readAttributes(file, BasicFileAttributes::class.java)
            val source =
                assertNotNull(
                    parser
                        .parse(file)
                        .primitiveArrays
                        .single()
                        .valueSource,
                )
            withHprof(bytes) { replacement ->
                Files.setLastModifiedTime(replacement, original.lastModifiedTime())
                Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING)
                val current = Files.readAttributes(file, BasicFileAttributes::class.java)
                assertEquals(original.size(), current.size())
                assertEquals(original.lastModifiedTime(), current.lastModifiedTime())
                if (original.fileKey() != null) {
                    assertTrue(original.fileKey() != current.fileKey())
                    assertNull(source.read(0, 1))
                }
            }
        }
    }

    @Test
    fun `changes during streamed parsing do not publish a heap with stale value sources`() {
        val bytes = HprofFixtureBuilder().heapDump(arrayRecord(1, PrimitiveType.INT, longArrayOf(42))).build()
        withHprof(bytes) { file ->
            val previous = Files.getLastModifiedTime(file)
            assertFailsWith<HprofParseException> {
                parser.parse(file) { progress ->
                    if (progress == 0) Files.setLastModifiedTime(file, FileTime.fromMillis(previous.toMillis() + 2000))
                }
            }
        }
    }

    @Test
    fun `byte sources remain independent of a missing source path hint`() {
        val bytes = HprofFixtureBuilder().heapDump(arrayRecord(1, PrimitiveType.INT, longArrayOf(42))).build()
        withHprof(bytes) { file ->
            val heap = parser.parse(bytes, rawHprofFile = file)
            Files.delete(file)

            assertEquals(file, heap.rawHprofFile)
            assertContentEquals(longArrayOf(42), assertNotNull(heap.primitiveArrays.single().valueSource).read(0, 1))
        }
    }

    @Test
    fun `large sparse streamed payload is read only in bounded pages`() {
        val count = 16 * 1024 * 1024
        val builder = HprofFixtureBuilder()
        val header = builder.build()
        val arrayHeader = arrayRecord(1, PrimitiveType.LONG, longArrayOf(), declaredCount = count)
        withHprof(header) { file ->
            FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
                channel.position(header.size.toLong())
                writeFully(channel, recordHeader(0x1c, arrayHeader.size + count * Long.SIZE_BYTES))
                writeFully(channel, arrayHeader)
                channel.position(channel.position() + count.toLong() * Long.SIZE_BYTES - Long.SIZE_BYTES)
                writeFully(channel, ByteBuffer.allocate(Long.SIZE_BYTES).putLong(Long.MIN_VALUE).array())
            }
            val array = parser.parse(file).primitiveArrays.single()
            val source = assertNotNull(array.valueSource)

            assertEquals(count, array.elementCount)
            assertContentEquals(longArrayOf(0), source.read(0, 1))
            assertContentEquals(longArrayOf(Long.MIN_VALUE), source.read(count - 1, 1))
            assertNull(source.read(0, count))
        }
    }

    @Test
    fun `streamed payload offsets remain long beyond two gibibytes`() {
        val header = HprofFixtureBuilder().build()
        val record = arrayRecord(1, PrimitiveType.INT, longArrayOf(0x12345678))
        withHprof(header) { file ->
            FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
                channel.position(header.size.toLong())
                repeat(2) {
                    val paddingBytes = 1024 * 1024 * 1024
                    writeFully(channel, recordHeader(0x7e, paddingBytes))
                    channel.position(channel.position() + paddingBytes)
                }
                assertTrue(channel.position() > Int.MAX_VALUE.toLong())
                writeFully(channel, recordHeader(0x1c, record.size))
                writeFully(channel, record)
            }
            val heap = parser.parse(file)

            assertEquals(2, heap.warnings.size)
            assertContentEquals(
                longArrayOf(0x12345678),
                assertNotNull(heap.primitiveArrays.single().valueSource).read(0, 1),
            )
        }
    }

    private fun assertPrimitiveValues(heap: HeapDump) {
        for ((index, example) in primitiveCases().withIndex()) {
            val (type, expected) = example
            val array = heap.primitiveArrays[index]
            assertEquals(type, array.primitiveType)
            assertEquals(expected.size, array.elementCount)
            assertContentEquals(expected, assertNotNull(array.valueSource).read(0, expected.size), type.name)
        }
    }

    private fun primitiveCases(): List<Pair<PrimitiveType, LongArray>> =
        listOf(
            PrimitiveType.BOOLEAN to longArrayOf(0, 1, 2, 255),
            PrimitiveType.CHAR to longArrayOf(0, 65, 0xd800, 0xffff),
            PrimitiveType.FLOAT to
                longArrayOf(
                    0,
                    1.5f.toRawBits().toLong(),
                    (-0.0f).toRawBits().toLong(),
                    0x7fc01234,
                    Float.NEGATIVE_INFINITY.toRawBits().toLong(),
                ),
            PrimitiveType.DOUBLE to
                longArrayOf(
                    0,
                    1.5.toRawBits(),
                    (-0.0).toRawBits(),
                    0x7ff8000000001234,
                    Double.NEGATIVE_INFINITY.toRawBits(),
                ),
            PrimitiveType.BYTE to longArrayOf(0, 127, 128, 255),
            PrimitiveType.SHORT to longArrayOf(0, 32767, 32768, 65535),
            PrimitiveType.INT to longArrayOf(0, 42, -1, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()),
            PrimitiveType.LONG to longArrayOf(0, 42, -1, Long.MIN_VALUE, Long.MAX_VALUE),
        )

    private fun primitiveFixture(idSize: Int): ByteArray {
        val builder = HprofFixtureBuilder(idSize)
        builder
            .string(1, "com.example.Values")
            .string(2, "app heap")
            .string(3, "image heap")
            .loadClass(10, 1)
            .stackFrame()
            .stackTrace()
            .unknownRecord()
            .heapDump(
                builder.androidHeapDumpInfo(1, 2),
                builder.classDump(10, 24),
                builder.instanceDump(11, 10),
                builder.objectArrayDump(12, 10, listOf(11)),
                builder.androidRoot(0x05, 10),
            )
        for ((index, example) in primitiveCases().withIndex()) {
            builder.heapDumpSegment(
                builder.androidHeapDumpInfo(index + 2, if (index % 2 == 0) 2 else 3),
                arrayRecord(100L + index, example.first, example.second, idSize = idSize),
            )
        }
        return builder.heapDumpEnd().build()
    }

    private fun arrayRecord(
        objectId: Long,
        type: PrimitiveType,
        values: LongArray,
        idSize: Int = 4,
        declaredCount: Int = values.size,
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeByte(0x23)
            if (idSize == 4) output.writeInt(objectId.toInt()) else output.writeLong(objectId)
            output.writeInt(0)
            output.writeInt(declaredCount)
            output.writeByte(type.hprofType)
            for (value in values) {
                when (type.byteWidth) {
                    1 -> output.writeByte(value.toInt())
                    2 -> output.writeShort(value.toInt())
                    4 -> output.writeInt(value.toInt())
                    8 -> output.writeLong(value)
                    else -> error("Not a primitive array type: $type")
                }
            }
        }
        return bytes.toByteArray()
    }

    private fun withoutValueSources(heap: HeapDump): HeapDump =
        heap.copy(rawHprofFile = null, primitiveArrays = heap.primitiveArrays.map { it.copy(valueSource = null) })

    private fun recordHeader(
        tag: Int,
        length: Int,
    ): ByteArray =
        ByteBuffer
            .allocate(9)
            .put(tag.toByte())
            .putInt(0)
            .putInt(length)
            .array()

    private fun writeFully(
        channel: FileChannel,
        bytes: ByteArray,
    ) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    private fun withHprof(
        bytes: ByteArray,
        block: (Path) -> Unit,
    ) {
        val file = Files.createTempFile("hprof-primitive-values-", ".hprof")
        try {
            Files.write(file, bytes)
            block(file)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
