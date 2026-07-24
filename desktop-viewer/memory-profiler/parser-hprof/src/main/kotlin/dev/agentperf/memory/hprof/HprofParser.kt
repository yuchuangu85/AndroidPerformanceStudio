@file:Suppress("MagicNumber", "TooManyFunctions", "MaxLineLength")

package dev.agentperf.memory.hprof

import dev.agentperf.memory.model.HeapClass
import dev.agentperf.memory.model.HeapDump
import dev.agentperf.memory.model.HeapInstance
import dev.agentperf.memory.model.HeapObjectArray
import dev.agentperf.memory.model.HeapPrimitiveArray
import dev.agentperf.memory.model.MemoryWarning
import dev.agentperf.memory.model.PrimitiveType
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.math.max

class HprofParseException(
    message: String,
) : RuntimeException(message)

class HprofParser {
    fun parse(path: Path): HeapDump =
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val size = channel.size()
            if (size > Int.MAX_VALUE) {
                throw HprofParseException("HPROF is too large to map: $size bytes")
            }
            parse(channel.map(FileChannel.MapMode.READ_ONLY, 0L, size), path)
        }

    fun parse(
        bytes: ByteArray,
        rawHprofFile: Path? = null,
    ): HeapDump = parse(ByteBuffer.wrap(bytes), rawHprofFile)

    private fun parse(
        buffer: ByteBuffer,
        rawHprofFile: Path?,
    ): HeapDump {
        val reader = HprofReader(buffer)
        val header = reader.readHeaderString()
        val idSize = reader.readInt()
        if (idSize != ID_SIZE_4 && idSize != ID_SIZE_8) {
            throw HprofParseException("Unsupported HPROF id size $idSize")
        }
        val timestamp = reader.readLong()
        val state = ParserState(idSize = idSize)

        while (reader.hasRemaining()) {
            val recordOffset = reader.position.toLong()
            val tag = reader.readUnsignedByte()
            reader.skip(Int.SIZE_BYTES)
            val length = reader.readInt()
            if (length < 0) {
                throw HprofParseException("Negative HPROF record length at offset $recordOffset")
            }
            val payload = reader.readSubReader(length, state.idSize)
            parseRecord(tag = tag, payload = payload, state = state, offset = recordOffset)
        }

        val classes =
            state.classesById.values
                .map { heapClass -> heapClass.copy(name = state.classNameForObjectId(heapClass.objectId)) }
                .sortedBy { it.objectId }
        val classesById = classes.associateBy(HeapClass::objectId)
        return HeapDump(
            rawHprofFile = rawHprofFile,
            format = header,
            idSize = idSize,
            timestampMillis = timestamp,
            classes = classes,
            instances =
                state.instances.map { instance ->
                    val heapClass = classesById[instance.classObjectId]
                    instance.copy(
                        className = heapClass?.name ?: HeapClass.UNKNOWN_CLASS_NAME,
                        shallowSize = heapClass?.instanceSize?.takeIf { it > 0L } ?: instance.shallowSize,
                    )
                },
            objectArrays =
                state.objectArrays.map { array ->
                    array.copy(className = classesById[array.arrayClassObjectId]?.name ?: HeapClass.UNKNOWN_CLASS_NAME)
                },
            primitiveArrays = state.primitiveArrays,
            warnings = state.warnings,
        )
    }

    private fun parseRecord(
        tag: Int,
        payload: HprofReader,
        state: ParserState,
        offset: Long,
    ) {
        when (tag) {
            STRING_IN_UTF8 -> parseString(payload, state)
            LOAD_CLASS -> parseLoadClass(payload, state)
            STACK_FRAME,
            STACK_TRACE,
            HEAP_DUMP_END,
            -> Unit
            HEAP_DUMP,
            HEAP_DUMP_SEGMENT,
            -> parseHeapDump(payload, state, offset)
            else ->
                state.warnings +=
                    MemoryWarning(
                        message = "Unknown top-level HPROF record tag 0x${tag.toString(16)} skipped",
                        offset = offset,
                    )
        }
    }

    private fun parseString(
        reader: HprofReader,
        state: ParserState,
    ) {
        val id = reader.readId()
        state.stringsById[id] = reader.readBytes(reader.remaining()).decodeToString()
    }

    private fun parseLoadClass(
        reader: HprofReader,
        state: ParserState,
    ) {
        reader.readInt()
        val classObjectId = reader.readId()
        reader.skip(Int.SIZE_BYTES)
        val nameStringId = reader.readId()
        state.classNameStringIdByObjectId[classObjectId] = nameStringId
        state.upsertClass(classObjectId, null)
    }

    private fun parseHeapDump(
        reader: HprofReader,
        state: ParserState,
        recordOffset: Long,
    ) {
        while (reader.hasRemaining()) {
            val subOffset = recordOffset + reader.relativePosition
            val subTag = reader.readUnsignedByte()
            val metadataSize = heapMetadataPayloadSize(subTag, state.idSize)
            if (metadataSize != null) {
                reader.skip(metadataSize)
                continue
            }
            when (subTag) {
                CLASS_DUMP -> parseClassDump(reader, state)
                INSTANCE_DUMP -> parseInstanceDump(reader, state)
                OBJECT_ARRAY_DUMP -> parseObjectArrayDump(reader, state)
                PRIMITIVE_ARRAY_DUMP -> parsePrimitiveArrayDump(reader, state, hasData = true)
                PRIMITIVE_ARRAY_NODATA_DUMP -> parsePrimitiveArrayDump(reader, state, hasData = false)
                else -> {
                    state.warnings +=
                        MemoryWarning(
                            message = "Unknown heap dump sub-record tag 0x${subTag.toString(
                                16,
                            )} at segment offset ${reader.relativePosition - 1}; stopped parsing current segment",
                            offset = subOffset,
                        )
                    return
                }
            }
        }
    }

    private fun heapMetadataPayloadSize(
        tag: Int,
        idSize: Int,
    ): Int? =
        when (tag) {
            ROOT_JNI_GLOBAL -> idSize * 2
            ROOT_JNI_LOCAL,
            ROOT_JAVA_FRAME,
            ROOT_THREAD_OBJECT,
            ROOT_JNI_MONITOR,
            -> idSize + (Int.SIZE_BYTES * 2)
            ROOT_NATIVE_STACK,
            ROOT_THREAD_BLOCK,
            -> idSize + Int.SIZE_BYTES
            ROOT_STICKY_CLASS,
            ROOT_MONITOR_USED,
            ROOT_UNKNOWN,
            ROOT_INTERNED_STRING,
            ROOT_FINALIZING,
            ROOT_DEBUGGER,
            ROOT_REFERENCE_CLEANUP,
            ROOT_VM_INTERNAL,
            ROOT_UNREACHABLE,
            -> idSize
            HEAP_DUMP_INFO -> Int.SIZE_BYTES + idSize
            else -> null
        }

    private fun parseClassDump(
        reader: HprofReader,
        state: ParserState,
    ) {
        val classObjectId = reader.readId()
        reader.skip(Int.SIZE_BYTES)
        repeat(RESERVED_CLASS_IDS) { reader.readId() }
        val instanceSize = reader.readInt().toLong()
        val constantPoolCount = reader.readUnsignedShort()
        repeat(constantPoolCount) {
            reader.skip(Short.SIZE_BYTES)
            reader.skipValue(reader.readUnsignedByte())
        }
        val staticFieldCount = reader.readUnsignedShort()
        repeat(staticFieldCount) {
            reader.readId()
            reader.skipValue(reader.readUnsignedByte())
        }
        val instanceFieldCount = reader.readUnsignedShort()
        repeat(instanceFieldCount) {
            reader.readId()
            reader.skip(Byte.SIZE_BYTES)
        }
        state.upsertClass(classObjectId, instanceSize)
    }

    private fun parseInstanceDump(
        reader: HprofReader,
        state: ParserState,
    ) {
        val objectId = reader.readId()
        reader.skip(Int.SIZE_BYTES)
        val classObjectId = reader.readId()
        val byteCount = reader.readInt()
        reader.skip(byteCount)
        val heapClass = state.classesById[classObjectId]
        state.instances +=
            HeapInstance(
                objectId = objectId,
                classObjectId = classObjectId,
                className = state.classNameForObjectId(classObjectId),
                shallowSize = heapClass?.instanceSize ?: max(byteCount, 0).toLong(),
            )
    }

    private fun parseObjectArrayDump(
        reader: HprofReader,
        state: ParserState,
    ) {
        val objectId = reader.readId()
        reader.skip(Int.SIZE_BYTES)
        val elementCount = reader.readInt()
        val arrayClassObjectId = reader.readId()
        reader.skipRepeated(elementCount, state.idSize)
        state.objectArrays +=
            HeapObjectArray(
                objectId = objectId,
                arrayClassObjectId = arrayClassObjectId,
                className = state.classNameForObjectId(arrayClassObjectId),
                elementCount = elementCount,
                shallowSize = OBJECT_ARRAY_HEADER_BYTES + (elementCount.toLong() * state.idSize),
            )
    }

    private fun parsePrimitiveArrayDump(
        reader: HprofReader,
        state: ParserState,
        hasData: Boolean,
    ) {
        val objectId = reader.readId()
        reader.skip(Int.SIZE_BYTES)
        val elementCount = reader.readInt()
        val primitiveType = PrimitiveType.fromHprofType(reader.readUnsignedByte())
        if (hasData) {
            reader.skipRepeated(elementCount, primitiveType.byteWidth)
        }
        state.primitiveArrays +=
            HeapPrimitiveArray(
                objectId = objectId,
                primitiveType = primitiveType,
                elementCount = elementCount,
                shallowSize = PRIMITIVE_ARRAY_HEADER_BYTES + (elementCount.toLong() * primitiveType.byteWidth),
            )
    }

    private data class ParserState(
        val idSize: Int,
        val stringsById: MutableMap<Long, String> = linkedMapOf(),
        val classNameStringIdByObjectId: MutableMap<Long, Long> = linkedMapOf(),
        val classesById: MutableMap<Long, HeapClass> = linkedMapOf(),
        val instances: MutableList<HeapInstance> = mutableListOf(),
        val objectArrays: MutableList<HeapObjectArray> = mutableListOf(),
        val primitiveArrays: MutableList<HeapPrimitiveArray> = mutableListOf(),
        val warnings: MutableList<MemoryWarning> = mutableListOf(),
    ) {
        fun classNameForObjectId(classId: Long): String =
            classNameStringIdByObjectId[classId]
                ?.let(stringsById::get)
                ?: HeapClass.UNKNOWN_CLASS_NAME

        fun upsertClass(
            classId: Long,
            instanceSize: Long?,
        ) {
            val current = classesById[classId]
            classesById[classId] =
                HeapClass(
                    objectId = classId,
                    name = classNameForObjectId(classId),
                    instanceSize = instanceSize ?: current?.instanceSize ?: 0L,
                )
        }
    }

    companion object {
        const val OBJECT_ARRAY_HEADER_BYTES = 16L
        const val PRIMITIVE_ARRAY_HEADER_BYTES = 16L

        private const val ID_SIZE_4 = 4
        private const val ID_SIZE_8 = 8
        private const val STRING_IN_UTF8 = 0x01
        private const val LOAD_CLASS = 0x02
        private const val STACK_FRAME = 0x04
        private const val STACK_TRACE = 0x05
        private const val HEAP_DUMP = 0x0c
        private const val HEAP_DUMP_SEGMENT = 0x1c
        private const val HEAP_DUMP_END = 0x2c
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
        private const val RESERVED_CLASS_IDS = 6
    }
}

private class HprofReader(
    private val bytes: ByteBuffer,
    private val idSize: Int = 0,
    private val start: Int = 0,
    private val limit: Int = bytes.limit(),
) {
    var position: Int = start
        private set

    val relativePosition: Int
        get() = position - start

    fun hasRemaining(): Boolean = position < limit

    fun remaining(): Int = limit - position

    fun readHeaderString(): String {
        val out = ByteArrayOutputStream()
        while (hasRemaining()) {
            val byte = bytes.get(position++)
            if (byte.toInt() == 0) {
                return out.toByteArray().decodeToString()
            }
            out.write(byte.toInt())
        }
        throw HprofParseException("Truncated HPROF header: missing NUL terminator")
    }

    fun readUnsignedByte(): Int {
        requireAvailable(Byte.SIZE_BYTES)
        return bytes.get(position++).toInt() and BYTE_MASK
    }

    fun readUnsignedShort(): Int {
        requireAvailable(Short.SIZE_BYTES)
        val value =
            ((bytes.get(position).toInt() and BYTE_MASK) shl 8) or
                (bytes.get(position + 1).toInt() and BYTE_MASK)
        position += Short.SIZE_BYTES
        return value
    }

    fun readInt(): Int {
        requireAvailable(Int.SIZE_BYTES)
        val value =
            ((bytes.get(position).toInt() and BYTE_MASK) shl 24) or
                ((bytes.get(position + 1).toInt() and BYTE_MASK) shl 16) or
                ((bytes.get(position + 2).toInt() and BYTE_MASK) shl 8) or
                (bytes.get(position + 3).toInt() and BYTE_MASK)
        position += Int.SIZE_BYTES
        return value
    }

    fun readLong(): Long {
        requireAvailable(Long.SIZE_BYTES)
        var value = 0L
        repeat(Long.SIZE_BYTES) {
            value = (value shl 8) or (bytes.get(position++).toLong() and BYTE_MASK_LONG)
        }
        return value
    }

    fun readId(): Long =
        when (idSize) {
            Int.SIZE_BYTES -> readInt().toLong() and UINT_MASK
            Long.SIZE_BYTES -> readLong()
            else -> throw HprofParseException("Cannot read id with invalid id size $idSize")
        }

    fun readBytes(count: Int): ByteArray {
        requireAvailable(count)
        val result = ByteArray(count)
        bytes.duplicate().position(position).get(result)
        position += count
        return result
    }

    fun skip(count: Int) {
        requireAvailable(count)
        position += count
    }

    fun skipRepeated(
        count: Int,
        width: Int,
    ) {
        if (count < 0 || width < 0) {
            throw HprofParseException("Negative element count or width at offset $position")
        }
        val byteCount = count.toLong() * width
        if (byteCount > Int.MAX_VALUE) {
            throw HprofParseException("Array payload is too large at offset $position: $byteCount bytes")
        }
        skip(byteCount.toInt())
    }

    fun readSubReader(
        count: Int,
        idSize: Int,
    ): HprofReader {
        requireAvailable(count)
        val subReader = HprofReader(bytes, idSize, start = position, limit = position + count)
        position += count
        return subReader
    }

    fun skipValue(type: Int) {
        val width = PrimitiveType.fromHprofType(type).byteWidth.takeIf { it > 0 } ?: idSize
        skip(width)
    }

    private fun requireAvailable(count: Int) {
        if (count < 0) {
            throw HprofParseException("Negative byte count $count at offset $position")
        }
        if (count > remaining()) {
            throw HprofParseException("Truncated HPROF at offset $position: need $count bytes, have ${remaining()}")
        }
    }

    companion object {
        private const val BYTE_MASK = 0xff
        private const val BYTE_MASK_LONG = 0xffL
        private const val UINT_MASK = 0xffffffffL
    }
}
