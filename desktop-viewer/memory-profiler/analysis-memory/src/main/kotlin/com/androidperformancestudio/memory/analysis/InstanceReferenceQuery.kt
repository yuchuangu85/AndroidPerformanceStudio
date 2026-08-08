@file:Suppress("MaxLineLength")

package com.androidperformancestudio.memory.analysis

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
    val fields: List<FieldValue>,
    val referenceChain: List<ObjectReference>,
    /** Every object/class field that points to this object. */
    val references: List<FieldValue> = emptyList(),
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
    private val objectById =
        buildMap {
            heapDump.instances.forEach { put(it.objectId, it) }
            heapDump.objectArrays.forEach { put(it.objectId, it) }
            heapDump.primitiveArrays.forEach { put(it.objectId, it) }
        }
    private val graph = HeapGraph.from(heapDump)
    private val chainFinder = ReferenceChainFinder(heapDump, graph)
    private val inboundReferences =
        buildMap<Long, MutableList<FieldValue>> {
            graph.references.forEach { (sourceId, references) ->
                references.forEach { reference ->
                    if (reference.targetObjectId != 0L) {
                        getOrPut(reference.targetObjectId, ::mutableListOf) +=
                            FieldValue(
                                name = reference.fieldName,
                                displayValue = renderSourceReference(sourceId),
                                targetObjectId = sourceId,
                                targetClassName = graph.classNames[sourceId],
                            )
                    }
                }
            }
        }.mapValues { (_, values) -> values.sortedWith(compareBy({ it.targetClassName }, { it.targetObjectId }, { it.name })) }

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
                .sortedBy(HeapObject::objectId)
        return merged.mapIndexed { index, obj ->
            val depth = chainFinder.depthOf(obj.objectId)
            InstanceQueryRow(
                objectId = obj.objectId,
                index = index + 1,
                shallowSize = obj.shallowSize,
                retainedSize = retainedSizes[obj.objectId],
                depth = depth,
                reachable = depth != null,
                nativeSize = (obj as? HeapInstance)?.nativeSizeBytes,
            )
        }
    }

    /** Detailed view of a single object, or null when [objectId] is unknown. */
    fun detailOf(objectId: Long): InstanceQueryDetail? {
        val obj = objectById[objectId] ?: return null
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
                        .take(MAX_ARRAY_ELEMENT_FIELDS)
                        .mapIndexed { index, id ->
                            FieldValue(
                                name = "[$index]",
                                displayValue = renderObjectReference(id),
                                targetObjectId = id,
                                targetClassName = graph.classNames[id],
                            )
                        }
                else -> emptyList()
            }
        return InstanceQueryDetail(
            objectId = obj.objectId,
            className = obj.className,
            shallowSize = obj.shallowSize,
            retainedSize = retainedSizes[obj.objectId],
            depth = chainFinder.depthOf(obj.objectId),
            isArray = obj is HeapObjectArray || obj is HeapPrimitiveArray,
            elementCount = elementCount,
            fields = fields,
            referenceChain = chainFinder.chainTo(objectId),
            references = inboundReferences[objectId].orEmpty(),
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
                        graph.classNames[reference.targetObjectId] ?: reference.targetClassName,
                )
            }

    private fun renderObjectReference(objectId: Long): String {
        if (objectId == 0L) return "null"
        val className = graph.classNames[objectId].orEmpty()
        return if (className.isEmpty()) {
            "0x${objectId.toString(HEX_RADIX)}"
        } else {
            "0x${objectId.toString(HEX_RADIX)} · $className"
        }
    }

    private fun renderSourceReference(objectId: Long): String {
        val className = graph.classNames[objectId].orEmpty()
        return if (className.isEmpty()) "0x${objectId.toString(HEX_RADIX)}" else "$className @0x${objectId.toString(HEX_RADIX)}"
    }

    private companion object {
        const val MAX_ARRAY_ELEMENT_FIELDS = 200
        const val HEX_RADIX = 16
    }
}
