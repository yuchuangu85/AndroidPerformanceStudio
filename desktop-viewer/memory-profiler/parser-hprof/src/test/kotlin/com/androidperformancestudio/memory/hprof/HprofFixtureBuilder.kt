@file:Suppress("MaxLineLength")

package com.androidperformancestudio.memory.hprof

import com.androidperformancestudio.memory.model.PrimitiveType
import java.io.ByteArrayOutputStream

internal class HprofFixtureBuilder(
    private val idSize: Int = 4,
) {
    private val records = ByteArrayOutputStream()

    fun string(
        id: Long,
        value: String,
    ) = apply {
        record(0x01, id(id) + value.encodeToByteArray())
    }

    fun loadClass(
        classId: Long,
        nameStringId: Long,
    ) = apply {
        record(0x02, int(1) + id(classId) + int(0) + id(nameStringId))
    }

    fun stackFrame() = apply { record(0x04, ByteArray(0)) }

    fun stackTrace() = apply { record(0x05, ByteArray(0)) }

    fun unknownRecord() = apply { record(0x7e, byteArrayOf(1, 2, 3)) }

    fun heapDump(vararg subRecords: ByteArray) = apply { record(0x0c, subRecords.reduce(ByteArray::plus)) }

    fun heapDumpSegment(vararg subRecords: ByteArray) = apply { record(0x1c, subRecords.reduce(ByteArray::plus)) }

    fun heapDumpEnd() = apply { record(0x2c, ByteArray(0)) }

    fun classDump(
        classId: Long,
        instanceSize: Int,
        superClassId: Long = 0L,
        classLoaderId: Long = 0L,
        staticObjectFields: List<Pair<Long, Long>> = emptyList(),
        instanceFields: List<Pair<Long, PrimitiveType>> = emptyList(),
    ): ByteArray =
        byteArrayOf(0x20) +
            id(classId) +
            int(0) +
            id(superClassId) + id(classLoaderId) + id(0) + id(0) + id(0) + id(0) +
            int(instanceSize) +
            short(0) +
            short(staticObjectFields.size) +
            staticObjectFields.fold(ByteArray(0)) { bytes, (nameId, targetId) ->
                bytes + id(nameId) + byteArrayOf(PrimitiveType.OBJECT.hprofType.toByte()) + id(targetId)
            } +
            short(instanceFields.size) +
            instanceFields.fold(ByteArray(0)) { bytes, (nameId, type) ->
                bytes + id(nameId) + byteArrayOf(type.hprofType.toByte())
            }

    fun instanceDump(
        objectId: Long,
        classId: Long,
        bytes: ByteArray = ByteArray(0),
    ): ByteArray =
        byteArrayOf(0x21) +
            id(objectId) + int(0) + id(classId) + int(bytes.size) + bytes

    fun objectArrayDump(
        objectId: Long,
        arrayClassId: Long,
        elementIds: List<Long>,
    ): ByteArray =
        byteArrayOf(0x22) +
            id(objectId) + int(0) + int(elementIds.size) + id(arrayClassId) + elementIds.flatMapBytes(::id)

    fun primitiveArrayDump(
        objectId: Long,
        type: PrimitiveType,
        elementCount: Int,
    ): ByteArray =
        byteArrayOf(0x23) +
            id(objectId) + int(0) + int(elementCount) + byteArrayOf(type.hprofType.toByte()) + ByteArray(type.byteWidth * elementCount)

    fun androidHeapDumpInfo(
        heapId: Int,
        heapNameStringId: Long,
    ): ByteArray = byteArrayOf(0xfe.toByte()) + int(heapId) + id(heapNameStringId)

    fun androidRoot(
        tag: Int,
        objectId: Long,
    ): ByteArray = byteArrayOf(tag.toByte()) + id(objectId)

    fun androidJniMonitorRoot(objectId: Long): ByteArray = byteArrayOf(0x8e.toByte()) + id(objectId) + int(1) + int(0)

    fun androidPrimitiveArrayNoData(
        objectId: Long,
        type: PrimitiveType,
        elementCount: Int,
    ): ByteArray =
        byteArrayOf(0xc3.toByte()) +
            id(objectId) + int(0) + int(elementCount) + byteArrayOf(type.hprofType.toByte())

    fun unknownSubRecord(): ByteArray = byteArrayOf(0x7d, 1, 2, 3)

    fun objectValue(value: Long): ByteArray = id(value)

    fun intValue(value: Int): ByteArray = int(value)

    fun build(): ByteArray =
        "JAVA PROFILE 1.0.3".encodeToByteArray() +
            byteArrayOf(0) +
            int(idSize) +
            long(1234L) +
            records.toByteArray()

    private fun record(
        tag: Int,
        payload: ByteArray,
    ) {
        records.write(tag)
        records.write(int(0))
        records.write(int(payload.size))
        records.write(payload)
    }

    private fun id(value: Long): ByteArray =
        when (idSize) {
            4 -> int(value.toInt())
            8 -> long(value)
            else -> error("Unsupported id size $idSize")
        }

    private fun int(value: Int): ByteArray =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

    private fun short(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())

    private fun long(value: Long): ByteArray = ByteArray(8) { index -> (value ushr ((7 - index) * 8)).toByte() }

    private fun List<Long>.flatMapBytes(mapper: (Long) -> ByteArray): ByteArray = fold(ByteArray(0)) { acc, value -> acc + mapper(value) }
}
