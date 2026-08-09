@file:Suppress("MaxLineLength")

package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.contracts.CapabilityId
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.platform.perfetto.TraceColumn
import com.androidperformancestudio.platform.perfetto.TraceQuery
import com.androidperformancestudio.platform.perfetto.TraceQueryResult
import com.androidperformancestudio.platform.perfetto.TraceQuerySchema

/** Feature-owned typed SQL boundary for Perfetto's java_hprof graph tables. */
class JavaHeapTraceProcessorAdapter {
    private val classId = TraceColumn.long("id")
    private val className = TraceColumn.string("class_name")
    private val superClassId = TraceColumn.long("superclass_id")
    private val classLoaderId = TraceColumn.long("classloader_id")
    private val classKind = TraceColumn.string("kind")
    private val objectId = TraceColumn.long("id")
    private val objectSize = TraceColumn.long("self_size")
    private val objectTypeId = TraceColumn.long("type_id")
    private val heapType = TraceColumn.string("heap_type")
    private val rootType = TraceColumn.string("root_type")
    private val ownerId = TraceColumn.long("owner_id")
    private val ownedId = TraceColumn.long("owned_id")
    private val fieldName = TraceColumn.string("field_name")

    val classQuery: TraceQuery<JavaHeapClassRow> =
        TraceQuery(
            "SELECT id, COALESCE(deobfuscated_name, name) AS class_name, superclass_id, classloader_id, kind FROM heap_graph_class ORDER BY id",
            TraceQuerySchema.v57_2(classId, className, superClassId, classLoaderId, classKind),
        ) { row ->
            JavaHeapClassRow(
                id = requireNotNull(row[classId]),
                name = row[className],
                superclassId = row[superClassId],
                classLoaderId = row[classLoaderId],
                kind = row[classKind],
            )
        }

    val objectQuery: TraceQuery<JavaHeapObjectRow> =
        TraceQuery(
            "SELECT id, self_size, type_id, heap_type, root_type FROM heap_graph_object ORDER BY id",
            TraceQuerySchema.v57_2(objectId, objectSize, objectTypeId, heapType, rootType),
        ) { row ->
            JavaHeapObjectRow(
                id = requireNotNull(row[objectId]),
                selfSize = row[objectSize],
                typeId = requireNotNull(row[objectTypeId]),
                heapType = row[heapType],
                rootType = row[rootType],
            )
        }

    val referenceQuery: TraceQuery<JavaHeapReferenceRow> =
        TraceQuery(
            "SELECT owner_id, owned_id, COALESCE(deobfuscated_field_name, field_name) AS field_name FROM heap_graph_reference ORDER BY owner_id, id",
            TraceQuerySchema.v57_2(ownerId, ownedId, fieldName),
        ) { row ->
            JavaHeapReferenceRow(
                ownerId = requireNotNull(row[ownerId]),
                ownedId = requireNotNull(row[ownedId]),
                fieldName = row[fieldName],
            )
        }

    fun mapFixture(
        classesCsv: String,
        objectsCsv: String,
        referencesCsv: String,
    ): JavaHeapTraceProcessorResult =
        map(
            classQuery.map(TraceQueryResult.parse(classesCsv)),
            objectQuery.map(TraceQueryResult.parse(objectsCsv)),
            referenceQuery.map(TraceQueryResult.parse(referencesCsv)),
        )

    @Suppress("LongMethod")
    fun map(
        classes: List<JavaHeapClassRow>,
        objects: List<JavaHeapObjectRow>,
        references: List<JavaHeapReferenceRow>,
    ): JavaHeapTraceProcessorResult {
        val fieldIds = linkedMapOf<String, Long>()

        fun fieldId(name: String): Long = fieldIds.getOrPut(name) { (fieldIds.size + 1).toLong() }
        val types =
            classes.map { row ->
                HeapGraphType(
                    id = row.id,
                    className = row.name ?: com.androidperformancestudio.memory.model.HeapClass.UNKNOWN_CLASS_NAME,
                    objectSize = 0L,
                    superclassId = row.superclassId ?: 0L,
                    kind = if (row.kind.equals("array", ignoreCase = true)) HeapGraphType.KIND_ARRAY else 0,
                    referenceFieldIds = emptyList(),
                    classLoaderId = row.classLoaderId ?: 0L,
                    objectSizeKnown = false,
                    superclassIdKnown = row.superclassId != null,
                    classLoaderIdKnown = row.classLoaderId != null,
                    kindKnown = row.kind != null,
                )
            }
        val refsByOwner = references.groupBy(JavaHeapReferenceRow::ownerId)
        val graphObjects =
            objects.map { row ->
                val ownerRefs = refsByOwner[row.id].orEmpty()
                HeapGraphObject(
                    id = row.id,
                    idDelta = row.id,
                    typeId = row.typeId,
                    selfSize = row.selfSize ?: 0L,
                    referenceFieldIds = ownerRefs.map { fieldId(it.fieldName ?: "<unknown>") },
                    referenceObjectIds = ownerRefs.map(JavaHeapReferenceRow::ownedId),
                    heapType = heapType(row.heapType),
                    selfSizeKnown = row.selfSize != null,
                )
            }
        val roots =
            objects.mapNotNull { row ->
                row.rootType?.let { HeapGraphRoot(listOf(row.id), rootType(it)) }
            }
        val graph =
            HeapGraphData(
                types = types,
                objects = graphObjects,
                roots = roots,
                fieldNames = fieldIds.entries.associate { (name, id) -> id to name },
                locationNames = emptyMap(),
            )
        val heapDump = HeapGraphToHeapDump.toHeapDump(graph)
        return JavaHeapTraceProcessorResult(
            heapDump = heapDump,
            availableCapabilities =
                buildSet {
                    addAll(
                        setOf(
                            JavaHeapCapabilities.CLASSES,
                            JavaHeapCapabilities.OBJECTS,
                            JavaHeapCapabilities.REFERENCES,
                            JavaHeapCapabilities.ROOTS,
                        ),
                    )
                    if (objects.all { it.selfSize != null }) add(JavaHeapCapabilities.SHALLOW_SIZES)
                },
        )
    }

    @Suppress("MagicNumber")
    private fun heapType(value: String?): Int =
        when (value?.lowercase()) {
            "app" -> 1
            "zygote" -> 2
            "image" -> 3
            else -> 0
        }

    @Suppress("CyclomaticComplexMethod", "MagicNumber")
    private fun rootType(value: String): Int =
        when (value.lowercase()) {
            "jni_global" -> 1
            "jni_local" -> 2
            "java_frame" -> 3
            "native_stack" -> 4
            "sticky_class" -> 5
            "thread_block" -> 6
            "monitor_used" -> 7
            "thread_object" -> 8
            "interned_string" -> 9
            "finalizing" -> 10
            "debugger" -> 11
            "reference_cleanup" -> 12
            "vm_internal" -> 13
            "jni_monitor" -> 14
            else -> 0
        }
}

data class JavaHeapClassRow(
    val id: Long,
    val name: String?,
    val superclassId: Long?,
    val classLoaderId: Long?,
    val kind: String?,
)

data class JavaHeapObjectRow(
    val id: Long,
    val selfSize: Long?,
    val typeId: Long,
    val heapType: String?,
    val rootType: String?,
)

data class JavaHeapReferenceRow(
    val ownerId: Long,
    val ownedId: Long,
    val fieldName: String?,
)

data class JavaHeapTraceProcessorResult(
    val heapDump: HeapDump,
    val availableCapabilities: Set<CapabilityId>,
)

object JavaHeapCapabilities {
    val CLASSES = CapabilityId("java_heap.classes")
    val OBJECTS = CapabilityId("java_heap.objects")
    val REFERENCES = CapabilityId("java_heap.references")
    val ROOTS = CapabilityId("java_heap.roots")
    val SHALLOW_SIZES = CapabilityId("java_heap.shallow_sizes")
    val ALL: Set<CapabilityId> = setOf(CLASSES, OBJECTS, REFERENCES, ROOTS, SHALLOW_SIZES)
}
