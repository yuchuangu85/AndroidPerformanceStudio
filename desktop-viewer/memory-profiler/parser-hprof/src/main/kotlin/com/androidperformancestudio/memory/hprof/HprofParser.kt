@file:Suppress("CyclomaticComplexMethod", "LongMethod", "MagicNumber", "TooManyFunctions", "MaxLineLength")

package com.androidperformancestudio.memory.hprof

import com.androidperformancestudio.memory.model.HeapClass
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapField
import com.androidperformancestudio.memory.model.HeapInstance
import com.androidperformancestudio.memory.model.HeapObjectArray
import com.androidperformancestudio.memory.model.HeapPrimitiveArray
import com.androidperformancestudio.memory.model.HeapRoot
import com.androidperformancestudio.memory.model.HeapRootKind
import com.androidperformancestudio.memory.model.MemoryHeapNames
import com.androidperformancestudio.memory.model.MemoryWarning
import com.androidperformancestudio.memory.model.ObjectReference
import com.androidperformancestudio.memory.model.PrimitiveType
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import kotlin.math.max

class HprofParseException(
    message: String,
) : RuntimeException(message)

class HprofParser {
    fun parse(
        path: Path,
        onProgress: (Int) -> Unit = {},
    ): HeapDump =
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val size = channel.size()
            if (size > Int.MAX_VALUE) {
                throw HprofParseException("HPROF is too large to map: $size bytes")
            }
            parse(channel.map(FileChannel.MapMode.READ_ONLY, 0L, size), path, onProgress)
        }

    fun parse(
        bytes: ByteArray,
        rawHprofFile: Path? = null,
    ): HeapDump = parse(ByteBuffer.wrap(bytes), rawHprofFile, {})

    private fun parse(
        buffer: ByteBuffer,
        rawHprofFile: Path?,
        onProgress: (Int) -> Unit,
    ): HeapDump {
        val reader = HprofReader(buffer)
        val header = reader.readHeaderString()
        val idSize = reader.readInt()
        if (idSize != ID_SIZE_4 && idSize != ID_SIZE_8) {
            throw HprofParseException("Unsupported HPROF id size $idSize")
        }
        val timestamp = reader.readLong()
        val state = ParserState(idSize = idSize)
        onProgress(0)

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
            onProgress(((reader.position.toDouble() / buffer.limit()) * 100.0).toInt().coerceIn(0, 100))
        }
        onProgress(100)

        val classes =
            state.classesById.values
                .map { heapClass ->
                    val metadata = state.classMetadataById[heapClass.objectId]
                    heapClass.copy(
                        name = state.classNameForObjectId(heapClass.objectId),
                        superClassObjectId = metadata?.superClassObjectId ?: 0L,
                        instanceFields =
                            metadata?.instanceFields.orEmpty().map { field ->
                                HeapField(
                                    name = state.stringsById[field.nameStringId] ?: "<field-${field.nameStringId}>",
                                    type = field.type,
                                )
                            },
                        staticReferences =
                            metadata?.staticReferences.orEmpty().map { reference ->
                                ObjectReference(
                                    fieldName =
                                        "static ${state.stringsById[reference.nameStringId] ?: "<field-${reference.nameStringId}>"}",
                                    targetObjectId = reference.targetObjectId,
                                )
                            },
                        classLoaderObjectId = metadata?.classLoaderObjectId ?: 0L,
                    )
                }.sortedBy { it.objectId }
        val classesById = classes.associateBy(HeapClass::objectId)
        val instances = state.instances.map { state.decodeInstance(it, classesById) }
        val objectClassNames =
            buildMap {
                classes.forEach { put(it.objectId, it.name) }
                instances.forEach { put(it.objectId, it.className) }
                state.objectArrays.forEach { array ->
                    put(array.objectId, classesById[array.arrayClassObjectId]?.name ?: HeapClass.UNKNOWN_CLASS_NAME)
                }
                state.primitiveArrays.forEach { put(it.objectId, it.className) }
            }
        return HeapDump(
            rawHprofFile = rawHprofFile,
            format = header,
            idSize = idSize,
            timestampMillis = timestamp,
            classes = classes,
            instances =
                instances.map { instance ->
                    instance.copy(
                        references =
                            instance.references.map { reference ->
                                reference.copy(
                                    targetClassName =
                                        objectClassNames[reference.targetObjectId] ?: HeapClass.UNKNOWN_CLASS_NAME,
                                )
                            },
                    )
                },
            objectArrays =
                state.objectArrays.map { array ->
                    array.copy(className = classesById[array.arrayClassObjectId]?.name ?: HeapClass.UNKNOWN_CLASS_NAME)
                },
            primitiveArrays = state.primitiveArrays,
            gcRoots = state.gcRoots.distinctBy { it.objectId to it.kind },
            warnings = state.warnings,
            heapByObjectId = state.heapByObjectId,
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
            val rootKind = heapRootKind(subTag)
            if (rootKind != null) {
                parseHeapRoot(reader, state, subTag, rootKind)
                continue
            }
            when (subTag) {
                HEAP_DUMP_INFO -> {
                    // u4 heap serial number + ID of the heap-name string; all following objects
                    // belong to this heap until the next HEAP_DUMP_INFO record.
                    reader.skip(Int.SIZE_BYTES)
                    val heapNameStringId = reader.readId()
                    state.currentHeap = normalizeHeapName(state.stringsById[heapNameStringId])
                }
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

    private fun heapRootKind(tag: Int): HeapRootKind? =
        when (tag) {
            ROOT_JNI_GLOBAL -> HeapRootKind.JNI_GLOBAL
            ROOT_JNI_LOCAL -> HeapRootKind.JNI_LOCAL
            ROOT_JAVA_FRAME -> HeapRootKind.JAVA_FRAME
            ROOT_NATIVE_STACK -> HeapRootKind.NATIVE_STACK
            ROOT_STICKY_CLASS -> HeapRootKind.STICKY_CLASS
            ROOT_THREAD_BLOCK -> HeapRootKind.THREAD_BLOCK
            ROOT_MONITOR_USED -> HeapRootKind.MONITOR_USED
            ROOT_THREAD_OBJECT -> HeapRootKind.THREAD_OBJECT
            ROOT_INTERNED_STRING -> HeapRootKind.INTERNED_STRING
            ROOT_FINALIZING -> HeapRootKind.FINALIZING
            ROOT_DEBUGGER -> HeapRootKind.DEBUGGER
            ROOT_REFERENCE_CLEANUP -> HeapRootKind.REFERENCE_CLEANUP
            ROOT_VM_INTERNAL -> HeapRootKind.VM_INTERNAL
            ROOT_JNI_MONITOR -> HeapRootKind.JNI_MONITOR
            ROOT_UNREACHABLE -> HeapRootKind.UNREACHABLE
            ROOT_UNKNOWN -> HeapRootKind.UNKNOWN
            else -> null
        }

    private fun parseHeapRoot(
        reader: HprofReader,
        state: ParserState,
        tag: Int,
        kind: HeapRootKind,
    ) {
        val objectId = reader.readId()
        val remainingBytes =
            when (tag) {
                ROOT_JNI_GLOBAL -> state.idSize
                ROOT_JNI_LOCAL,
                ROOT_JAVA_FRAME,
                ROOT_THREAD_OBJECT,
                ROOT_JNI_MONITOR,
                -> Int.SIZE_BYTES * 2
                ROOT_NATIVE_STACK,
                ROOT_THREAD_BLOCK,
                -> Int.SIZE_BYTES
                else -> 0
            }
        reader.skip(remainingBytes)
        if (objectId != 0L) state.gcRoots += HeapRoot(objectId, kind)
    }

    private fun parseClassDump(
        reader: HprofReader,
        state: ParserState,
    ) {
        val classObjectId = reader.readId()
        reader.skip(Int.SIZE_BYTES)
        val superClassObjectId = reader.readId()
        val classLoaderObjectId = reader.readId()
        repeat(RESERVED_CLASS_IDS - 2) { reader.readId() }
        val instanceSize = reader.readInt().toLong()
        val constantPoolCount = reader.readUnsignedShort()
        repeat(constantPoolCount) {
            reader.skip(Short.SIZE_BYTES)
            reader.skipValue(reader.readUnsignedByte())
        }
        val staticFieldCount = reader.readUnsignedShort()
        val staticReferences = mutableListOf<RawStaticReference>()
        repeat(staticFieldCount) {
            val nameStringId = reader.readId()
            val type = PrimitiveType.fromHprofType(reader.readUnsignedByte())
            val value = reader.readValue(type)
            if (type == PrimitiveType.OBJECT && value != 0L) {
                staticReferences += RawStaticReference(nameStringId, value)
            }
        }
        val instanceFieldCount = reader.readUnsignedShort()
        val instanceFields = mutableListOf<RawField>()
        repeat(instanceFieldCount) {
            instanceFields +=
                RawField(
                    nameStringId = reader.readId(),
                    type = PrimitiveType.fromHprofType(reader.readUnsignedByte()),
                )
        }
        state.upsertClass(
            classId = classObjectId,
            instanceSize = instanceSize,
            metadata = ClassMetadata(superClassObjectId, classLoaderObjectId, instanceFields, staticReferences),
        )
    }

    private fun parseInstanceDump(
        reader: HprofReader,
        state: ParserState,
    ) {
        val objectId = reader.readId()
        reader.skip(Int.SIZE_BYTES)
        val classObjectId = reader.readId()
        val byteCount = reader.readInt()
        val fieldBytes = reader.readBytes(byteCount)
        val heapClass = state.classesById[classObjectId]
        state.heapByObjectId[objectId] = state.currentHeap
        state.instances +=
            RawInstance(
                objectId = objectId,
                classObjectId = classObjectId,
                shallowSize = heapClass?.instanceSize ?: max(byteCount, 0).toLong(),
                fieldBytes = fieldBytes,
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
        val elementIds = List(elementCount) { reader.readId() }
        state.heapByObjectId[objectId] = state.currentHeap
        state.objectArrays +=
            HeapObjectArray(
                objectId = objectId,
                arrayClassObjectId = arrayClassObjectId,
                className = state.classNameForObjectId(arrayClassObjectId),
                elementCount = elementCount,
                elementIds = elementIds,
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
        state.heapByObjectId[objectId] = state.currentHeap
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
        val classMetadataById: MutableMap<Long, ClassMetadata> = linkedMapOf(),
        val instances: MutableList<RawInstance> = mutableListOf(),
        val objectArrays: MutableList<HeapObjectArray> = mutableListOf(),
        val primitiveArrays: MutableList<HeapPrimitiveArray> = mutableListOf(),
        val gcRoots: MutableList<HeapRoot> = mutableListOf(),
        val warnings: MutableList<MemoryWarning> = mutableListOf(),
        val heapByObjectId: MutableMap<Long, String> = mutableMapOf(),
        var currentHeap: String = MemoryHeapNames.DEFAULT,
    ) {
        fun classNameForObjectId(classId: Long): String =
            classNameStringIdByObjectId[classId]
                ?.let(stringsById::get)
                ?: HeapClass.UNKNOWN_CLASS_NAME

        fun upsertClass(
            classId: Long,
            instanceSize: Long?,
            metadata: ClassMetadata? = null,
        ) {
            val current = classesById[classId]
            classesById[classId] =
                HeapClass(
                    objectId = classId,
                    name = classNameForObjectId(classId),
                    instanceSize = instanceSize ?: current?.instanceSize ?: 0L,
                )
            metadata?.let { classMetadataById[classId] = it }
        }

        fun decodeInstance(
            raw: RawInstance,
            classesById: Map<Long, HeapClass>,
        ): HeapInstance {
            val fields = fieldsForClass(raw.classObjectId)
            val reader = HprofReader(ByteBuffer.wrap(raw.fieldBytes), idSize)
            val references = mutableListOf<ObjectReference>()
            val primitiveFields = linkedMapOf<String, Long>()
            fields.forEach { field ->
                if (!reader.hasRemaining()) return@forEach
                val name = stringsById[field.nameStringId] ?: "<field-${field.nameStringId}>"
                val value = reader.readValue(field.type)
                if (field.type == PrimitiveType.OBJECT) {
                    references += ObjectReference(name, value)
                } else {
                    primitiveFields[name] = value
                }
            }
            return HeapInstance(
                objectId = raw.objectId,
                classObjectId = raw.classObjectId,
                className = classesById[raw.classObjectId]?.name ?: HeapClass.UNKNOWN_CLASS_NAME,
                shallowSize = classesById[raw.classObjectId]?.instanceSize?.takeIf { it > 0L } ?: raw.shallowSize,
                references = references,
                primitiveFields = primitiveFields,
            )
        }

        private fun fieldsForClass(classObjectId: Long): List<RawField> {
            val result = mutableListOf<RawField>()
            val visited = hashSetOf<Long>()
            var current = classObjectId
            while (current != 0L && visited.add(current)) {
                val metadata = classMetadataById[current] ?: break
                result += metadata.instanceFields
                current = metadata.superClassObjectId
            }
            return result
        }
    }

    private data class RawField(
        val nameStringId: Long,
        val type: PrimitiveType,
    )

    private data class RawStaticReference(
        val nameStringId: Long,
        val targetObjectId: Long,
    )

    private data class ClassMetadata(
        val superClassObjectId: Long,
        val classLoaderObjectId: Long,
        val instanceFields: List<RawField>,
        val staticReferences: List<RawStaticReference>,
    )

    private data class RawInstance(
        val objectId: Long,
        val classObjectId: Long,
        val shallowSize: Long,
        val fieldBytes: ByteArray,
    )

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

    /** Maps a raw HPROF heap-name string to the canonical [MemoryHeapNames] label used by the UI. */
    private fun normalizeHeapName(raw: String?): String {
        val lower = raw?.lowercase(Locale.ROOT).orEmpty()
        return when {
            "image" in lower -> MemoryHeapNames.IMAGE
            "zygote" in lower -> MemoryHeapNames.ZYGOTE
            "app" in lower -> MemoryHeapNames.APP
            else -> MemoryHeapNames.DEFAULT
        }
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

    fun readValue(type: PrimitiveType): Long =
        when (type) {
            PrimitiveType.OBJECT -> readId()
            PrimitiveType.BOOLEAN,
            PrimitiveType.BYTE,
            -> readUnsignedByte().toLong()
            PrimitiveType.CHAR,
            PrimitiveType.SHORT,
            -> readUnsignedShort().toLong()
            PrimitiveType.FLOAT,
            PrimitiveType.INT,
            -> readInt().toLong()
            PrimitiveType.DOUBLE,
            PrimitiveType.LONG,
            -> readLong()
            PrimitiveType.UNKNOWN ->
                throw HprofParseException("Unknown HPROF value type at offset $position")
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
