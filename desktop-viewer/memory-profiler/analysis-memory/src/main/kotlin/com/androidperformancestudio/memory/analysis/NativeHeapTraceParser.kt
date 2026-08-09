@file:Suppress("MagicNumber", "MaxLineLength", "TooManyFunctions")

package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.NativeHeapAnalysis
import com.androidperformancestudio.memory.model.NativeHeapSample
import java.nio.file.Files
import java.nio.file.Path

/**
 * Best-effort summary reader for a heapprofd (Perfetto native heap) `.pb` trace.
 *
 * Reads the protobuf wire format directly (no protobuf runtime dependency) and extracts the
 * pre-aggregated `ProfilePacket` data: allocation samples grouped by callstack plus interned
 * function names. The raw trace remains authoritative and should be opened with Perfetto for full
 * sequence-state handling, symbolization, call trees, and guardrail diagnostics.
 */
object NativeHeapTraceParser {
    fun parse(path: Path): NativeHeapAnalysis = parse(Files.readAllBytes(path))

    fun parse(bytes: ByteArray): NativeHeapAnalysis = runCatching { parseValidTrace(bytes) }.getOrDefault(NativeHeapAnalysis())

    /**
     * Strict validation used only when the authoritative Trace Processor is unavailable. Unlike
     * [parse], malformed protobuf bytes are surfaced to the feature backend and are never
     * presented as a normal empty fallback result.
     */
    fun parseStrict(path: Path): NativeHeapAnalysis = parseValidTrace(Files.readAllBytes(path))

    private fun parseValidTrace(bytes: ByteArray): NativeHeapAnalysis {
        val sequences = HashMap<Long, InterningState>()
        val samples = ArrayList<ResolvedSample>()

        val trace = ProtoReader(bytes)
        while (trace.isAtEnd.not()) {
            val tag = trace.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            if (fieldNumber == TRACE_PACKET_FIELD && wireType == WIRE_LENGTH_DELIMITED) {
                parsePacket(trace.readLengthDelimited(), sequences, samples)
            } else {
                trace.skip(wireType)
            }
        }
        return buildAnalysis(samples)
    }

    private fun parsePacket(
        bytes: ByteArray,
        sequences: MutableMap<Long, InterningState>,
        samples: MutableList<ResolvedSample>,
    ) {
        val packet = ProtoReader(bytes)
        var sequenceId = 0L
        var clearIncrementalState = false
        val internedData = mutableListOf<ByteArray>()
        val profilePackets = mutableListOf<ByteArray>()
        while (packet.isAtEnd.not()) {
            val tag = packet.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            when {
                fieldNumber == PROFILE_PACKET_FIELD && wireType == WIRE_LENGTH_DELIMITED ->
                    profilePackets += packet.readLengthDelimited()
                fieldNumber == INTERNED_DATA_FIELD && wireType == WIRE_LENGTH_DELIMITED ->
                    internedData += packet.readLengthDelimited()
                fieldNumber == TRUSTED_PACKET_SEQUENCE_ID_FIELD && wireType == WIRE_VARINT ->
                    sequenceId = packet.readVarint()
                fieldNumber == INCREMENTAL_STATE_CLEARED_FIELD && wireType == WIRE_VARINT ->
                    clearIncrementalState = packet.readVarint() != 0L
                else -> packet.skip(wireType)
            }
        }
        val state = sequences.getOrPut(sequenceId, ::InterningState)
        if (clearIncrementalState) state.clear()
        internedData.forEach { parseInternedData(it, state.strings, state.frameFunctions, state.callstackFrames) }
        profilePackets.forEach { parseProfilePacket(it, state, samples) }
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
        state: InterningState,
        samples: MutableList<ResolvedSample>,
    ) {
        val profile = ProtoReader(bytes)
        val internedStrings = mutableListOf<ByteArray>()
        val frames = mutableListOf<ByteArray>()
        val callstacks = mutableListOf<ByteArray>()
        val processDumps = mutableListOf<ByteArray>()
        while (profile.isAtEnd.not()) {
            val tag = profile.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            when {
                wireType != WIRE_LENGTH_DELIMITED -> profile.skip(wireType)
                fieldNumber == INTERNED_STRINGS_FIELD -> internedStrings += profile.readLengthDelimited()
                fieldNumber == FRAMES_FIELD -> frames += profile.readLengthDelimited()
                fieldNumber == CALLSTACKS_FIELD -> callstacks += profile.readLengthDelimited()
                fieldNumber == PROCESS_DUMPS_FIELD -> processDumps += profile.readLengthDelimited()
                else -> profile.skip(wireType)
            }
        }
        internedStrings.forEach { parseInternedString(it, state.strings) }
        frames.forEach { parseFrame(it, state.frameFunctions) }
        callstacks.forEach { parseCallstack(it, state.callstackFrames) }
        val rawSamples = mutableListOf<RawSample>()
        processDumps.forEach { parseProcessDump(it, rawSamples) }
        rawSamples.forEach { sample ->
            val leafFrame = state.callstackFrames[sample.callstackId]?.lastOrNull()
            val functionName = leafFrame?.let(state.frameFunctions::get)?.let(state.strings::get) ?: UNKNOWN_SYMBOL
            samples += ResolvedSample(functionName, sample)
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

    private fun buildAnalysis(samples: List<ResolvedSample>): NativeHeapAnalysis {
        val aggregates = HashMap<String, Aggregate>()
        var totalAllocated = 0L
        var totalFreed = 0L
        samples.forEach { sample ->
            totalAllocated += sample.allocated
            totalFreed += sample.freed
            aggregates.getOrPut(sample.functionName, ::Aggregate).add(sample.raw)
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

    private data class ResolvedSample(
        val functionName: String,
        val raw: RawSample,
    ) {
        val allocated: Long get() = raw.allocated
        val freed: Long get() = raw.freed
    }

    private class InterningState {
        val strings = HashMap<Long, String>()
        val frameFunctions = HashMap<Long, Long>()
        val callstackFrames = HashMap<Long, List<Long>>()

        fun clear() {
            strings.clear()
            frameFunctions.clear()
            callstackFrames.clear()
        }
    }

    private const val UNKNOWN_SYMBOL = "<unknown>"
    private const val MAX_TOP_ALLOCATIONS = 50

    private const val TRACE_PACKET_FIELD = 1
    private const val PROFILE_PACKET_FIELD = 37
    private const val INTERNED_DATA_FIELD = 12
    private const val TRUSTED_PACKET_SEQUENCE_ID_FIELD = 10
    private const val INCREMENTAL_STATE_CLEARED_FIELD = 41
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

internal class ProtoReader(
    private val bytes: ByteArray,
) {
    private var position = 0

    val isAtEnd: Boolean
        get() = position >= bytes.size

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(position < bytes.size) { "Truncated protobuf varint" }
            require(shift < Long.SIZE_BITS) { "Protobuf varint is too long" }
            val byte = bytes[position++].toInt() and 0xff
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    fun readTag(): Int = readVarint().toInt()

    fun readLengthDelimited(): ByteArray {
        val encodedLength = readVarint()
        require(encodedLength <= Int.MAX_VALUE) { "Protobuf field is too large" }
        val length = encodedLength.toInt()
        val remaining = bytes.size - position
        require(length <= remaining) { "Truncated length-delimited protobuf field" }
        val result = bytes.copyOfRange(position, position + length)
        position += length
        return result
    }

    fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> skipBytes(8)
            2 -> readLengthDelimited()
            5 -> skipBytes(4)
            else -> error("Unsupported protobuf wire type: $wireType")
        }
    }

    private fun skipBytes(count: Int) {
        require(count <= bytes.size - position) { "Truncated fixed-width protobuf field" }
        position += count
    }
}
