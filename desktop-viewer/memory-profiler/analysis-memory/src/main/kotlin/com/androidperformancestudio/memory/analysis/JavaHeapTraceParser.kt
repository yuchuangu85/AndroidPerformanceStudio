@file:Suppress("MagicNumber")

package com.androidperformancestudio.memory.analysis

import java.nio.file.Files
import java.nio.file.Path

/**
 * Best-effort parser for a perfetto `java_hprof` trace — the Java heap snapshot Android 11+
 * produces via the `android.java_hprof` data source.
 *
 * The heap graph is carried as `TracePacket.heap_graph` (field 56); the payload is a `HeapGraph`
 * (perfetto `protos/perfetto/trace/profiling/heap_graph.proto`) with `types` (9), `objects` (2),
 * `roots` (7), `field_names` (4) and `location_names` (8). Class/field names are interned strings
 * (`iid`/`str`). Reads the protobuf wire format directly (no protobuf runtime), matching the
 * `NativeHeapTraceParser` approach.
 */
object JavaHeapTraceParser {
    fun parse(path: Path): JavaHeapParseResult =
        try {
            parse(Files.readAllBytes(path))
        } catch (exception: Exception) {
            JavaHeapParseResult.Failure("Unable to read Java heap trace: ${exception.message}")
        }

    fun parse(bytes: ByteArray): JavaHeapParseResult =
        try {
            val reader = WireReader(bytes)
            val types = ArrayList<HeapGraphType>()
            val objects = ArrayList<HeapGraphObject>()
            val roots = ArrayList<HeapGraphRoot>()
            val fieldNames = HashMap<Long, String>()
            val locationNames = HashMap<Long, String>()
            var sawHeapGraph = false

            while (reader.isAtEnd.not()) {
                val tag = reader.readTag()
                val fieldNumber = tag ushr 3
                val wireType = tag and 7
                if (fieldNumber == TRACE_PACKET_HEAP_GRAPH && wireType == WIRE_LENGTH_DELIMITED) {
                    sawHeapGraph = true
                    parseHeapGraph(reader.readLengthDelimited(), types, objects, roots, fieldNames, locationNames)
                } else {
                    reader.skip(wireType)
                }
            }
            if (!sawHeapGraph) {
                JavaHeapParseResult.Failure("No java_hprof heap graph found in the trace.")
            } else {
                JavaHeapParseResult.Success(
                    HeapGraphData(
                        types = types,
                        objects = objects,
                        roots = roots,
                        fieldNames = fieldNames,
                        locationNames = locationNames,
                    ),
                )
            }
        } catch (exception: JavaHeapFormatException) {
            JavaHeapParseResult.Failure(exception.message ?: "Malformed Java heap trace.")
        }

    private fun parseHeapGraph(
        bytes: ByteArray,
        types: MutableList<HeapGraphType>,
        objects: MutableList<HeapGraphObject>,
        roots: MutableList<HeapGraphRoot>,
        fieldNames: MutableMap<Long, String>,
        locationNames: MutableMap<Long, String>,
    ) {
        val graph = WireReader(bytes)
        var previousObjectId = 0L
        while (graph.isAtEnd.not()) {
            val tag = graph.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            when {
                fieldNumber == HEAP_GRAPH_TYPES && wireType == WIRE_LENGTH_DELIMITED ->
                    types += parseType(graph.readLengthDelimited())
                fieldNumber == HEAP_GRAPH_OBJECTS && wireType == WIRE_LENGTH_DELIMITED -> {
                    val objectEntry = parseObject(graph.readLengthDelimited())
                    // The `id` field may be omitted in favor of a delta from the previous object.
                    previousObjectId = if (objectEntry.id == 0L) previousObjectId + objectEntry.idDelta else objectEntry.id
                    objects += objectEntry.copy(id = previousObjectId)
                }
                fieldNumber == HEAP_GRAPH_ROOTS && wireType == WIRE_LENGTH_DELIMITED ->
                    roots += parseRoot(graph.readLengthDelimited())
                fieldNumber == HEAP_GRAPH_FIELD_NAMES && wireType == WIRE_LENGTH_DELIMITED ->
                    parseInternedString(graph.readLengthDelimited(), fieldNames)
                fieldNumber == HEAP_GRAPH_LOCATION_NAMES && wireType == WIRE_LENGTH_DELIMITED ->
                    parseInternedString(graph.readLengthDelimited(), locationNames)
                else -> graph.skip(wireType)
            }
        }
    }

    private fun parseType(bytes: ByteArray): HeapGraphType {
        val reader = WireReader(bytes)
        var id = 0L
        var className = ""
        var objectSize = 0L
        var superclassId = 0L
        var kind = 0
        val referenceFieldIds = ArrayList<Long>()
        while (reader.isAtEnd.not()) {
            val tag = reader.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            when (fieldNumber) {
                TYPE_ID -> id = reader.readVarint()
                TYPE_CLASS_NAME -> className = reader.readLengthDelimited().decodeToString()
                TYPE_OBJECT_SIZE -> objectSize = reader.readVarint()
                TYPE_SUPERCLASS_ID -> superclassId = reader.readVarint()
                TYPE_KIND -> kind = reader.readVarint().toInt()
                TYPE_REFERENCE_FIELD_ID -> readPackedVarints(reader, wireType, referenceFieldIds)
                else -> reader.skip(wireType)
            }
        }
        return HeapGraphType(
            id = id,
            className = className,
            objectSize = objectSize,
            superclassId = superclassId,
            kind = kind,
            referenceFieldIds = referenceFieldIds,
        )
    }

    private fun parseObject(bytes: ByteArray): HeapGraphObject {
        val reader = WireReader(bytes)
        var id = 0L
        var idDelta = 0L
        var typeId = 0L
        var selfSize = 0L
        var referenceFieldIdBase = 0L
        val referenceFieldIds = ArrayList<Long>()
        val referenceObjectIds = ArrayList<Long>()
        while (reader.isAtEnd.not()) {
            val tag = reader.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            when (fieldNumber) {
                OBJECT_ID -> id = reader.readVarint()
                OBJECT_ID_DELTA -> idDelta = reader.readVarint()
                OBJECT_TYPE_ID -> typeId = reader.readVarint()
                OBJECT_SELF_SIZE -> selfSize = reader.readVarint()
                OBJECT_REFERENCE_FIELD_ID -> readPackedVarints(reader, wireType, referenceFieldIds)
                OBJECT_REFERENCE_OBJECT_ID -> readPackedVarints(reader, wireType, referenceObjectIds)
                OBJECT_REFERENCE_FIELD_ID_BASE -> referenceFieldIdBase = reader.readVarint()
                else -> reader.skip(wireType)
            }
        }
        val resolvedReferenceObjectIds =
            referenceObjectIds.map { target -> if (target == 0L) 0L else referenceFieldIdBase + target }
        return HeapGraphObject(
            id = id,
            idDelta = idDelta,
            typeId = typeId,
            selfSize = selfSize,
            referenceFieldIds = referenceFieldIds,
            referenceObjectIds = resolvedReferenceObjectIds,
        )
    }

    private fun parseRoot(bytes: ByteArray): HeapGraphRoot {
        val reader = WireReader(bytes)
        val objectIds = ArrayList<Long>()
        var rootType = 0
        while (reader.isAtEnd.not()) {
            val tag = reader.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            when (fieldNumber) {
                ROOT_OBJECT_IDS -> readPackedVarints(reader, wireType, objectIds)
                ROOT_TYPE -> rootType = reader.readVarint().toInt()
                else -> reader.skip(wireType)
            }
        }
        return HeapGraphRoot(objectIds = objectIds, rootType = rootType)
    }

    private fun parseInternedString(
        bytes: ByteArray,
        target: MutableMap<Long, String>,
    ) {
        val reader = WireReader(bytes)
        var iid = 0L
        var value = ""
        while (reader.isAtEnd.not()) {
            val tag = reader.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            when {
                fieldNumber == INTERNED_ID && wireType == WIRE_VARINT -> iid = reader.readVarint()
                fieldNumber == INTERNED_STRING && wireType == WIRE_LENGTH_DELIMITED -> value = reader.readLengthDelimited().decodeToString()
                else -> reader.skip(wireType)
            }
        }
        if (iid != 0L) target[iid] = value
    }

    /** Repeated numeric fields may arrive packed (length-delimited run of varints) or unpacked. */
    private fun readPackedVarints(
        reader: WireReader,
        wireType: Int,
        target: MutableList<Long>,
    ) {
        if (wireType == WIRE_LENGTH_DELIMITED) {
            val packed = WireReader(reader.readLengthDelimited())
            while (packed.isAtEnd.not()) {
                target += packed.readVarint()
            }
        } else if (wireType == WIRE_VARINT) {
            target += reader.readVarint()
        } else {
            reader.skip(wireType)
        }
    }

    // TracePacket / HeapGraph wire fields (perfetto heap_graph.proto).
    private const val TRACE_PACKET_HEAP_GRAPH = 56
    private const val HEAP_GRAPH_TYPES = 9
    private const val HEAP_GRAPH_OBJECTS = 2
    private const val HEAP_GRAPH_ROOTS = 7
    private const val HEAP_GRAPH_FIELD_NAMES = 4
    private const val HEAP_GRAPH_LOCATION_NAMES = 8

    private const val TYPE_ID = 1
    private const val TYPE_CLASS_NAME = 3
    private const val TYPE_OBJECT_SIZE = 4
    private const val TYPE_SUPERCLASS_ID = 5
    private const val TYPE_REFERENCE_FIELD_ID = 6
    private const val TYPE_KIND = 7

    private const val OBJECT_ID = 1
    private const val OBJECT_TYPE_ID = 2
    private const val OBJECT_SELF_SIZE = 3
    private const val OBJECT_REFERENCE_FIELD_ID = 4
    private const val OBJECT_REFERENCE_OBJECT_ID = 5
    private const val OBJECT_REFERENCE_FIELD_ID_BASE = 6
    private const val OBJECT_ID_DELTA = 7

    private const val ROOT_OBJECT_IDS = 1
    private const val ROOT_TYPE = 2

    private const val INTERNED_ID = 1
    private const val INTERNED_STRING = 2

    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH_DELIMITED = 2
}

sealed interface JavaHeapParseResult {
    data class Success(val heapGraph: HeapGraphData) : JavaHeapParseResult
    data class Failure(val message: String) : JavaHeapParseResult
}

/** Raw perfetto `java_hprof` heap graph, ready to convert into the `HeapDump` model. */
data class HeapGraphData(
    val types: List<HeapGraphType>,
    val objects: List<HeapGraphObject>,
    val roots: List<HeapGraphRoot>,
    val fieldNames: Map<Long, String>,
    val locationNames: Map<Long, String>,
) {
    val isEmpty: Boolean
        get() = objects.isEmpty()
}

data class HeapGraphType(
    val id: Long,
    val className: String,
    val objectSize: Long,
    val superclassId: Long,
    val kind: Int,
    val referenceFieldIds: List<Long>,
) {
    val isArray: Boolean
        get() = kind == KIND_ARRAY

    companion object {
        const val KIND_ARRAY = 4
    }
}

data class HeapGraphObject(
    val id: Long,
    val idDelta: Long,
    val typeId: Long,
    val selfSize: Long,
    val referenceFieldIds: List<Long>,
    val referenceObjectIds: List<Long>,
)

data class HeapGraphRoot(
    val objectIds: List<Long>,
    val rootType: Int,
)

private class JavaHeapFormatException(message: String) : Exception(message)

private class WireReader(
    private val bytes: ByteArray,
) {
    private var position = 0

    val isAtEnd: Boolean
        get() = position >= bytes.size

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (position >= bytes.size) throw JavaHeapFormatException("truncated varint")
            val byte = bytes[position++].toInt() and 0xff
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    fun readTag(): Int = readVarint().toInt()

    fun readLengthDelimited(): ByteArray {
        val length = readVarint().toInt()
        val remaining = bytes.size - position
        val actualLength = if (length < 0) remaining else minOf(length, remaining)
        val result = bytes.copyOfRange(position, position + actualLength)
        position += actualLength
        return result
    }

    fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> position = (position + 8).coerceAtMost(bytes.size)
            2 -> readLengthDelimited()
            5 -> position = (position + 4).coerceAtMost(bytes.size)
            else -> position = bytes.size
        }
    }
}
