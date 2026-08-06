@file:Suppress("MagicNumber", "MaxLineLength", "TooManyFunctions")

package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.NativeHeapAnalysis
import com.androidperformancestudio.memory.model.NativeHeapSample
import java.nio.file.Files
import java.nio.file.Path

/**
 * Best-effort parser for a heapprofd (Perfetto native heap) `.pb` trace.
 *
 * Reads the protobuf wire format directly (no protobuf runtime dependency) and extracts the
 * pre-aggregated `ProfilePacket` data: allocation samples grouped by callstack plus interned
 * function names. Interning tables inside the ProfilePacket are used; traces that only intern via
 * the newer `InternedData` packets fall back to `<unknown>` symbol names.
 */
object NativeHeapTraceParser {
    fun parse(path: Path): NativeHeapAnalysis = parse(Files.readAllBytes(path))

    fun parse(bytes: ByteArray): NativeHeapAnalysis {
        val strings = HashMap<Long, String>()
        val frameFunctions = HashMap<Long, Long>()
        val callstackFrames = HashMap<Long, List<Long>>()
        val samples = ArrayList<RawSample>()

        val trace = ProtoReader(bytes)
        while (trace.isAtEnd.not()) {
            val tag = trace.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            if (fieldNumber == TRACE_PACKET_FIELD && wireType == WIRE_LENGTH_DELIMITED) {
                parsePacket(trace.readLengthDelimited(), strings, frameFunctions, callstackFrames, samples)
            } else {
                trace.skip(wireType)
            }
        }
        return buildAnalysis(strings, frameFunctions, callstackFrames, samples)
    }

    private fun parsePacket(
        bytes: ByteArray,
        strings: MutableMap<Long, String>,
        frameFunctions: MutableMap<Long, Long>,
        callstackFrames: MutableMap<Long, List<Long>>,
        samples: MutableList<RawSample>,
    ) {
        val packet = ProtoReader(bytes)
        while (packet.isAtEnd.not()) {
            val tag = packet.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            when {
                fieldNumber == PROFILE_PACKET_FIELD && wireType == WIRE_LENGTH_DELIMITED ->
                    parseProfilePacket(packet.readLengthDelimited(), strings, frameFunctions, callstackFrames, samples)
                fieldNumber == INTERNED_DATA_FIELD && wireType == WIRE_LENGTH_DELIMITED ->
                    parseInternedData(packet.readLengthDelimited(), strings, frameFunctions, callstackFrames)
                else -> packet.skip(wireType)
            }
        }
    }

    /**
     * Parses the trace-level `InternedData` packet (used on Android R+ instead of the interning
     * tables embedded in each ProfilePacket).
     */
    private fun parseInternedData(
        bytes: ByteArray,
        strings: MutableMap<Long, String>,
        frameFunctions: MutableMap<Long, Long>,
        callstackFrames: MutableMap<Long, List<Long>>,
    ) {
        val data = ProtoReader(bytes)
        while (data.isAtEnd.not()) {
            val tag = data.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            when {
                wireType != WIRE_LENGTH_DELIMITED -> data.skip(wireType)
                fieldNumber == INTERNED_FUNCTION_NAMES_FIELD -> parseInternedString(data.readLengthDelimited(), strings)
                fieldNumber == INTERNED_FRAMES_FIELD -> parseFrame(data.readLengthDelimited(), frameFunctions)
                fieldNumber == INTERNED_CALLSTACKS_FIELD -> parseCallstack(data.readLengthDelimited(), callstackFrames)
                else -> data.skip(wireType)
            }
        }
    }

    private fun parseProfilePacket(
        bytes: ByteArray,
        strings: MutableMap<Long, String>,
        frameFunctions: MutableMap<Long, Long>,
        callstackFrames: MutableMap<Long, List<Long>>,
        samples: MutableList<RawSample>,
    ) {
        val profile = ProtoReader(bytes)
        while (profile.isAtEnd.not()) {
            val tag = profile.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            when {
                wireType != WIRE_LENGTH_DELIMITED -> profile.skip(wireType)
                fieldNumber == INTERNED_STRINGS_FIELD -> parseInternedString(profile.readLengthDelimited(), strings)
                fieldNumber == FRAMES_FIELD -> parseFrame(profile.readLengthDelimited(), frameFunctions)
                fieldNumber == CALLSTACKS_FIELD -> parseCallstack(profile.readLengthDelimited(), callstackFrames)
                fieldNumber == PROCESS_DUMPS_FIELD -> parseProcessDump(profile.readLengthDelimited(), samples)
                else -> profile.skip(wireType)
            }
        }
    }

    private fun parseInternedString(
        bytes: ByteArray,
        strings: MutableMap<Long, String>,
    ) {
        val reader = ProtoReader(bytes)
        var iid = 0L
        var str = ""
        while (reader.isAtEnd.not()) {
            val tag = reader.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            when {
                fieldNumber == 1 && wireType == WIRE_VARINT -> iid = reader.readVarint()
                fieldNumber == 2 && wireType == WIRE_LENGTH_DELIMITED -> str = reader.readLengthDelimited().decodeToString()
                else -> reader.skip(wireType)
            }
        }
        if (iid != 0L) strings[iid] = str
    }

    private fun parseFrame(
        bytes: ByteArray,
        frameFunctions: MutableMap<Long, Long>,
    ) {
        val reader = ProtoReader(bytes)
        var iid = 0L
        var functionNameId = 0L
        while (reader.isAtEnd.not()) {
            val tag = reader.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            when {
                fieldNumber == 1 && wireType == WIRE_VARINT -> iid = reader.readVarint()
                fieldNumber == 2 && wireType == WIRE_VARINT -> functionNameId = reader.readVarint()
                else -> reader.skip(wireType)
            }
        }
        if (iid != 0L) frameFunctions[iid] = functionNameId
    }

    private fun parseCallstack(
        bytes: ByteArray,
        callstackFrames: MutableMap<Long, List<Long>>,
    ) {
        val reader = ProtoReader(bytes)
        var iid = 0L
        val frames = ArrayList<Long>()
        while (reader.isAtEnd.not()) {
            val tag = reader.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            when {
                fieldNumber == 1 && wireType == WIRE_VARINT -> iid = reader.readVarint()
                fieldNumber == 2 && wireType == WIRE_VARINT -> frames.add(reader.readVarint())
                else -> reader.skip(wireType)
            }
        }
        if (iid != 0L) callstackFrames[iid] = frames
    }

    private fun parseProcessDump(
        bytes: ByteArray,
        samples: MutableList<RawSample>,
    ) {
        val reader = ProtoReader(bytes)
        while (reader.isAtEnd.not()) {
            val tag = reader.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            if (fieldNumber == PROCESS_SAMPLES_FIELD && wireType == WIRE_LENGTH_DELIMITED) {
                parseSample(reader.readLengthDelimited(), samples)
            } else {
                reader.skip(wireType)
            }
        }
    }

    private fun parseSample(
        bytes: ByteArray,
        samples: MutableList<RawSample>,
    ) {
        val reader = ProtoReader(bytes)
        val sample = RawSample()
        while (reader.isAtEnd.not()) {
            val tag = reader.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            if (wireType != WIRE_VARINT) {
                reader.skip(wireType)
                continue
            }
            when (fieldNumber) {
                SAMPLE_CALLSTACK_ID -> sample.callstackId = reader.readVarint()
                SAMPLE_SELF_ALLOCATED -> sample.allocated = reader.readVarint()
                SAMPLE_SELF_FREED -> sample.freed = reader.readVarint()
                SAMPLE_ALLOC_COUNT -> sample.allocCount = reader.readVarint()
                SAMPLE_FREE_COUNT -> sample.freeCount = reader.readVarint()
                else -> reader.readVarint()
            }
        }
        samples.add(sample)
    }

    private fun buildAnalysis(
        strings: Map<Long, String>,
        frameFunctions: Map<Long, Long>,
        callstackFrames: Map<Long, List<Long>>,
        samples: List<RawSample>,
    ): NativeHeapAnalysis {
        val aggregates = HashMap<String, Aggregate>()
        var totalAllocated = 0L
        var totalFreed = 0L
        samples.forEach { sample ->
            totalAllocated += sample.allocated
            totalFreed += sample.freed
            val leafFrame = callstackFrames[sample.callstackId]?.lastOrNull()
            val functionName =
                if (leafFrame == null) {
                    UNKNOWN_SYMBOL
                } else {
                    strings[frameFunctions[leafFrame]] ?: UNKNOWN_SYMBOL
                }
            aggregates.getOrPut(functionName, ::Aggregate).add(sample)
        }
        return NativeHeapAnalysis(
            totalAllocatedBytes = totalAllocated,
            totalFreedBytes = totalFreed,
            sampleCount = samples.size,
            topAllocations =
                aggregates
                    .map { (name, aggregate) ->
                        NativeHeapSample(
                            functionName = name,
                            allocatedBytes = aggregate.allocated,
                            freedBytes = aggregate.freed,
                            allocCount = aggregate.allocCount,
                            freeCount = aggregate.freeCount,
                        )
                    }.sortedWith(compareByDescending<NativeHeapSample> { it.allocatedBytes }.thenBy { it.functionName })
                    .take(MAX_TOP_ALLOCATIONS),
        )
    }

    private class Aggregate {
        var allocated = 0L
        var freed = 0L
        var allocCount = 0L
        var freeCount = 0L

        fun add(sample: RawSample) {
            allocated += sample.allocated
            freed += sample.freed
            allocCount += sample.allocCount
            freeCount += sample.freeCount
        }
    }

    private class RawSample {
        var callstackId = 0L
        var allocated = 0L
        var freed = 0L
        var allocCount = 0L
        var freeCount = 0L
    }

    private const val UNKNOWN_SYMBOL = "<unknown>"
    private const val MAX_TOP_ALLOCATIONS = 50

    private const val TRACE_PACKET_FIELD = 1
    private const val PROFILE_PACKET_FIELD = 37
    private const val INTERNED_DATA_FIELD = 12
    private const val INTERNED_FUNCTION_NAMES_FIELD = 5
    private const val INTERNED_FRAMES_FIELD = 6
    private const val INTERNED_CALLSTACKS_FIELD = 7
    private const val INTERNED_STRINGS_FIELD = 1
    private const val FRAMES_FIELD = 2
    private const val CALLSTACKS_FIELD = 3
    private const val PROCESS_DUMPS_FIELD = 5
    private const val PROCESS_SAMPLES_FIELD = 2

    private const val SAMPLE_CALLSTACK_ID = 1
    private const val SAMPLE_SELF_ALLOCATED = 2
    private const val SAMPLE_SELF_FREED = 3
    private const val SAMPLE_ALLOC_COUNT = 5
    private const val SAMPLE_FREE_COUNT = 6

    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH_DELIMITED = 2
}

private class ProtoReader(
    private val bytes: ByteArray,
) {
    private var position = 0

    val isAtEnd: Boolean
        get() = position >= bytes.size

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
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
