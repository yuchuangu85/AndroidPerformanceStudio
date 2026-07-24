@file:Suppress("MagicNumber")

package dev.agentperf.memory.model

import java.nio.file.Path
import java.time.Instant

/** Phase 1 heap dump metadata and parsed-content container. */
data class HeapDump(
    val id: String = "",
    val packageName: String = "",
    val pid: Int = 0,
    val capturedAt: Instant = Instant.EPOCH,
    val rawHprofFile: Path? = null,
    val convertedHprofFile: Path? = null,
    val format: String = "",
    val idSize: Int = 0,
    val timestampMillis: Long = 0L,
    val classes: List<HeapClass> = emptyList(),
    val instances: List<HeapInstance> = emptyList(),
    val objectArrays: List<HeapObjectArray> = emptyList(),
    val primitiveArrays: List<HeapPrimitiveArray> = emptyList(),
    val warnings: List<MemoryWarning> = emptyList(),
    val heapSummary: HeapSummary = HeapSummary(),
    val topClasses: List<ClassStats> = emptyList(),
    val leakSuspects: List<LeakSuspect> = emptyList(),
)

data class MemoryWarning(
    val message: String,
    val offset: Long? = null,
)

data class HeapClass(
    val objectId: Long,
    val name: String = UNKNOWN_CLASS_NAME,
    val instanceSize: Long = 0L,
) {
    companion object {
        const val UNKNOWN_CLASS_NAME = "<unknown>"
    }
}

sealed interface HeapObject {
    val objectId: Long
    val className: String
    val shallowSize: Long
    val retainedSize: Long?
        get() = null
    val references: List<ObjectReference>
        get() = emptyList()
}

data class ObjectReference(
    val fieldName: String,
    val targetObjectId: Long,
    val targetClassName: String = HeapClass.UNKNOWN_CLASS_NAME,
)

data class HeapInstance(
    override val objectId: Long,
    val classObjectId: Long,
    override val className: String = HeapClass.UNKNOWN_CLASS_NAME,
    override val shallowSize: Long = 0L,
    val fieldBytes: ByteArray = ByteArray(0),
) : HeapObject {
    override fun equals(other: Any?): Boolean =
        other is HeapInstance &&
            objectId == other.objectId &&
            classObjectId == other.classObjectId &&
            className == other.className &&
            shallowSize == other.shallowSize &&
            fieldBytes.contentEquals(other.fieldBytes)

    override fun hashCode(): Int {
        var result = objectId.hashCode()
        result = 31 * result + classObjectId.hashCode()
        result = 31 * result + className.hashCode()
        result = 31 * result + shallowSize.hashCode()
        result = 31 * result + fieldBytes.contentHashCode()
        return result
    }
}

data class HeapObjectArray(
    override val objectId: Long,
    val arrayClassObjectId: Long,
    override val className: String = HeapClass.UNKNOWN_CLASS_NAME,
    val elementCount: Int = 0,
    val elementIds: List<Long> = emptyList(),
    override val shallowSize: Long = 0L,
) : HeapObject

data class HeapPrimitiveArray(
    override val objectId: Long,
    val primitiveType: PrimitiveType = PrimitiveType.UNKNOWN,
    val elementCount: Int = 0,
    override val shallowSize: Long = 0L,
) : HeapObject {
    override val className: String = primitiveType.arrayClassName
}

enum class PrimitiveType(
    val hprofType: Int,
    val byteWidth: Int,
    val arrayClassName: String,
) {
    OBJECT(2, 0, "object[]"),
    BOOLEAN(4, 1, "boolean[]"),
    CHAR(5, 2, "char[]"),
    FLOAT(6, 4, "float[]"),
    DOUBLE(7, 8, "double[]"),
    BYTE(8, 1, "byte[]"),
    SHORT(9, 2, "short[]"),
    INT(10, 4, "int[]"),
    LONG(11, 8, "long[]"),
    UNKNOWN(-1, 0, "<unknown-primitive-array>"),
    ;

    companion object {
        fun fromHprofType(type: Int): PrimitiveType = entries.firstOrNull { it.hprofType == type } ?: UNKNOWN
    }
}

data class ClassStats(
    val className: String,
    val instanceCount: Int = 0,
    val shallowSize: Long = 0L,
    val retainedSize: Long? = null,
)

data class HeapSummary(
    val objectCount: Int = 0,
    val shallowSize: Long = 0L,
    val classCount: Int = 0,
    val heapSegments: List<HeapSegment> = emptyList(),
) {
    val totalObjects: Int
        get() = objectCount
    val totalHeapSize: Long
        get() = shallowSize
    val totalClasses: Int
        get() = classCount
}

data class HeapSegment(
    val name: String,
    val size: Long,
    val objectCount: Int,
)

data class HeapHistogram(
    val summary: HeapSummary = HeapSummary(),
    val classes: List<ClassStats> = emptyList(),
)

data class LeakSuspect(
    val className: String,
    val reason: String,
    val retainedSize: Long? = null,
    val instanceCount: Int = 0,
    val referenceChain: List<ObjectReference> = emptyList(),
    val confidence: Float = 0f,
) {
    val leakReason: String
        get() = reason
}
