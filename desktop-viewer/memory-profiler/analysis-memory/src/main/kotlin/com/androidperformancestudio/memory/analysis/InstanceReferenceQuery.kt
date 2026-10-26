@file:Suppress("MaxLineLength")

package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.HeapClass
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapInstance
import com.androidperformancestudio.memory.model.HeapObject
import com.androidperformancestudio.memory.model.HeapObjectArray
import com.androidperformancestudio.memory.model.HeapPrimitiveArray
import com.androidperformancestudio.memory.model.ObjectReference

/** One row of the instance list shown when a class is selected. */
data class InstanceQueryRow(
    val objectId: Long,
    val index: Int,
    val shallowSize: Long,
    val retainedSize: Long?,
    val depth: Int?,
    val reachable: Boolean,
    val nativeSize: Long? = null,
    val shallowSizeKnown: Boolean = true,
)

/** A single field value of an instance, either a primitive or an object reference. */
data class FieldValue(
    val name: String,
    val displayValue: String,
    val targetObjectId: Long?,
    val targetClassName: String?,
)

/** Full detail view of one heap object (instance or array). */
data class InstanceQueryDetail(
    val objectId: Long,
    val className: String,
    val shallowSize: Long,
    val retainedSize: Long?,
    val depth: Int?,
    val isArray: Boolean,
    val elementCount: Int?,
    val arrayPageSize: Int = 200,
    val fields: List<FieldValue>,
    val referenceChain: List<ObjectReference>,
    /** Every object/class field that points to this object. */
    val references: List<FieldValue> = emptyList(),
    val shallowSizeKnown: Boolean = true,
    val nativeSize: Long? = null,
)

/**
 * Query service backing the Android Studio-style class → instance → reference drill-down.
 *
 * Builds the reachability traversal once per heap dump and reuses it across class/instance
 * selections, so the caller should keep one instance per loaded [HeapDump].
 */
class InstanceReferenceQuery(
    heapDump: HeapDump,
) {
    private val retainedSizes = heapDump.objectRetainedSizes
    private val heapByObjectId = heapDump.heapByObjectId
    private val instancesByClass = heapDump.instances.groupBy { it.className }
    private val arraysByClass =
        (heapDump.objectArrays + heapDump.primitiveArrays).groupBy { it.className }
    private val heapClasses = heapDump.classes.sortedBy { it.objectId }
    private val heapClassById = heapClasses.associateBy { it.objectId }
    private val classObjectType = heapClasses.firstOrNull { it.name == HeapClass.CLASS_OBJECT_CLASS_NAME }
    private val classObjectShallowSize = classObjectType?.instanceSize ?: 0L
    private val objectById =
        buildMap {
            heapDump.instances.forEach { put(it.objectId, it) }
            heapDump.objectArrays.forEach { put(it.objectId, it) }
            heapDump.primitiveArrays.forEach { put(it.objectId, it) }
        }

    // Keep one object lookup for the workspace. A second HeapGraph duplicates millions of
    // reference lists and class-name entries when the first instance is opened.
    private val navigator by lazy { OnDemandHeapReferenceNavigator(heapDump, objectById, heapClassById) }

    private fun classNameOf(objectId: Long): String? = heapClassById[objectId]?.name ?: objectById[objectId]?.className

    /** Reuses the query's object index when another view needs a row for a known object id. */
    fun objectOf(objectId: Long): HeapObject? = objectById[objectId]

    /** Class-table group for an object; a CLASS_DUMP is grouped by the class it represents. */
    fun groupingClassNameOf(objectId: Long): String? = classNameOf(objectId)

    /** Snapshot-local heap identity, including CLASS_DUMP objects and arrays. */
    fun heapNameOf(objectId: Long): String? = heapByObjectId[objectId]

    /**
     * Instances and arrays of [className], ordered by object id with 1-based row indexes.
     * When [heapName] is non-null only objects of that heap are returned, keeping the instance
     * list consistent with the heap-scoped class table.
     */
    fun instancesOf(
        className: String,
        heapName: String? = null,
    ): List<InstanceQueryRow> {
        val merged =
            (instancesByClass[className].orEmpty() + arraysByClass[className].orEmpty())
                .filter { heapName == null || heapByObjectId[it.objectId] == heapName }
        val objectRows =
            merged.map { obj ->
                val depth = navigator.depthOf(obj.objectId)
                InstanceQueryRow(
                    objectId = obj.objectId,
                    index = 0,
                    shallowSize = obj.shallowSize,
                    retainedSize = retainedSizes[obj.objectId],
                    depth = depth,
                    reachable = depth != null,
                    nativeSize = (obj as? HeapInstance)?.nativeSizeBytes,
                    shallowSizeKnown = obj.shallowSizeKnown,
                )
            }
        val classObjectRows =
            heapClasses
                .asSequence()
                .filter { it.name == className && (heapName == null || heapByObjectId[it.objectId] == heapName) }
                .map { heapClass ->
                    val depth = navigator.depthOf(heapClass.objectId)
                    InstanceQueryRow(
                        objectId = heapClass.objectId,
                        index = 0,
                        shallowSize = classObjectShallowSize,
                        retainedSize = retainedSizes[heapClass.objectId],
                        depth = depth,
                        reachable = depth != null,
                        shallowSizeKnown = classObjectType != null,
                    )
                }.toList()
        return (objectRows + classObjectRows)
            .sortedBy(InstanceQueryRow::objectId)
            .mapIndexed { index, row -> row.copy(index = index + 1) }
    }

    /** Detailed view of a single object, or null when [objectId] is unknown. */
    fun detailOf(
        objectId: Long,
        arrayStart: Int = 0,
        arrayLimit: Int = MAX_ARRAY_ELEMENT_FIELDS,
    ): InstanceQueryDetail? {
        val obj = objectById[objectId] ?: return classDetailOf(objectId)
        val boundedStart = arrayStart.coerceAtLeast(0)
        val boundedLimit = arrayLimit.coerceIn(1, MAX_ARRAY_ELEMENT_FIELDS)
        val elementCount =
            when (obj) {
                is HeapObjectArray -> obj.elementCount
                is HeapPrimitiveArray -> obj.elementCount
                else -> null
            }
        val fields =
            when (obj) {
                is HeapInstance -> instanceFields(obj)
                is HeapObjectArray ->
                    obj.elementIds
                        .drop(boundedStart)
                        .take(boundedLimit)
                        .mapIndexed { index, id ->
                            FieldValue(
                                name = "[${boundedStart + index}]",
                                displayValue = renderObjectReference(id),
                                targetObjectId = id,
                                targetClassName = classNameOf(id),
                            )
                        }
                else -> emptyList()
            }
        return InstanceQueryDetail(
            objectId = obj.objectId,
            className = obj.className,
            shallowSize = obj.shallowSize,
            retainedSize = retainedSizes[obj.objectId],
            depth = navigator.depthOf(obj.objectId),
            isArray = obj is HeapObjectArray || obj is HeapPrimitiveArray,
            elementCount = elementCount,
            arrayPageSize = boundedLimit,
            fields = fields,
            referenceChain = navigator.chainTo(objectId),
            references = inboundReferencesOf(objectId),
            shallowSizeKnown = obj.shallowSizeKnown,
            nativeSize = (obj as? HeapInstance)?.nativeSizeBytes,
        )
    }

    private fun classDetailOf(objectId: Long): InstanceQueryDetail? {
        val heapClass = heapClassById[objectId] ?: return null
        return InstanceQueryDetail(
            objectId = objectId,
            className = HeapClass.CLASS_OBJECT_CLASS_NAME,
            shallowSize = classObjectShallowSize,
            retainedSize = retainedSizes[objectId],
            depth = navigator.depthOf(objectId),
            isArray = false,
            elementCount = null,
            fields =
                heapClass.staticReferences.map { reference ->
                    FieldValue(
                        name = reference.fieldName,
                        displayValue = renderObjectReference(reference.targetObjectId),
                        targetObjectId = reference.targetObjectId,
                        targetClassName = classNameOf(reference.targetObjectId),
                    )
                },
            referenceChain = navigator.chainTo(objectId),
            references = inboundReferencesOf(objectId),
            shallowSizeKnown = classObjectType != null,
        )
    }

    private fun instanceFields(instance: HeapInstance): List<FieldValue> =
        instance.primitiveFields.map { (name, value) ->
            FieldValue(
                name = name,
                displayValue = value.toString(),
                targetObjectId = null,
                targetClassName = null,
            )
        } +
            instance.references.map { reference ->
                FieldValue(
                    name = reference.fieldName,
                    displayValue = renderObjectReference(reference.targetObjectId),
                    targetObjectId = reference.targetObjectId,
                    targetClassName =
                        classNameOf(reference.targetObjectId) ?: reference.targetClassName,
                )
            }

    private fun renderObjectReference(objectId: Long): String {
        if (objectId == 0L) return "null"
        val className = classNameOf(objectId).orEmpty()
        return if (className.isEmpty()) {
            "0x${objectId.toString(HEX_RADIX)}"
        } else {
            "0x${objectId.toString(HEX_RADIX)} · $className"
        }
    }

    private fun renderSourceReference(objectId: Long): String {
        val className = classNameOf(objectId).orEmpty()
        return if (className.isEmpty()) "0x${objectId.toString(HEX_RADIX)}" else "$className @0x${objectId.toString(HEX_RADIX)}"
    }

    private fun inboundReferencesOf(objectId: Long): List<FieldValue> {
        val values = mutableListOf<FieldValue>()
        navigator.forEachInboundReference(objectId) { sourceId, reference ->
            values +=
                FieldValue(
                    name = reference.fieldName,
                    displayValue = renderSourceReference(sourceId),
                    targetObjectId = sourceId,
                    targetClassName = classNameOf(sourceId),
                )
        }
        return values.sortedWith(compareBy({ it.targetClassName }, { it.targetObjectId }, { it.name }))
    }

    private companion object {
        const val MAX_ARRAY_ELEMENT_FIELDS = 200
        const val HEX_RADIX = 16
    }
}
