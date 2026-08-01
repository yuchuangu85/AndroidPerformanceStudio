package com.androidperformancestudio.memory.hprof

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BitmapDumpParserTest {
    @Test
    fun `extracts every bitmap record and preserves duplicate payloads`() {
        val root = createTempDirectory("bitmap-parser")
        val hprof = root.resolve("bitmap.hprof")
        val png = ONE_PIXEL_PNG.copyOf()
        Files.write(hprof, bitmapHprof(png, listOf(400, 400), discoveredCount = 3))
        val progress = mutableListOf<Int>()

        val result = BitmapDumpParser().parse(hprof, root.resolve("images"), progress::add)

        assertEquals(2, result.recordedBitmapCount)
        assertEquals(3, result.discoveredBitmapCount)
        assertEquals(2, result.images.size)
        assertEquals(listOf(1, 2), result.images.map { it.recordIndex })
        assertEquals(1, result.images.single { it.recordIndex == 1 }.width)
        assertEquals(1, result.images.single { it.recordIndex == 1 }.height)
        assertEquals(
            1,
            result.images
                .map { it.sha256 }
                .distinct()
                .size,
        )
        result.images.forEach { assertContentEquals(png, Files.readAllBytes(it.file)) }
        assertEquals(100, progress.last())
    }

    @Test
    fun `invalid png crc is not exported`() {
        val root = createTempDirectory("bitmap-parser-crc")
        val hprof = root.resolve("bitmap.hprof")
        val corrupt = ONE_PIXEL_PNG.copyOf().also { it[45] = (it[45].toInt() xor 0x01).toByte() }
        Files.write(hprof, bitmapHprof(corrupt, listOf(400)))

        val result = BitmapDumpParser().parse(hprof, root.resolve("images"))

        assertTrue(result.images.isEmpty())
    }

    @Test
    fun `rejects files without hprof header`() {
        val root = createTempDirectory("bitmap-parser-invalid")
        val file = root.resolve("broken.hprof")
        Files.writeString(file, "not a heap dump")

        assertFailsWith<BitmapDumpParseException> {
            BitmapDumpParser().parse(file, root.resolve("images"))
        }
    }

    private fun bitmapHprof(
        png: ByteArray,
        bufferIds: List<Int>,
        discoveredCount: Int = bufferIds.size,
    ): ByteArray =
        ByteArrayOutputStream()
            .also { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.write("JAVA PROFILE 1.0.3".encodeToByteArray())
                    output.writeByte(0)
                    output.writeInt(Int.SIZE_BYTES)
                    output.writeLong(0L)
                    output.stringRecord(1, "android.graphics.Bitmap")
                    output.stringRecord(2, "android.graphics.Bitmap\$DumpData")
                    output.stringRecord(3, "dumpData")
                    output.stringRecord(4, "count")
                    output.stringRecord(5, "max")
                    output.stringRecord(6, "buffers")
                    output.stringRecord(7, "[Ljava.lang.Object;")
                    output.loadClassRecord(1, 100, 1)
                    output.loadClassRecord(2, 101, 2)
                    output.loadClassRecord(3, 102, 7)
                    output.record(HEAP_DUMP) {
                        writeBitmapClassDump()
                        writeDumpDataClassDump()
                        writeByte(INSTANCE_DUMP)
                        writeInt(200)
                        writeInt(0)
                        writeInt(101)
                        writeInt(12)
                        writeInt(bufferIds.size)
                        writeInt(discoveredCount)
                        writeInt(300)
                        writeByte(OBJECT_ARRAY_DUMP)
                        writeInt(300)
                        writeInt(0)
                        writeInt(bufferIds.size)
                        writeInt(102)
                        bufferIds.forEach(::writeInt)
                        writeByte(PRIMITIVE_ARRAY_DUMP)
                        writeInt(400)
                        writeInt(0)
                        writeInt(png.size)
                        writeByte(BYTE_TYPE)
                        write(png)
                    }
                }
            }.toByteArray()

    private fun DataOutputStream.writeBitmapClassDump() {
        writeByte(CLASS_DUMP)
        writeInt(100)
        writeInt(0)
        repeat(6) { writeInt(0) }
        writeInt(0)
        writeShort(0)
        writeShort(1)
        writeInt(3)
        writeByte(OBJECT_TYPE)
        writeInt(200)
        writeShort(0)
    }

    private fun DataOutputStream.writeDumpDataClassDump() {
        writeByte(CLASS_DUMP)
        writeInt(101)
        writeInt(0)
        repeat(6) { writeInt(0) }
        writeInt(12)
        writeShort(0)
        writeShort(0)
        writeShort(3)
        writeInt(4)
        writeByte(INT_TYPE)
        writeInt(5)
        writeByte(INT_TYPE)
        writeInt(6)
        writeByte(OBJECT_TYPE)
    }

    private fun DataOutputStream.stringRecord(
        id: Int,
        value: String,
    ) {
        record(STRING_IN_UTF8) {
            writeInt(id)
            write(value.encodeToByteArray())
        }
    }

    private fun DataOutputStream.loadClassRecord(
        serial: Int,
        classId: Int,
        nameId: Int,
    ) {
        record(LOAD_CLASS) {
            writeInt(serial)
            writeInt(classId)
            writeInt(0)
            writeInt(nameId)
        }
    }

    private fun DataOutputStream.record(
        tag: Int,
        payload: DataOutputStream.() -> Unit,
    ) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(payload)
        writeByte(tag)
        writeInt(0)
        writeInt(bytes.size())
        write(bytes.toByteArray())
    }

    companion object {
        private val ONE_PIXEL_PNG =
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            )
        private const val STRING_IN_UTF8 = 0x01
        private const val LOAD_CLASS = 0x02
        private const val HEAP_DUMP = 0x0c
        private const val CLASS_DUMP = 0x20
        private const val INSTANCE_DUMP = 0x21
        private const val OBJECT_ARRAY_DUMP = 0x22
        private const val PRIMITIVE_ARRAY_DUMP = 0x23
        private const val OBJECT_TYPE = 2
        private const val BYTE_TYPE = 8
        private const val INT_TYPE = 10
    }
}
