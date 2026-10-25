package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.HeapClass
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapInstance
import com.androidperformancestudio.memory.model.HeapObject
import com.androidperformancestudio.memory.model.HeapObjectArray
import com.androidperformancestudio.memory.model.HeapRoot
import com.androidperformancestudio.memory.model.ObjectReference
import java.util.ArrayDeque

/**
 * Object drill-down over the parsed heap without materializing a second full reference graph.
 * Only the BFS predecessor for each reachable object is retained; inbound edges are scanned on
 * demand because most imported objects are never opened in the inspector.
 */
internal class OnDemandHeapReferenceNavigator(
    private val heapDump: HeapDump,
    private val objects: Map<Long, HeapObject>,
    private val classes: Map<Long, HeapClass>,
) {
    private val referenceClassIds by lazy { heapDump.classIdsAssignableTo("java.lang.ref.Reference") }
    private val rootById by lazy {
        heapDump.gcRoots
            .sortedWith(compareBy(HeapRoot::objectId, { it.kind.name }))
            .filter { known(it.objectId) }
            .distinctBy(HeapRoot::objectId)
            .associateBy(HeapRoot::objectId)
    }
    private val predecessors by lazy { buildPredecessors() }

    fun depthOf(objectId: Long): Int? {
        val parents = predecessors
        return if (objectId in rootById) 0 else parents[objectId]?.depth
    }

    fun chainTo(objectId: Long): List<ObjectReference> {
        val parents = predecessors
        if (objectId !in rootById && objectId !in parents) return emptyList()
        val reversed = mutableListOf<ObjectReference>()
        var current = objectId
        while (current !in rootById) {
            val predecessor = parents.getValue(current)
            reversed += predecessor.reference.copy(targetClassName = classNameOf(current))
            current = predecessor.sourceObjectId
        }
        val root = rootById.getValue(current)
        return buildList(reversed.size + 1) {
            add(ObjectReference("GC Root (${root.kind.name})", current, classNameOf(current)))
            reversed.asReversed().forEach(::add)
        }
    }

    fun forEachInboundReference(
        targetObjectId: Long,
        action: (Long, ObjectReference) -> Unit,
    ) {
        objects.forEach { (sourceId, obj) ->
            if (sourceId in classes) return@forEach
            emitObjectInboundReferences(sourceId, obj, targetObjectId, action)
        }
        classes.forEach { (sourceId, heapClass) ->
            heapClass.staticReferences.forEach { reference ->
                if (reference.targetObjectId == targetObjectId) action(sourceId, reference)
            }
        }
    }

    private fun buildPredecessors(): Map<Long, Predecessor> {
        val result = HashMap<Long, Predecessor>()
        val queue = ArrayDeque<Long>()
        rootById.keys.forEach(queue::addLast)
        while (queue.isNotEmpty()) {
            val sourceId = queue.removeFirst()
            val sourceDepth = result[sourceId]?.depth ?: 0
            forEachSortedStrongReference(sourceId) { reference ->
                val target = reference.targetObjectId
                if (target !in rootById && target !in result) {
                    result[target] = Predecessor(sourceId, reference, sourceDepth + 1)
                    queue.addLast(target)
                }
            }
        }
        return result
    }

    private fun known(objectId: Long): Boolean = objectId in objects || objectId in classes

    private fun classNameOf(objectId: Long): String = classes[objectId]?.name ?: objects[objectId]?.className.orEmpty()

    private fun emitObjectInboundReferences(
        sourceId: Long,
        obj: HeapObject,
        targetObjectId: Long,
        action: (Long, ObjectReference) -> Unit,
    ) {
        if (obj is HeapObjectArray) {
            emitArrayInboundReferences(obj, targetObjectId) { reference -> action(sourceId, reference) }
            return
        }
        obj.references.forEach { reference ->
            if (reference.targetObjectId == targetObjectId && isStrong(sourceId, reference)) {
                action(sourceId, reference)
            }
        }
    }

    private fun emitArrayInboundReferences(
        array: HeapObjectArray,
        targetObjectId: Long,
        action: (ObjectReference) -> Unit,
    ) {
        array.elementIds.forEachIndexed { index, elementId ->
            if (elementId == targetObjectId) action(ObjectReference("[$index]", targetObjectId))
        }
    }

    private fun forEachSortedStrongReference(
        sourceId: Long,
        action: (ObjectReference) -> Unit,
    ) {
        val heapClass = classes[sourceId]
        if (heapClass != null) {
            heapClass.staticReferences
                .asSequence()
                .filter { known(it.targetObjectId) }
                .sortedWith(compareBy(ObjectReference::fieldName, ObjectReference::targetObjectId))
                .forEach(action)
            return
        }
        val obj = objects[sourceId]
        if (obj is HeapObjectArray) {
            // Visit array elements without materializing a second list of all references.
            obj.elementIds.forEachIndexed { index, targetId ->
                if (targetId != 0L && known(targetId)) action(ObjectReference("[$index]", targetId))
            }
            return
        }
        obj
            ?.references
            .orEmpty()
            .asSequence()
            .filter { isStrong(sourceId, it) && known(it.targetObjectId) }
            .sortedWith(compareBy(ObjectReference::fieldName, ObjectReference::targetObjectId))
            .forEach(action)
    }

    private fun isStrong(
        sourceId: Long,
        reference: ObjectReference,
    ): Boolean {
        val instance = objects[sourceId] as? HeapInstance ?: return true
        val referenceHolder =
            instance.classObjectId in referenceClassIds ||
                instance.className in REFERENCE_CLASS_NAMES
        return !referenceHolder || reference.fieldName != "referent"
    }

    private data class Predecessor(
        val sourceObjectId: Long,
        val reference: ObjectReference,
        val depth: Int,
    )

    private companion object {
        val REFERENCE_CLASS_NAMES =
            setOf("java.lang.ref.WeakReference", "java.lang.ref.SoftReference", "java.lang.ref.PhantomReference")
    }
}
