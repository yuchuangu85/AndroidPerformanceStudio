@file:Suppress("MagicNumber")

package com.androidperformancestudio.memory.hprof

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class HprofRecordBoundsTest {
    @Test
    fun `object array count is checked before allocation in both entrypoints`() {
        val root = createTempDirectory("hprof-object-array-bounds")
        try {
            for (count in listOf(Int.MAX_VALUE, -1)) {
                val bytes =
                    record(17) { output ->
                        output.writeByte(0x22)
                        output.writeInt(1)
                        output.writeInt(0)
                        output.writeInt(count)
                        output.writeInt(2)
                    }
                val file = root.resolve("array-$count.hprof")
                Files.write(file, bytes)
                assertFailsWith<HprofParseException> { HprofParser().parse(bytes) }
                assertFailsWith<HprofParseException> { HprofParser().parse(file) }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `object ids crossing file reader windows match byte parsing`() {
        val root = createTempDirectory("hprof-window-boundary")
        try {
            val count = 20_000
            val bytes =
                record(17 + count * 4) { output ->
                    output.writeByte(0x22)
                    output.writeInt(1)
                    output.writeInt(0)
                    output.writeInt(count)
                    output.writeInt(2)
                    repeat(count) { output.writeInt(it + 3) }
                }
            val file = root.resolve("windows.hprof")
            Files.write(file, bytes)
            val expected = HprofParser().parse(bytes).objectArrays.single()
            assertEquals(expected, HprofParser().parse(file).objectArrays.single())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unsigned record length above two GiB stays sparse and primitive values stay lazy`() {
        val root = createTempDirectory("hprof-unsigned-record")
        try {
            val file = root.resolve("large.hprof")
            val count = Int.MAX_VALUE
            val payloadLength = count.toLong() + 14
            val prefix =
                record(payloadLength.toInt()) { output ->
                    output.writeByte(0x23)
                    output.writeInt(1)
                    output.writeInt(0)
                    output.writeInt(count)
                    output.writeByte(8)
                }
            FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                channel.write(ByteBuffer.wrap(prefix))
                channel.position(prefix.size.toLong() + count - 1)
                channel.write(ByteBuffer.wrap(byteArrayOf(42)))
                channel.write(ByteBuffer.wrap(byteArrayOf(0x2c, 0, 0, 0, 0, 0, 0, 0, 0)))
            }
            val heap = HprofParser().parse(file)
            val array = heap.primitiveArrays.single()
            assertEquals(count, array.elementCount)
            val values = assertNotNull(array.valueSource)
            assertContentEquals(longArrayOf(0), values.read(0, 1))
            assertContentEquals(longArrayOf(42), values.read(count - 1, 1))
            val indexFile = root.resolve("large.idx")
            val index = HprofRecordIndexer().build(file, indexFile)
            assertEquals(payloadLength, index.records.first().payloadLength)
            assertEquals(listOf(0x1c, 0x2c), index.records.map { it.tag })
            assertEquals(index, HprofRecordIndexer().read(indexFile))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unsigned record length without its payload is rejected by both parsers and indexer`() {
        val root = createTempDirectory("hprof-truncated-unsigned")
        try {
            val bytes = record(Int.MIN_VALUE) {}
            val file = root.resolve("truncated.hprof")
            Files.write(file, bytes)
            assertFailsWith<HprofParseException> { HprofParser().parse(bytes) }
            assertFailsWith<HprofParseException> { HprofParser().parse(file) }
            assertFailsWith<HprofParseException> { HprofRecordIndexer().build(file, root.resolve("index")) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun record(
        length: Int,
        payload: (DataOutputStream) -> Unit,
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write("JAVA PROFILE 1.0.3".encodeToByteArray())
            output.writeByte(0)
            output.writeInt(4)
            output.writeLong(0)
            output.writeByte(0x1c)
            output.writeInt(0)
            output.writeInt(length)
            payload(output)
        }
        return bytes.toByteArray()
    }
}
