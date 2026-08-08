@file:Suppress("MagicNumber")

package com.androidperformancestudio.memory.model

import java.nio.file.Path
import java.time.Instant

/** Heap dump metadata, parsed object graph, and derived analysis results. */
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
    val gcRoots: List<HeapRoot> = emptyList(),
    val warnings: List<MemoryWarning> = emptyList(),
    val heapSummary: HeapSummary = HeapSummary(),
    val topClasses: List<ClassStats> = emptyList(),
    val leakSuspects: List<LeakSuspect> = emptyList(),
    val objectRetainedSizes: Map<Long, Long> = emptyMap(),
    val objectImmediateDominators: Map<Long, Long?> = emptyMap(),
    val heapByObjectId: Map<Long, String> = emptyMap(),
    val bitmapInstances: List<BitmapInstanceStats> = emptyList(),
    val activityLeaks: List<ActivityLeakEntry> = emptyList(),
)

data class MemoryWarning(
    val message: String,
    val offset: Long? = null,
)

data class HeapClass(
    val objectId: Long,
    val name: String = UNKNOWN_CLASS_NAME,
    val instanceSize: Long = 0L,
    val superClassObjectId: Long = 0L,
    val instanceFields: List<HeapField> = emptyList(),
    val staticReferences: List<ObjectReference> = emptyList(),
    val classLoaderObjectId: Long = 0L,
) {
    companion object {
        const val UNKNOWN_CLASS_NAME = "<unknown>"
        const val CLASS_OBJECT_CLASS_NAME = "java.lang.Class"
    }
}

data class HeapField(
    val name: String,
    val type: PrimitiveType,
)

data class HeapRoot(
    val objectId: Long,
    val kind: HeapRootKind,
)

enum class HeapRootKind {
    JNI_GLOBAL,
    JNI_LOCAL,
    JAVA_FRAME,
    NATIVE_STACK,
    STICKY_CLASS,
    THREAD_BLOCK,
    MONITOR_USED,
    THREAD_OBJECT,
    INTERNED_STRING,
    FINALIZING,
    DEBUGGER,
    REFERENCE_CLEANUP,
    VM_INTERNAL,
    JNI_MONITOR,
    UNREACHABLE,
    UNKNOWN,
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
    override val references: List<ObjectReference> = emptyList(),
    val primitiveFields: Map<String, Long> = emptyMap(),
    val nativeSizeBytes: Long? = null,
) : HeapObject {
    override fun equals(other: Any?): Boolean =
        other is HeapInstance &&
            objectId == other.objectId &&
            classObjectId == other.classObjectId &&
            className == other.className &&
            shallowSize == other.shallowSize &&
            fieldBytes.contentEquals(other.fieldBytes) &&
            references == other.references &&
            primitiveFields == other.primitiveFields &&
            nativeSizeBytes == other.nativeSizeBytes

    override fun hashCode(): Int {
        var result = objectId.hashCode()
        result = 31 * result + classObjectId.hashCode()
        result = 31 * result + className.hashCode()
        result = 31 * result + shallowSize.hashCode()
        result = 31 * result + fieldBytes.contentHashCode()
        result = 31 * result + references.hashCode()
        result = 31 * result + primitiveFields.hashCode()
        result = 31 * result + (nativeSizeBytes?.hashCode() ?: 0)
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
) : HeapObject {
    override val references: List<ObjectReference>
        get() =
            elementIds.mapIndexedNotNull { index, targetObjectId ->
                targetObjectId.takeIf { it != 0L }?.let {
                    ObjectReference(fieldName = "[$index]", targetObjectId = it)
                }
            }
}

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
    /** Pre-obfuscation original class name when a mapping.txt was applied; null otherwise. */
    val obfuscatedClassName: String? = null,
    /** Depth of the class in its superclass hierarchy (java.lang.Object == 0). */
    val hierarchyDepth: Int? = null,
    /** Estimated native footprint aggregated for this class (Bitmap pixel buffers); null when unknown. */
    val nativeSize: Long? = null,
    /**
     * Android Studio classifier metrics. Null means that the source (usually a heap dump) does
     * not provide the metric.
     */
    val moduleName: String? = null,
    val allocations: Long? = null,
    val deallocations: Long? = null,
    val allocationsSize: Long? = null,
    val deallocationsSize: Long? = null,
    val shallowSizeChange: Long? = null,
    /** Allocation call stack/method, when the data came from an allocation recording. */
    val allocationMethod: String? = null,
    val allocationCallstack: List<String> = emptyList(),
    val totalCount: Long? = null,
) {
    /** Deobfuscated display name; keeps the obfuscated name in parens when a mapping was applied. */
    val displayClassName: String
        get() =
            if (obfuscatedClassName != null) {
                "$className ($obfuscatedClassName)"
            } else {
                className
            }
}

/** Grouping modes used by Android Studio's Memory Classifier. */
enum class MemoryClassGrouping {
    CLASS,
    PACKAGE,
    CALLSTACK,
    ALLOCATION_METHOD,
}

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
    /** True when the heuristic confidence is below the manual-verification threshold. */
    val requiresManualVerification: Boolean = false,
    /** True only for Android Studio-compatible Activity/Fragment lifecycle candidates. */
    val activityOrFragmentLeak: Boolean = false,
) {
    val leakReason: String
        get() = reason
}

enum class HeapDiffMatchMode {
    /** Match heap-diff entries by class name only (stable for un-obfuscated dumps). */
    CLASS_NAME,

    /** Legacy heuristic; hierarchy depth is not a ClassLoader identity and can still merge classes. */
    CLASS_NAME_AND_HIERARCHY,
}

data class HeapDiffEntry(
    val className: String,
    val beforeCount: Int,
    val afterCount: Int,
    val countDelta: Int,
    val beforeShallowSize: Long,
    val afterShallowSize: Long,
    val shallowSizeDelta: Long,
    val matchedBy: HeapDiffMatchMode = HeapDiffMatchMode.CLASS_NAME,
    val hierarchyDepth: Int? = null,
)

data class HeapDiff(
    val entries: List<HeapDiffEntry> = emptyList(),
) {
    val added: List<HeapDiffEntry>
        get() = entries.filter { it.beforeCount == 0 && it.afterCount > 0 }
    val removed: List<HeapDiffEntry>
        get() = entries.filter { it.beforeCount > 0 && it.afterCount == 0 }
    val changed: List<HeapDiffEntry>
        get() = entries.filter { it.beforeCount > 0 && it.afterCount > 0 && it.countDelta != 0 }
}

data class BitmapInstanceStats(
    val objectId: Long,
    val width: Int?,
    val height: Int?,
    val retainedSize: Long,
    val referenceChain: List<ObjectReference> = emptyList(),
    /** Pixel-buffer estimate from rowBytes when available, otherwise width × height × 4 B. */
    val estimatedPixelBytes: Long? = null,
    /** Java-heap footprint of the Bitmap object itself (shallow size). */
    val javaSizeBytes: Long = 0L,
    /** Native pixel-buffer bytes only when the backing allocation is known to be native; null otherwise. */
    val nativeSizeBytes: Long? = null,
    /** Bitmap class name, used for class-level native aggregation. */
    val className: String = "android.graphics.Bitmap",
    /** Perfetto-captured backing storage identity, when available. */
    val bitmapId: Long? = null,
    val bitmapSourceId: Long? = null,
)

/**
 * Canonical heap labels shown in the Heap selector (the "All heaps" option is represented by a
 * null filter in the presentation layer).
 */
object MemoryHeapNames {
    const val APP = "App"
    const val IMAGE = "Image"
    const val ZYGOTE = "Zygote"
    const val DEFAULT = "Default"

    /** Preferred display order for the Heap selector. */
    val ordered: List<String> = listOf(APP, IMAGE, ZYGOTE, DEFAULT)
}

/** A per-Activity-class summary used to report Activity leaks with live/destroyed counts. */
data class ActivityLeakEntry(
    val className: String,
    val liveInstanceCount: Int = 0,
    val destroyedInstanceCount: Int = 0,
    val retainedSize: Long? = null,
    val referenceChain: List<ObjectReference> = emptyList(),
)

data class ActivityLeakReport(
    val entries: List<ActivityLeakEntry> = emptyList(),
)

/**
 * Summary of a heapprofd native-heap trace, safe to expose to the presentation layer. The API level
 * is only known for live captures; imported `.pb` files do not carry it (null).
 */
data class NativeHeapTrace(
    val traceFile: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val deviceSdkApiLevel: Int? = null,
)

/** Aggregated allocation statistics for one native function (leaf callstack frame). */
data class NativeHeapSample(
    val functionName: String,
    val allocatedBytes: Long,
    val freedBytes: Long,
    val allocCount: Long,
    val freeCount: Long,
)

/** Parsed allocation table from a heapprofd (.pb) trace. */
data class NativeHeapAnalysis(
    val totalAllocatedBytes: Long = 0L,
    val totalFreedBytes: Long = 0L,
    val sampleCount: Int = 0,
    val topAllocations: List<NativeHeapSample> = emptyList(),
)
