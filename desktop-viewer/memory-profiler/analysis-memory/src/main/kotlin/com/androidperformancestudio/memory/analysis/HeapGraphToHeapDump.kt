package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.HeapClass
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapInstance
import com.androidperformancestudio.memory.model.HeapRoot
import com.androidperformancestudio.memory.model.HeapRootKind
import com.androidperformancestudio.memory.model.ObjectReference

/**
 * Converts a raw perfetto `java_hprof` [HeapGraphData] into the canonical [HeapDump] model so the
 * existing heap analysis (class histogram, dominator tree / retained sizes, leak suspects) and the
 * class-list UI apply unchanged.
 *
 * Every [HeapGraphObject] becomes a [HeapInstance]; object references become [ObjectReference]s
 * (named via the interned field names). [HeapGraphType]s become [HeapClass]es and roots become
 * [HeapRoot]s. Array objects (kind `KIND_ARRAY`) are kept as instances too — their element
 * references flow through [HeapInstance.references], so the analysis stays correct.
 */
object HeapGraphToHeapDump {
    fun toHeapDump(graph: HeapGraphData): HeapDump {
        val typesById = graph.types.associateBy { type -> type.id }
        val classes =
            graph.types.map { type ->
                HeapClass(
                    objectId = type.id,
                    name = type.className.ifBlank { HeapClass.UNKNOWN_CLASS_NAME },
                    instanceSize = type.objectSize,
                    superClassObjectId = type.superclassId,
                )
            }
        val instances =
            graph.objects.map { obj ->
                val type = typesById[obj.typeId]
                val references =
                    obj.referenceObjectIds.mapIndexedNotNull { index, targetId ->
                        if (targetId == 0L) {
                            null
                        } else {
                            val fieldName =
                                obj.referenceFieldIds.getOrNull(index)
                                    ?.let { graph.fieldNames[it] }
                                    ?: "[$index]"
                            ObjectReference(fieldName = fieldName, targetObjectId = targetId)
                        }
                    }
                HeapInstance(
                    objectId = obj.id,
                    classObjectId = obj.typeId,
                    className =
                        type?.className?.ifBlank { HeapClass.UNKNOWN_CLASS_NAME }
                            ?: HeapClass.UNKNOWN_CLASS_NAME,
                    shallowSize = obj.selfSize,
                    references = references,
                )
            }
        val gcRoots =
            graph.roots.flatMap { root ->
                root.objectIds.map { objectId -> HeapRoot(objectId = objectId, kind = rootKind(root.rootType)) }
            }
        return HeapDump(
            format = FORMAT,
            classes = classes,
            instances = instances,
            gcRoots = gcRoots,
        )
    }

    private fun rootKind(rootType: Int): HeapRootKind =
        when (rootType) {
            1 -> HeapRootKind.JNI_GLOBAL
            2 -> HeapRootKind.JNI_LOCAL
            3 -> HeapRootKind.JAVA_FRAME
            4 -> HeapRootKind.NATIVE_STACK
            5 -> HeapRootKind.STICKY_CLASS
            6 -> HeapRootKind.THREAD_BLOCK
            7 -> HeapRootKind.MONITOR_USED
            8 -> HeapRootKind.THREAD_OBJECT
            9 -> HeapRootKind.INTERNED_STRING
            10 -> HeapRootKind.FINALIZING
            11 -> HeapRootKind.DEBUGGER
            12 -> HeapRootKind.REFERENCE_CLEANUP
            13 -> HeapRootKind.VM_INTERNAL
            14 -> HeapRootKind.JNI_MONITOR
            else -> HeapRootKind.UNKNOWN
        }

    private const val FORMAT = "perfetto-java-hprof"
}
