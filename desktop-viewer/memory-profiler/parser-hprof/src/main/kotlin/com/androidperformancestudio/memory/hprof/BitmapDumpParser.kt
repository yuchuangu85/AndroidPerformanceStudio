@file:Suppress("LongMethod", "MagicNumber", "MaxLineLength", "ReturnCount", "ThrowsCount", "TooManyFunctions")

package com.androidperformancestudio.memory.hprof

import com.androidperformancestudio.memory.model.BitmapDumpImage
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.CRC32

data class BitmapDumpParseResult(
    val recordedBitmapCount: Int,
    val discoveredBitmapCount: Int,
    val images: List<BitmapDumpImage>,
)

class BitmapDumpParseException(
    message: String,
) : RuntimeException(message)

/** Parses the API 35 `am dumpheap -b png` extension without retaining PNG payloads in heap memory. */
class BitmapDumpParser {
    fun parse(
        hprofFile: Path,
        imagesDirectory: Path,
        onProgress: (Int) -> Unit = {},
    ): BitmapDumpParseResult {
        require(Files.isRegularFile(hprofFile)) { "Bitmap HPROF does not exist: $hprofFile" }
        Files.createDirectories(imagesDirectory)
        return HprofFileReader(hprofFile).use { reader ->
            val header = Header.read(reader)
            onProgress(5)
            val metadata = readMetadata(reader, header)
            onProgress(35)
            val dumpValues = readDumpData(reader, header, metadata)
            onProgress(50)
            val bufferIds = readBufferIds(reader, header, dumpValues.buffersObjectId)
            val expected =
                bufferIds
                    .take(dumpValues.recordedCount)
                    .mapIndexedNotNull { index, objectId -> objectId.takeIf { it != 0L }?.let { index + 1 to it } }
            val recordsByArrayId = expected.groupBy(keySelector = { it.second }, valueTransform = { it.first })
            val images = extractImages(reader, header, recordsByArrayId, imagesDirectory, onProgress)
            onProgress(100)
            BitmapDumpParseResult(
                recordedBitmapCount = dumpValues.recordedCount,
                discoveredBitmapCount = dumpValues.discoveredCount,
                images = images.sortedBy(BitmapDumpImage::recordIndex),
            )
        }
    }

    private fun readMetadata(
        reader: HprofFileReader,
        header: Header,
    ): DumpMetadata {
        val strings = linkedMapOf<Long, String>()
        val classNameStringIds = linkedMapOf<Long, Long>()
        reader.forEachTopRecord(header) { tag, body, end ->
            when (tag) {
                STRING_IN_UTF8 -> {
                    val id = reader.readId(body, header.idSize)
                    val length = end - body - header.idSize
                    if (length > MAX_METADATA_STRING_BYTES) {
                        throw BitmapDumpParseException("HPROF string is too large at offset $body")
                    }
                    strings[id] = reader.readBytes(body + header.idSize, length.toInt()).decodeToString()
                }
                LOAD_CLASS -> {
                    val classObjectId = reader.readId(body + Int.SIZE_BYTES, header.idSize)
                    val nameId = reader.readId(body + Int.SIZE_BYTES + header.idSize + Int.SIZE_BYTES, header.idSize)
                    classNameStringIds[classObjectId] = nameId
                }
            }
        }
        val bitmapClassId =
            classNameStringIds.entries.firstOrNull { strings[it.value] == BITMAP_CLASS }?.key
                ?: throw BitmapDumpParseException("HPROF does not contain $BITMAP_CLASS")
        val dumpDataClassId =
            classNameStringIds.entries.firstOrNull { strings[it.value] == BITMAP_DUMP_DATA_CLASS }?.key
                ?: throw BitmapDumpParseException("HPROF does not contain Android Bitmap dump metadata")

        var dumpObjectId: Long? = null
        var dumpFields: List<DumpField>? = null
        reader.forEachHeapRecord(header) { tag, body, _ ->
            if (tag != CLASS_DUMP) return@forEachHeapRecord
            val parsed = parseClassDump(reader, body, header.idSize, strings)
            if (parsed.classObjectId == bitmapClassId) {
                dumpObjectId = parsed.staticObjectReferences[BITMAP_DUMP_FIELD]
            }
            if (parsed.classObjectId == dumpDataClassId) {
                dumpFields = parsed.instanceFields
            }
        }
        return DumpMetadata(
            dumpDataClassId = dumpDataClassId,
            dumpObjectId =
                dumpObjectId?.takeIf { it != 0L }
                    ?: throw BitmapDumpParseException("Bitmap.dumpData is empty"),
            dumpFields = dumpFields ?: throw BitmapDumpParseException("Bitmap DumpData fields are missing"),
        )
    }

    private fun readDumpData(
        reader: HprofFileReader,
        header: Header,
        metadata: DumpMetadata,
    ): DumpValues {
        var fieldBytes: ByteArray? = null
        reader.forEachHeapRecord(header) { tag, body, _ ->
            if (tag != INSTANCE_DUMP) return@forEachHeapRecord
            val objectId = reader.readId(body, header.idSize)
            if (objectId != metadata.dumpObjectId) return@forEachHeapRecord
            val classId = reader.readId(body + header.idSize + Int.SIZE_BYTES, header.idSize)
            if (classId != metadata.dumpDataClassId) return@forEachHeapRecord
            val lengthOffset = body + header.idSize + Int.SIZE_BYTES + header.idSize
            val byteCount = reader.readU4(lengthOffset)
            if (byteCount > MAX_DUMP_DATA_BYTES) {
                throw BitmapDumpParseException("Bitmap DumpData instance is too large: $byteCount bytes")
            }
            fieldBytes = reader.readBytes(lengthOffset + Int.SIZE_BYTES, byteCount.toInt())
        }
        val bytes = fieldBytes ?: throw BitmapDumpParseException("Bitmap DumpData instance was not found")
        var offset = 0
        val values = linkedMapOf<String, Long>()
        metadata.dumpFields.forEach { field ->
            val width = valueWidth(field.type, header.idSize)
            if (offset + width > bytes.size) {
                throw BitmapDumpParseException("Bitmap DumpData fields are truncated")
            }
            values[field.name] = readBigEndian(bytes, offset, width)
            offset += width
        }
        return DumpValues(
            recordedCount =
                values["count"]?.toInt()?.takeIf { it >= 0 }
                    ?: throw BitmapDumpParseException("Bitmap DumpData count is missing"),
            discoveredCount =
                values["max"]?.toInt()?.takeIf { it >= 0 }
                    ?: throw BitmapDumpParseException("Bitmap DumpData max is missing"),
            buffersObjectId =
                values["buffers"]?.takeIf { it != 0L }
                    ?: throw BitmapDumpParseException("Bitmap DumpData buffers are missing"),
        )
    }

    private fun readBufferIds(
        reader: HprofFileReader,
        header: Header,
        buffersObjectId: Long,
    ): List<Long> {
        var result: List<Long>? = null
        reader.forEachHeapRecord(header) { tag, body, _ ->
            if (tag != OBJECT_ARRAY_DUMP || reader.readId(body, header.idSize) != buffersObjectId) {
                return@forEachHeapRecord
            }
            val countOffset = body + header.idSize + Int.SIZE_BYTES
            val count = reader.readU4(countOffset)
            if (count > MAX_BITMAP_RECORDS) {
                throw BitmapDumpParseException("Bitmap buffer array is too large: $count entries")
            }
            val elements = countOffset + Int.SIZE_BYTES + header.idSize
            result = List(count.toInt()) { index -> reader.readId(elements + index.toLong() * header.idSize, header.idSize) }
        }
        return result ?: throw BitmapDumpParseException("Bitmap image buffer array was not found")
    }

    private fun extractImages(
        reader: HprofFileReader,
        header: Header,
        recordsByArrayId: Map<Long, List<Int>>,
        imagesDirectory: Path,
        onProgress: (Int) -> Unit,
    ): List<BitmapDumpImage> {
        val images = mutableListOf<BitmapDumpImage>()
        val totalRecords = recordsByArrayId.values.sumOf(List<Int>::size).coerceAtLeast(1)
        var visited = 0
        reader.forEachHeapRecord(header) { tag, body, _ ->
            if (tag != PRIMITIVE_ARRAY_DUMP) return@forEachHeapRecord
            val objectId = reader.readId(body, header.idSize)
            val recordIndexes = recordsByArrayId[objectId] ?: return@forEachHeapRecord
            val countOffset = body + header.idSize + Int.SIZE_BYTES
            val byteCount = reader.readU4(countOffset)
            val type = reader.readU1(countOffset + Int.SIZE_BYTES)
            if (type != BYTE_TYPE) return@forEachHeapRecord
            val payloadOffset = countOffset + Int.SIZE_BYTES + Byte.SIZE_BYTES
            val png = PngPayloadValidator.validate(reader, payloadOffset, byteCount) ?: return@forEachHeapRecord
            recordIndexes.forEach { recordIndex ->
                val tempFile = imagesDirectory.resolve(".bitmap-$recordIndex.tmp")
                val sha256 = reader.copyAndDigest(payloadOffset, byteCount, tempFile)
                val fileName =
                    "%04d_%dx%d_%dB_%s.png".format(
                        recordIndex,
                        png.width,
                        png.height,
                        byteCount,
                        sha256.take(12),
                    )
                val output = imagesDirectory.resolve(fileName)
                moveCompletedImage(tempFile, output)
                images +=
                    BitmapDumpImage(
                        recordIndex = recordIndex,
                        arrayObjectId = objectId,
                        file = output,
                        width = png.width,
                        height = png.height,
                        pngBytes = byteCount,
                        estimatedMemoryBytes = png.width.toLong() * png.height.toLong() * ARGB_8888_BYTES_PER_PIXEL,
                        sha256 = sha256,
                    )
                visited++
                onProgress(50 + (visited * 50 / totalRecords))
            }
        }
        return images
    }

    private fun parseClassDump(
        reader: HprofFileReader,
        body: Long,
        idSize: Int,
        strings: Map<Long, String>,
    ): ParsedClassDump {
        val classId = reader.readId(body, idSize)
        var cursor = body + idSize + Int.SIZE_BYTES + RESERVED_CLASS_IDS.toLong() * idSize + Int.SIZE_BYTES
        val constantCount = reader.readU2(cursor)
        cursor += Short.SIZE_BYTES
        repeat(constantCount) {
            val type = reader.readU1(cursor + Short.SIZE_BYTES)
            cursor += Short.SIZE_BYTES + Byte.SIZE_BYTES + valueWidth(type, idSize)
        }
        val staticReferences = linkedMapOf<String, Long>()
        val staticCount = reader.readU2(cursor)
        cursor += Short.SIZE_BYTES
        repeat(staticCount) {
            val nameId = reader.readId(cursor, idSize)
            val type = reader.readU1(cursor + idSize)
            val valueOffset = cursor + idSize + Byte.SIZE_BYTES
            if (type == OBJECT_TYPE) {
                staticReferences[strings[nameId].orEmpty()] = reader.readId(valueOffset, idSize)
            }
            cursor = valueOffset + valueWidth(type, idSize)
        }
        val fieldCount = reader.readU2(cursor)
        cursor += Short.SIZE_BYTES
        val fields =
            List(fieldCount) {
                val nameId = reader.readId(cursor, idSize)
                val type = reader.readU1(cursor + idSize)
                cursor += idSize + Byte.SIZE_BYTES
                DumpField(strings[nameId].orEmpty(), type)
            }
        return ParsedClassDump(classId, staticReferences, fields)
    }

    private fun valueWidth(
        type: Int,
        idSize: Int,
    ): Int =
        when (type) {
            OBJECT_TYPE -> idSize
            BOOLEAN_TYPE, BYTE_TYPE -> 1
            CHAR_TYPE, SHORT_TYPE -> 2
            FLOAT_TYPE, INT_TYPE -> 4
            DOUBLE_TYPE, LONG_TYPE -> 8
            else -> throw BitmapDumpParseException("Unknown HPROF value type $type")
        }

    private fun readBigEndian(
        bytes: ByteArray,
        offset: Int,
        count: Int,
    ): Long {
        var value = 0L
        repeat(count) { value = (value shl 8) or (bytes[offset + it].toLong() and 0xffL) }
        return value
    }

    private fun moveCompletedImage(
        tempFile: Path,
        output: Path,
    ) {
        try {
            Files.move(tempFile, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tempFile, output, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private data class DumpMetadata(
        val dumpDataClassId: Long,
        val dumpObjectId: Long,
        val dumpFields: List<DumpField>,
    )

    private data class DumpField(
        val name: String,
        val type: Int,
    )

    private data class DumpValues(
        val recordedCount: Int,
        val discoveredCount: Int,
        val buffersObjectId: Long,
    )

    private data class ParsedClassDump(
        val classObjectId: Long,
        val staticObjectReferences: Map<String, Long>,
        val instanceFields: List<DumpField>,
    )

    internal data class Header(
        val recordStart: Long,
        val idSize: Int,
    ) {
        companion object {
            fun read(reader: HprofFileReader): Header {
                var end = 0L
                while (end < MAX_HEADER_BYTES && end < reader.size && reader.readU1(end) != 0) end++
                if (end == reader.size || end == MAX_HEADER_BYTES) {
                    throw BitmapDumpParseException("Truncated HPROF header")
                }
                val text = reader.readBytes(0, end.toInt()).decodeToString()
                if (!text.startsWith("JAVA PROFILE")) throw BitmapDumpParseException("File is not a valid HPROF")
                val idSize = reader.readU4(end + 1).toInt()
                if (idSize != Int.SIZE_BYTES && idSize != Long.SIZE_BYTES) {
                    throw BitmapDumpParseException("Unsupported HPROF id size $idSize")
                }
                return Header(end + 1 + Int.SIZE_BYTES + Long.SIZE_BYTES, idSize)
            }
        }
    }

    companion object {
        private const val BITMAP_CLASS = "android.graphics.Bitmap"
        private const val BITMAP_DUMP_DATA_CLASS = "android.graphics.Bitmap\$DumpData"
        private const val BITMAP_DUMP_FIELD = "dumpData"
        private const val STRING_IN_UTF8 = 0x01
        private const val LOAD_CLASS = 0x02
        private const val CLASS_DUMP = 0x20
        private const val INSTANCE_DUMP = 0x21
        private const val OBJECT_ARRAY_DUMP = 0x22
        private const val PRIMITIVE_ARRAY_DUMP = 0x23
        private const val OBJECT_TYPE = 2
        private const val BOOLEAN_TYPE = 4
        private const val CHAR_TYPE = 5
        private const val FLOAT_TYPE = 6
        private const val DOUBLE_TYPE = 7
        private const val BYTE_TYPE = 8
        private const val SHORT_TYPE = 9
        private const val INT_TYPE = 10
        private const val LONG_TYPE = 11
        private const val RESERVED_CLASS_IDS = 6
        private const val MAX_HEADER_BYTES = 256L
        private const val MAX_METADATA_STRING_BYTES = 4L * 1024L * 1024L
        private const val MAX_DUMP_DATA_BYTES = 1024L * 1024L
        private const val MAX_BITMAP_RECORDS = 1_000_000L
        private const val ARGB_8888_BYTES_PER_PIXEL = 4L
    }
}

private data class PngInfo(
    val width: Int,
    val height: Int,
)

private object PngPayloadValidator {
    private val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

    fun validate(
        reader: HprofFileReader,
        offset: Long,
        byteCount: Long,
    ): PngInfo? {
        if (byteCount < MINIMUM_PNG_BYTES || !reader.readBytes(offset, signature.size).contentEquals(signature)) return null
        var cursor = offset + signature.size
        val end = offset + byteCount
        var width = 0
        var height = 0
        var first = true
        while (cursor + PNG_CHUNK_OVERHEAD <= end) {
            val length = reader.readU4(cursor)
            if (length > MAX_PNG_CHUNK_BYTES) return null
            val chunkEnd = cursor + PNG_CHUNK_OVERHEAD + length
            if (chunkEnd > end) return null
            val type = reader.readBytes(cursor + Int.SIZE_BYTES, 4)
            val storedCrc = reader.readU4(cursor + Int.SIZE_BYTES + 4 + length)
            val crc = CRC32()
            reader.updateCrc(crc, cursor + Int.SIZE_BYTES, 4 + length)
            if (crc.value != storedCrc) return null
            if (first) {
                if (!type.contentEquals(IHDR) || length != 13L) return null
                val dimensions = reader.readBytes(cursor + Int.SIZE_BYTES + 4, 8)
                width = readInt(dimensions, 0)
                height = readInt(dimensions, 4)
                if (width <= 0 || height <= 0) return null
                first = false
            }
            cursor = chunkEnd
            if (type.contentEquals(IEND)) return PngInfo(width, height).takeIf { cursor == end }
        }
        return null
    }

    private fun readInt(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private val IHDR = "IHDR".encodeToByteArray()
    private val IEND = "IEND".encodeToByteArray()
    private const val PNG_CHUNK_OVERHEAD = 12L
    private const val MINIMUM_PNG_BYTES = 33L
    private const val MAX_PNG_CHUNK_BYTES = 512L * 1024L * 1024L
}

internal class HprofFileReader(
    path: Path,
) : AutoCloseable {
    private val channel = FileChannel.open(path, StandardOpenOption.READ)
    val size: Long = channel.size()

    fun readU1(position: Long): Int = readBytes(position, 1)[0].toInt() and 0xff

    fun readU2(position: Long): Int {
        val bytes = readBytes(position, 2)
        return ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)
    }

    fun readU4(position: Long): Long {
        val bytes = readBytes(position, 4)
        return ((bytes[0].toLong() and 0xffL) shl 24) or
            ((bytes[1].toLong() and 0xffL) shl 16) or
            ((bytes[2].toLong() and 0xffL) shl 8) or
            (bytes[3].toLong() and 0xffL)
    }

    fun readId(
        position: Long,
        idSize: Int,
    ): Long {
        val bytes = readBytes(position, idSize)
        var result = 0L
        bytes.forEach { result = (result shl 8) or (it.toLong() and 0xffL) }
        return result
    }

    fun readBytes(
        position: Long,
        count: Int,
    ): ByteArray {
        if (position < 0 || count < 0 || position + count > size) throw EOFException("Truncated HPROF at $position")
        val buffer = ByteBuffer.allocate(count)
        readFully(position, buffer)
        return buffer.array()
    }

    fun forEachTopRecord(
        header: BitmapDumpParser.Header,
        block: (Int, Long, Long) -> Unit,
    ) {
        var offset = header.recordStart
        while (offset < size) {
            if (offset + TOP_RECORD_HEADER_BYTES > size) throw BitmapDumpParseException("Truncated HPROF record at $offset")
            val tag = readU1(offset)
            val body = offset + TOP_RECORD_HEADER_BYTES
            val end = body + readU4(offset + 5)
            if (end < body || end > size) throw BitmapDumpParseException("Truncated HPROF record at $offset")
            block(tag, body, end)
            offset = end
        }
    }

    fun forEachHeapRecord(
        header: BitmapDumpParser.Header,
        block: (Int, Long, Long) -> Unit,
    ) {
        forEachTopRecord(header) { tag, body, end ->
            if (tag != HEAP_DUMP && tag != HEAP_DUMP_SEGMENT) return@forEachTopRecord
            var offset = body
            while (offset < end) {
                val subTag = readU1(offset)
                val recordBody = offset + 1
                val next = nextHeapRecordOffset(subTag, recordBody, header.idSize)
                if (next <= offset ||
                    next > end
                ) {
                    throw BitmapDumpParseException("Truncated heap record 0x${subTag.toString(16)} at $offset")
                }
                block(subTag, recordBody, next)
                offset = next
            }
        }
    }

    private fun nextHeapRecordOffset(
        tag: Int,
        body: Long,
        idSize: Int,
    ): Long =
        when (tag) {
            ROOT_UNKNOWN, ROOT_STICKY_CLASS, ROOT_MONITOR_USED, ROOT_INTERNED_STRING, ROOT_FINALIZING,
            ROOT_DEBUGGER, ROOT_REFERENCE_CLEANUP, ROOT_VM_INTERNAL, ROOT_UNREACHABLE,
            -> body + idSize
            ROOT_JNI_GLOBAL -> body + idSize * 2L
            ROOT_JNI_LOCAL, ROOT_JAVA_FRAME, ROOT_THREAD_OBJECT, ROOT_JNI_MONITOR -> body + idSize + 8L
            ROOT_NATIVE_STACK, ROOT_THREAD_BLOCK -> body + idSize + 4L
            HEAP_DUMP_INFO -> body + 4L + idSize
            CLASS_DUMP -> classDumpEnd(body, idSize)
            INSTANCE_DUMP -> {
                val lengthOffset = body + idSize + 4L + idSize
                lengthOffset + 4L + readU4(lengthOffset)
            }
            OBJECT_ARRAY_DUMP -> {
                val countOffset = body + idSize + 4L
                countOffset + 4L + idSize + readU4(countOffset) * idSize
            }
            PRIMITIVE_ARRAY_DUMP, PRIMITIVE_ARRAY_NODATA_DUMP -> {
                val countOffset = body + idSize + 4L
                val count = readU4(countOffset)
                val type = readU1(countOffset + 4L)
                countOffset + 5L + if (tag == PRIMITIVE_ARRAY_DUMP) count * primitiveWidth(type, idSize) else 0L
            }
            else -> throw BitmapDumpParseException("Unknown heap record 0x${tag.toString(16)}")
        }

    private fun classDumpEnd(
        body: Long,
        idSize: Int,
    ): Long {
        var cursor = body + idSize + 4L + 6L * idSize + 4L
        val constants = readU2(cursor)
        cursor += 2L
        repeat(constants) {
            val type = readU1(cursor + 2L)
            cursor += 3L + primitiveWidth(type, idSize)
        }
        val statics = readU2(cursor)
        cursor += 2L
        repeat(statics) {
            val type = readU1(cursor + idSize)
            cursor += idSize + 1L + primitiveWidth(type, idSize)
        }
        val fields = readU2(cursor)
        return cursor + 2L + fields * (idSize + 1L)
    }

    private fun primitiveWidth(
        type: Int,
        idSize: Int,
    ): Int =
        when (type) {
            2 -> idSize
            4, 8 -> 1
            5, 9 -> 2
            6, 10 -> 4
            7, 11 -> 8
            else -> throw BitmapDumpParseException("Unknown HPROF value type $type")
        }

    fun updateCrc(
        crc: CRC32,
        position: Long,
        count: Long,
    ) {
        forEachChunk(position, count) { bytes, length -> crc.update(bytes, 0, length) }
    }

    fun copyAndDigest(
        position: Long,
        count: Long,
        output: Path,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newOutputStream(output).use { stream ->
            forEachChunk(position, count) { bytes, length ->
                digest.update(bytes, 0, length)
                stream.write(bytes, 0, length)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun forEachChunk(
        position: Long,
        count: Long,
        action: (ByteArray, Int) -> Unit,
    ) {
        var cursor = position
        var remaining = count
        val bytes = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            val length = minOf(bytes.size.toLong(), remaining).toInt()
            val buffer = ByteBuffer.wrap(bytes, 0, length)
            readFully(cursor, buffer)
            action(bytes, length)
            cursor += length
            remaining -= length
        }
    }

    private fun readFully(
        position: Long,
        buffer: ByteBuffer,
    ) {
        var cursor = position
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, cursor)
            if (read < 0) throw EOFException("Truncated HPROF at $cursor")
            if (read == 0) continue
            cursor += read
        }
    }

    override fun close() = channel.close()

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val TOP_RECORD_HEADER_BYTES = 9L
        private const val HEAP_DUMP = 0x0c
        private const val HEAP_DUMP_SEGMENT = 0x1c
        private const val ROOT_JNI_GLOBAL = 0x01
        private const val ROOT_JNI_LOCAL = 0x02
        private const val ROOT_JAVA_FRAME = 0x03
        private const val ROOT_NATIVE_STACK = 0x04
        private const val ROOT_STICKY_CLASS = 0x05
        private const val ROOT_THREAD_BLOCK = 0x06
        private const val ROOT_MONITOR_USED = 0x07
        private const val ROOT_THREAD_OBJECT = 0x08
        private const val ROOT_INTERNED_STRING = 0x89
        private const val ROOT_FINALIZING = 0x8a
        private const val ROOT_DEBUGGER = 0x8b
        private const val ROOT_REFERENCE_CLEANUP = 0x8c
        private const val ROOT_VM_INTERNAL = 0x8d
        private const val ROOT_JNI_MONITOR = 0x8e
        private const val ROOT_UNREACHABLE = 0x90
        private const val ROOT_UNKNOWN = 0xff
        private const val HEAP_DUMP_INFO = 0xfe
        private const val CLASS_DUMP = 0x20
        private const val INSTANCE_DUMP = 0x21
        private const val OBJECT_ARRAY_DUMP = 0x22
        private const val PRIMITIVE_ARRAY_DUMP = 0x23
        private const val PRIMITIVE_ARRAY_NODATA_DUMP = 0xc3
    }
}
