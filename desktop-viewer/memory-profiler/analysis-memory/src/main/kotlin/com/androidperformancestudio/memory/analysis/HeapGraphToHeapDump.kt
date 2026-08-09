package com.androidperformancestudio.memory.analysis

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

/** Converts a Perfetto `java_hprof` graph into the profiler's canonical heap model. */
object HeapGraphToHeapDump {
    @Suppress("LongMethod")
    fun toHeapDump(graph: HeapGraphData): HeapDump {
        val typesById = graph.types.associateBy { it.id }
        val classNames = graph.types.associate { it.id to normalizeClassName(it.className) }
        val objectClassNames =
            graph.objects.associate { it.id to (classNames[it.typeId] ?: HeapClass.UNKNOWN_CLASS_NAME) }
        val fieldsByType = graph.types.associate { it.id to referenceFields(it, typesById) }
        val classes =
            graph.types.map { type ->
                HeapClass(
                    objectId = type.id,
                    name = classNames.getValue(type.id),
                    instanceSize = type.objectSize,
                    superClassObjectId = type.superclassId,
                    instanceFields =
                        fieldsByType[type.id].orEmpty().map { fieldId ->
                            HeapField(graph.fieldNames[fieldId] ?: "<field-$fieldId>", PrimitiveType.OBJECT)
                        },
                    classLoaderObjectId = type.classLoaderId,
                    instanceSizeKnown = type.objectSizeKnown,
                    superClassObjectIdKnown = type.superclassIdKnown,
                    classLoaderObjectIdKnown = type.classLoaderIdKnown,
                )
            }
        val instances = mutableListOf<HeapInstance>()
        val objectArrays = mutableListOf<HeapObjectArray>()
        val primitiveArrays = mutableListOf<HeapPrimitiveArray>()
        graph.objects.forEach { obj ->
            val type = typesById[obj.typeId]
            val className = objectClassNames.getValue(obj.id)
            val fieldIds = obj.referenceFieldIds.ifEmpty { fieldsByType[obj.typeId].orEmpty() }
            val fieldReferences =
                obj.referenceObjectIds.mapIndexed { index, targetId ->
                    ObjectReference(
                        fieldName = graph.fieldNames[fieldIds.getOrNull(index)] ?: "[$index]",
                        targetObjectId = targetId,
                        targetClassName = objectClassNames[targetId] ?: HeapClass.UNKNOWN_CLASS_NAME,
                    )
                }
            val runtimeReferences =
                obj.runtimeInternalObjectIds.mapIndexed { index, targetId ->
                    ObjectReference(
                        fieldName = "<runtime-internal-$index>",
                        targetObjectId = targetId,
                        targetClassName = objectClassNames[targetId] ?: HeapClass.UNKNOWN_CLASS_NAME,
                    )
                }
            if (type?.isArray == true) {
                val primitiveType = primitiveArrayType(type.className)
                if (primitiveType != null) {
                    primitiveArrays +=
                        HeapPrimitiveArray(
                            objectId = obj.id,
                            primitiveType = primitiveType,
                            shallowSize = obj.selfSize,
                            shallowSizeKnown = obj.selfSizeKnown,
                        )
                } else {
                    objectArrays +=
                        HeapObjectArray(
                            objectId = obj.id,
                            arrayClassObjectId = obj.typeId,
                            className = className,
                            elementCount = obj.referenceObjectIds.size,
                            elementIds = obj.referenceObjectIds,
                            shallowSize = obj.selfSize,
                            shallowSizeKnown = obj.selfSizeKnown,
                        )
                }
            } else {
                instances +=
                    HeapInstance(
                        objectId = obj.id,
                        classObjectId = obj.typeId,
                        className = className,
                        shallowSize = obj.selfSize,
                        references = fieldReferences + runtimeReferences,
                        primitiveFields = specialFields(obj),
                        nativeSizeBytes = obj.nativeAllocationRegistrySize,
                        shallowSizeKnown = obj.selfSizeKnown,
                    )
            }
        }
        return HeapDump(
            pid = graph.pid,
            format = FORMAT,
            classes = classes,
            instances = instances,
            objectArrays = objectArrays,
            primitiveArrays = primitiveArrays,
            gcRoots = graph.roots.flatMap { root -> root.objectIds.map { HeapRoot(it, rootKind(root.rootType)) } },
            warnings =
                buildList {
                    add(
                        MemoryWarning(
                            "Perfetto java_hprof omits general primitive field values; " +
                                "lifecycle leak filters may be partial.",
                        ),
                    )
                    if (graph.types.any { !it.objectSizeKnown }) {
                        add(
                            MemoryWarning(
                                "Perfetto java_hprof does not report class instance sizes; size fields are unknown.",
                            ),
                        )
                    }
                    if (graph.types.any { !it.superclassIdKnown || !it.classLoaderIdKnown || !it.kindKnown }) {
                        add(
                            MemoryWarning(
                                "Perfetto java_hprof omitted class relationship or kind fields; " +
                                    "those values remain unknown.",
                            ),
                        )
                    }
                    if (graph.objects.any { !it.selfSizeKnown }) {
                        add(
                            MemoryWarning(
                                "Perfetto java_hprof omitted object shallow sizes; size fields remain unknown.",
                            ),
                        )
                    }
                },
            heapByObjectId = graph.objects.associate { it.id to heapName(it.heapType) },
        )
    }

    private fun referenceFields(
        type: HeapGraphType,
        typesById: Map<Long, HeapGraphType>,
    ): List<Long> {
        val result = mutableListOf<Long>()
        val visited = hashSetOf<Long>()
        var current: HeapGraphType? = type
        while (current != null && visited.add(current.id)) {
            result += current.referenceFieldIds
            current = typesById[current.superclassId]
        }
        return result
    }

    private fun specialFields(obj: HeapGraphObject): Map<String, Long> =
        buildMap {
            obj.bitmapId?.let { put("mId", it) }
            obj.bitmapSourceId?.let { put("mSourceId", it) }
            obj.bitmapWidth?.let { put("mWidth", it.toLong()) }
            obj.bitmapHeight?.let { put("mHeight", it.toLong()) }
            obj.applicationInfoLongVersionCode?.let { put("longVersionCode", it) }
        }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun normalizeClassName(rawName: String): String {
        if (rawName.isBlank()) return HeapClass.UNKNOWN_CLASS_NAME
        val name = rawName.replace('/', '.')
        if (!name.startsWith("[")) return name.removePrefix("L").removeSuffix(";")
        var dimensions = 0
        while (dimensions < name.length && name[dimensions] == '[') dimensions += 1
        val element =
            when (val descriptor = name.getOrNull(dimensions)) {
                'Z' -> "boolean"
                'C' -> "char"
                'F' -> "float"
                'D' -> "double"
                'B' -> "byte"
                'S' -> "short"
                'I' -> "int"
                'J' -> "long"
                'L' -> name.substring(dimensions + 1).removeSuffix(";")
                else -> descriptor?.toString() ?: HeapClass.UNKNOWN_CLASS_NAME
            }
        return element + "[]".repeat(dimensions)
    }

    private fun primitiveArrayType(rawName: String): PrimitiveType? =
        when (rawName.substringAfterLast('[').firstOrNull()) {
            'Z' -> PrimitiveType.BOOLEAN
            'C' -> PrimitiveType.CHAR
            'F' -> PrimitiveType.FLOAT
            'D' -> PrimitiveType.DOUBLE
            'B' -> PrimitiveType.BYTE
            'S' -> PrimitiveType.SHORT
            'I' -> PrimitiveType.INT
            'J' -> PrimitiveType.LONG
            else -> null
        }

    @Suppress("MagicNumber")
    private fun heapName(heapType: Int): String =
        when (heapType) {
            1 -> MemoryHeapNames.APP
            2 -> MemoryHeapNames.ZYGOTE
            3 -> MemoryHeapNames.IMAGE
            else -> MemoryHeapNames.DEFAULT
        }

    @Suppress("CyclomaticComplexMethod", "MagicNumber")
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
