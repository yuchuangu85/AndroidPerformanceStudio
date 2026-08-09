@file:Suppress("MagicNumber", "TooManyFunctions")

package com.androidperformancestudio.memory.analysis

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Parses Perfetto's `android.java_hprof` heap graph packets. */
object JavaHeapTraceParser {
    fun parse(path: Path): JavaHeapParseResult =
        try {
            parse(Files.readAllBytes(path))
        } catch (exception: IOException) {
            JavaHeapParseResult.Failure("Unable to read Java heap trace: ${exception.message}")
        }

    fun parse(bytes: ByteArray): JavaHeapParseResult =
        try {
            parseTrace(bytes)
        } catch (exception: IllegalArgumentException) {
            JavaHeapParseResult.Failure(exception.message ?: "Malformed Java heap trace.")
        } catch (exception: IllegalStateException) {
            JavaHeapParseResult.Failure(exception.message ?: "Malformed Java heap trace.")
        }

    private fun parseTrace(bytes: ByteArray): JavaHeapParseResult {
        val trace = ProtoReader(bytes)
        val sequences = hashMapOf<Long, HeapGraphAssembly>()
        val completeGraphs = mutableListOf<HeapGraphData>()
        var sawHeapGraph = false

        while (trace.isAtEnd.not()) {
            val tag = trace.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            when {
                fieldNumber == TRACE_PACKET && wireType == WIRE_LENGTH_DELIMITED -> {
                    val result = parsePacket(trace.readLengthDelimited(), sequences, completeGraphs)
                    sawHeapGraph = sawHeapGraph || result
                }
                // Compatibility with the initially shipped parser's bare-TracePacket fixtures.
                fieldNumber == TRACE_PACKET_HEAP_GRAPH && wireType == WIRE_LENGTH_DELIMITED -> {
                    sawHeapGraph = true
                    acceptChunk(0L, parseHeapGraphChunk(trace.readLengthDelimited()), sequences, completeGraphs)
                }
                else -> trace.skip(wireType)
            }
        }
        return when {
            completeGraphs.isNotEmpty() -> JavaHeapParseResult.Success(completeGraphs.last())
            sawHeapGraph -> JavaHeapParseResult.Failure("Incomplete java_hprof heap graph.")
            else -> JavaHeapParseResult.Failure("No java_hprof heap graph found in the trace.")
        }
    }

    private fun parsePacket(
        bytes: ByteArray,
        sequences: MutableMap<Long, HeapGraphAssembly>,
        completeGraphs: MutableList<HeapGraphData>,
    ): Boolean {
        val packet = ProtoReader(bytes)
        var sequenceId = 0L
        val chunks = mutableListOf<ByteArray>()
        while (packet.isAtEnd.not()) {
            val tag = packet.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            when {
                fieldNumber == TRUSTED_PACKET_SEQUENCE_ID && wireType == WIRE_VARINT ->
                    sequenceId = packet.readVarint()
                fieldNumber == TRACE_PACKET_HEAP_GRAPH && wireType == WIRE_LENGTH_DELIMITED ->
                    chunks += packet.readLengthDelimited()
                else -> packet.skip(wireType)
            }
        }
        chunks.forEach { acceptChunk(sequenceId, parseHeapGraphChunk(it), sequences, completeGraphs) }
        return chunks.isNotEmpty()
    }

    private fun acceptChunk(
        sequenceId: Long,
        chunk: HeapGraphChunk,
        sequences: MutableMap<Long, HeapGraphAssembly>,
        completeGraphs: MutableList<HeapGraphData>,
    ) {
        if (chunk.index == 0L && sequences[sequenceId]?.complete == true) {
            sequences.remove(sequenceId)
        }
        val state = sequences.getOrPut(sequenceId) { HeapGraphAssembly(sequenceId) }
        require(chunk.index == state.nextIndex) {
            "Missing java_hprof packet for sequence $sequenceId: expected index ${state.nextIndex}, got ${chunk.index}."
        }
        state.pid = chunk.pid ?: state.pid
        state.heapBytesAllocated = chunk.heapBytesAllocated ?: state.heapBytesAllocated
        state.types += chunk.types
        state.roots += chunk.roots
        state.fieldNames += chunk.fieldNames
        state.locationNames += chunk.locationNames
        chunk.objects.forEach { raw ->
            val objectId = if (raw.idDelta != 0L) state.lastObjectId + raw.idDelta else raw.id
            state.lastObjectId = objectId
            raw.heapType?.let { state.lastHeapType = it }
            state.objects += raw.toHeapGraphObject(objectId, state.lastHeapType)
        }
        state.nextIndex += 1
        state.complete = !chunk.continued
        if (state.complete) completeGraphs += state.toData()
    }

    @Suppress("CyclomaticComplexMethod")
    private fun parseHeapGraphChunk(bytes: ByteArray): HeapGraphChunk {
        val graph = ProtoReader(bytes)
        val chunk = HeapGraphChunk()
        while (graph.isAtEnd.not()) {
            val tag = graph.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            when {
                fieldNumber == HEAP_GRAPH_PID && wireType == WIRE_VARINT -> chunk.pid = graph.readVarint().toInt()
                fieldNumber == HEAP_GRAPH_OBJECTS && wireType == WIRE_LENGTH_DELIMITED ->
                    chunk.objects += parseObject(graph.readLengthDelimited())
                fieldNumber == HEAP_GRAPH_FIELD_NAMES && wireType == WIRE_LENGTH_DELIMITED ->
                    parseInternedString(graph.readLengthDelimited(), chunk.fieldNames)
                fieldNumber == HEAP_GRAPH_CONTINUED && wireType == WIRE_VARINT ->
                    chunk.continued = graph.readVarint() != 0L
                fieldNumber == HEAP_GRAPH_INDEX && wireType == WIRE_VARINT -> chunk.index = graph.readVarint()
                fieldNumber == HEAP_GRAPH_ROOTS && wireType == WIRE_LENGTH_DELIMITED ->
                    chunk.roots += parseRoot(graph.readLengthDelimited())
                fieldNumber == HEAP_GRAPH_LOCATION_NAMES && wireType == WIRE_LENGTH_DELIMITED ->
                    parseInternedString(graph.readLengthDelimited(), chunk.locationNames)
                fieldNumber == HEAP_GRAPH_TYPES && wireType == WIRE_LENGTH_DELIMITED ->
                    chunk.types += parseType(graph.readLengthDelimited())
                fieldNumber == HEAP_GRAPH_BYTES_ALLOCATED && wireType == WIRE_VARINT ->
                    chunk.heapBytesAllocated = graph.readVarint()
                else -> graph.skip(wireType)
            }
        }
        return chunk
    }

    private fun parseType(bytes: ByteArray): HeapGraphType {
        val reader = ProtoReader(bytes)
        var id = 0L
        var locationId = 0L
        var className = ""
        var objectSize = 0L
        var superclassId = 0L
        var kind = 0
        var classLoaderId = 0L
        val referenceFieldIds = arrayListOf<Long>()
        while (reader.isAtEnd.not()) {
            val tag = reader.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            when (fieldNumber) {
                TYPE_ID -> id = reader.readVarint()
                TYPE_LOCATION_ID -> locationId = reader.readVarint()
                TYPE_CLASS_NAME -> className = reader.readLengthDelimited().decodeToString()
                TYPE_OBJECT_SIZE -> objectSize = reader.readVarint()
                TYPE_SUPERCLASS_ID -> superclassId = reader.readVarint()
                TYPE_REFERENCE_FIELD_ID -> readPackedVarints(reader, wireType, referenceFieldIds)
                TYPE_KIND -> kind = reader.readVarint().toInt()
                TYPE_CLASSLOADER_ID -> classLoaderId = reader.readVarint()
                else -> reader.skip(wireType)
            }
        }
        return HeapGraphType(
            id,
            className,
            objectSize,
            superclassId,
            kind,
            referenceFieldIds,
            locationId,
            classLoaderId,
        )
    }

    @Suppress("CyclomaticComplexMethod")
    private fun parseObject(bytes: ByteArray): RawHeapGraphObject {
        val reader = ProtoReader(bytes)
        val raw = RawHeapGraphObject()
        while (reader.isAtEnd.not()) {
            val tag = reader.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            when (fieldNumber) {
                OBJECT_ID -> raw.id = reader.readVarint()
                OBJECT_TYPE_ID -> raw.typeId = reader.readVarint()
                OBJECT_SELF_SIZE -> raw.selfSize = reader.readVarint()
                OBJECT_REFERENCE_FIELD_ID -> readPackedVarints(reader, wireType, raw.referenceFieldIds)
                OBJECT_REFERENCE_OBJECT_ID -> readPackedVarints(reader, wireType, raw.referenceObjectIds)
                OBJECT_REFERENCE_OBJECT_ID_BASE -> raw.referenceObjectIdBase = reader.readVarint()
                OBJECT_ID_DELTA -> raw.idDelta = reader.readVarint()
                OBJECT_NATIVE_ALLOCATION_SIZE -> raw.nativeAllocationRegistrySize = reader.readSignedVarint()
                OBJECT_HEAP_TYPE_DELTA -> raw.heapType = reader.readVarint().toInt()
                OBJECT_RUNTIME_INTERNAL_ID -> readPackedVarints(reader, wireType, raw.runtimeInternalObjectIds)
                OBJECT_BITMAP_ID -> raw.bitmapId = reader.readSignedVarint()
                OBJECT_BITMAP_SOURCE_ID -> raw.bitmapSourceId = reader.readSignedVarint()
                OBJECT_BITMAP_WIDTH -> raw.bitmapWidth = reader.readVarint().toInt()
                OBJECT_BITMAP_HEIGHT -> raw.bitmapHeight = reader.readVarint().toInt()
                OBJECT_APPLICATION_VERSION -> raw.applicationInfoLongVersionCode = reader.readSignedVarint()
                else -> reader.skip(wireType)
            }
        }
        raw.referenceObjectIds.replaceAll { target -> if (target == 0L) 0L else raw.referenceObjectIdBase + target }
        return raw
    }

    private fun parseRoot(bytes: ByteArray): HeapGraphRoot {
        val reader = ProtoReader(bytes)
        val objectIds = arrayListOf<Long>()
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
        return HeapGraphRoot(objectIds, rootType)
    }

    private fun parseInternedString(
        bytes: ByteArray,
        target: MutableMap<Long, String>,
    ) {
        val reader = ProtoReader(bytes)
        var iid = 0L
        var value = ""
        while (reader.isAtEnd.not()) {
            val tag = reader.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            when {
                fieldNumber == INTERNED_ID && wireType == WIRE_VARINT -> iid = reader.readVarint()
                fieldNumber == INTERNED_STRING && wireType == WIRE_LENGTH_DELIMITED ->
                    value = reader.readLengthDelimited().decodeToString()
                else -> reader.skip(wireType)
            }
        }
        if (iid != 0L) target[iid] = value
    }

    private fun readPackedVarints(
        reader: ProtoReader,
        wireType: Int,
        target: MutableList<Long>,
    ) {
        when (wireType) {
            WIRE_LENGTH_DELIMITED -> {
                val packed = ProtoReader(reader.readLengthDelimited())
                while (packed.isAtEnd.not()) target += packed.readVarint()
            }
            WIRE_VARINT -> target += reader.readVarint()
            else -> reader.skip(wireType)
        }
    }

    private fun ProtoReader.readSignedVarint(): Long = readVarint()

    private const val TRACE_PACKET = 1
    private const val TRACE_PACKET_HEAP_GRAPH = 56
    private const val TRUSTED_PACKET_SEQUENCE_ID = 10
    private const val HEAP_GRAPH_PID = 1
    private const val HEAP_GRAPH_OBJECTS = 2
    private const val HEAP_GRAPH_FIELD_NAMES = 4
    private const val HEAP_GRAPH_CONTINUED = 5
    private const val HEAP_GRAPH_INDEX = 6
    private const val HEAP_GRAPH_ROOTS = 7
    private const val HEAP_GRAPH_LOCATION_NAMES = 8
    private const val HEAP_GRAPH_TYPES = 9
    private const val HEAP_GRAPH_BYTES_ALLOCATED = 10
    private const val TYPE_ID = 1
    private const val TYPE_LOCATION_ID = 2
    private const val TYPE_CLASS_NAME = 3
    private const val TYPE_OBJECT_SIZE = 4
    private const val TYPE_SUPERCLASS_ID = 5
    private const val TYPE_REFERENCE_FIELD_ID = 6
    private const val TYPE_KIND = 7
    private const val TYPE_CLASSLOADER_ID = 8
    private const val OBJECT_ID = 1
    private const val OBJECT_TYPE_ID = 2
    private const val OBJECT_SELF_SIZE = 3
    private const val OBJECT_REFERENCE_FIELD_ID = 4
    private const val OBJECT_REFERENCE_OBJECT_ID = 5
    private const val OBJECT_REFERENCE_OBJECT_ID_BASE = 6
    private const val OBJECT_ID_DELTA = 7
    private const val OBJECT_NATIVE_ALLOCATION_SIZE = 8
    private const val OBJECT_HEAP_TYPE_DELTA = 9
    private const val OBJECT_RUNTIME_INTERNAL_ID = 10
    private const val OBJECT_BITMAP_ID = 11
    private const val OBJECT_BITMAP_SOURCE_ID = 12
    private const val OBJECT_BITMAP_WIDTH = 13
    private const val OBJECT_BITMAP_HEIGHT = 14
    private const val OBJECT_APPLICATION_VERSION = 15
    private const val ROOT_OBJECT_IDS = 1
    private const val ROOT_TYPE = 2
    private const val INTERNED_ID = 1
    private const val INTERNED_STRING = 2
    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH_DELIMITED = 2
}

sealed interface JavaHeapParseResult {
    data class Success(
        val heapGraph: HeapGraphData,
    ) : JavaHeapParseResult

    data class Failure(
        val message: String,
    ) : JavaHeapParseResult
}

data class HeapGraphData(
    val types: List<HeapGraphType>,
    val objects: List<HeapGraphObject>,
    val roots: List<HeapGraphRoot>,
    val fieldNames: Map<Long, String>,
    val locationNames: Map<Long, String>,
    val pid: Int = 0,
    val heapBytesAllocated: Long? = null,
    val sequenceId: Long = 0L,
) {
    val isEmpty: Boolean get() = objects.isEmpty()
}

data class HeapGraphType(
    val id: Long,
    val className: String,
    val objectSize: Long,
    val superclassId: Long,
    val kind: Int,
    val referenceFieldIds: List<Long>,
    val locationId: Long = 0L,
    val classLoaderId: Long = 0L,
    val objectSizeKnown: Boolean = true,
    val superclassIdKnown: Boolean = true,
    val classLoaderIdKnown: Boolean = true,
    val kindKnown: Boolean = true,
) {
    val isArray: Boolean get() = kind == KIND_ARRAY

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
    val heapType: Int = 0,
    val runtimeInternalObjectIds: List<Long> = emptyList(),
    val nativeAllocationRegistrySize: Long? = null,
    val bitmapId: Long? = null,
    val bitmapSourceId: Long? = null,
    val bitmapWidth: Int? = null,
    val bitmapHeight: Int? = null,
    val applicationInfoLongVersionCode: Long? = null,
    val selfSizeKnown: Boolean = true,
)

data class HeapGraphRoot(
    val objectIds: List<Long>,
    val rootType: Int,
)

private data class HeapGraphAssembly(
    val sequenceId: Long,
    var pid: Int = 0,
    var heapBytesAllocated: Long? = null,
    var nextIndex: Long = 0L,
    var lastObjectId: Long = 0L,
    var lastHeapType: Int = 0,
    var complete: Boolean = false,
    val types: MutableList<HeapGraphType> = mutableListOf(),
    val objects: MutableList<HeapGraphObject> = mutableListOf(),
    val roots: MutableList<HeapGraphRoot> = mutableListOf(),
    val fieldNames: MutableMap<Long, String> = linkedMapOf(),
    val locationNames: MutableMap<Long, String> = linkedMapOf(),
) {
    fun toData() = HeapGraphData(types, objects, roots, fieldNames, locationNames, pid, heapBytesAllocated, sequenceId)
}

private data class HeapGraphChunk(
    var pid: Int? = null,
    var heapBytesAllocated: Long? = null,
    var continued: Boolean = false,
    var index: Long = 0L,
    val types: MutableList<HeapGraphType> = mutableListOf(),
    val objects: MutableList<RawHeapGraphObject> = mutableListOf(),
    val roots: MutableList<HeapGraphRoot> = mutableListOf(),
    val fieldNames: MutableMap<Long, String> = linkedMapOf(),
    val locationNames: MutableMap<Long, String> = linkedMapOf(),
)

private data class RawHeapGraphObject(
    var id: Long = 0L,
    var idDelta: Long = 0L,
    var typeId: Long = 0L,
    var selfSize: Long = 0L,
    var referenceObjectIdBase: Long = 0L,
    val referenceFieldIds: MutableList<Long> = mutableListOf(),
    val referenceObjectIds: MutableList<Long> = mutableListOf(),
    var heapType: Int? = null,
    val runtimeInternalObjectIds: MutableList<Long> = mutableListOf(),
    var nativeAllocationRegistrySize: Long? = null,
    var bitmapId: Long? = null,
    var bitmapSourceId: Long? = null,
    var bitmapWidth: Int? = null,
    var bitmapHeight: Int? = null,
    var applicationInfoLongVersionCode: Long? = null,
) {
    fun toHeapGraphObject(
        objectId: Long,
        resolvedHeapType: Int,
    ) = HeapGraphObject(
        objectId,
        idDelta,
        typeId,
        selfSize,
        referenceFieldIds,
        referenceObjectIds,
        resolvedHeapType,
        runtimeInternalObjectIds,
        nativeAllocationRegistrySize,
        bitmapId,
        bitmapSourceId,
        bitmapWidth,
        bitmapHeight,
        applicationInfoLongVersionCode,
    )
}
