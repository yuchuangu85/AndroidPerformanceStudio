package com.androidperformancestudio.arttrace

import java.nio.file.Files
import java.nio.file.Path

/**
 * Parser for ART method-trace (`.trace`) files — the binary output of `am profile start/stop` and
 * `Debug.startMethodTracing`.
 *
 * Byte layout follows AOSP `art/runtime/trace.cc` and `art/runtime/trace.h`:
 * - **Streaming versions 4/5** (default on modern Android): a 32-byte header (magic `'SLOW'`,
 *   version, start time in ns) followed by length-prefixed packets:
 *   `kThreadInfoHeaderV2`, `kMethodInfoHeaderV2`, `kEntryHeaderV2` (block of LEB128-delta
 *   records), `kSummaryHeaderV2`.
 * - **Classic versions 2/3** (legacy): a 32-byte header (magic, version, data offset, start time
 *   in µs, record size 10/14) followed by a text section (`*threads` / `*methods` / `*end`) and
 *   fixed-size records `[2B thread id][4B (method index << 2 | action)][4B cpu µs][4B wall µs]`.
 *
 * The low two bits of every method word hold the action (0 = enter, 1 = exit, 2 = unroll);
 * records in both layouts are normalized to [ArtTraceEvent] with a monotonic nanosecond time.
 * Events carry the method *index* (`methodWord ushr 2`); consumers resolve display names through
 * [ArtTraceAnalysis.methods], falling back to the raw id for symbols missing from the table.
 */
object ArtTraceParser {
    fun parse(path: Path): ArtTraceParseResult =
        try {
            parse(Files.readAllBytes(path))
        } catch (exception: Exception) {
            ArtTraceParseResult.Failure("Unable to read trace file: ${exception.message}")
        }

    fun parse(bytes: ByteArray): ArtTraceParseResult =
        try {
            val reader = ArtTraceBinaryReader(bytes)
            if (reader.readU32() != TRACE_MAGIC) {
                return ArtTraceParseResult.Failure("Not an ART method trace (bad magic).")
            }
            val version = reader.readU16()
            when (version) {
                in 4..5 -> parseStreaming(reader, version)
                in 2..3 -> parseClassic(reader, version)
                1 -> ArtTraceParseResult.Failure("Trace version 1 is not supported (legacy Dalvik format).")
                else -> ArtTraceParseResult.Failure("Unsupported trace version $version.")
            }
        } catch (exception: ArtTraceFormatException) {
            ArtTraceParseResult.Failure(exception.message ?: "Malformed trace.")
        }

    // --- Streaming format (versions 4/5) ------------------------------------------------

    private fun parseStreaming(reader: ArtTraceBinaryReader, version: Int): ArtTraceParseResult {
        val startTimeNanos = reader.readU64() // bytes 6..13
        reader.skip(HEADER_LENGTH - 14) // pad to the 32-byte header
        val dualClock = version == 5
        val methods = LinkedHashMap<Long, ArtMethod>()
        val threads = LinkedHashMap<Int, ArtThread>()
        val events = ArrayList<ArtTraceEvent>()
        val warnings = ArrayList<String>()

        while (!reader.isAtEnd) {
            when (val packetType = reader.readU8()) {
                PACKET_THREAD_INFO -> {
                    val tid = reader.readU32().toInt()
                    val name = reader.readBytes(reader.readU16()).decodeToString()
                    threads[tid] = ArtThread(tid, name)
                }
                PACKET_METHOD_INFO -> {
                    val methodId = reader.readU64()
                    val info = reader.readBytes(reader.readU16()).decodeToString()
                    methods[methodId] = parseMethodInfo(methodId, info)
                }
                PACKET_ENTRY_BLOCK -> {
                    val threadId = reader.readU32().toInt()
                    val numRecords = reader.readU24()
                    val block = ArtTraceBinaryReader(reader.readBytes(reader.readU32().toInt()))
                    parseEntryBlock(block, numRecords, threadId, dualClock, events, warnings)
                }
                PACKET_SUMMARY -> reader.skip(reader.readU16())
                else -> {
                    warnings += "Unknown streaming packet type $packetType; stopped at the end of the trace."
                    break
                }
            }
        }
        return buildAnalysis(header(version, startTimeNanos, dualClock), methods, threads, events, warnings)
    }

    private fun parseEntryBlock(
        block: ArtTraceBinaryReader,
        numRecords: Int,
        threadId: Int,
        dualClock: Boolean,
        events: MutableList<ArtTraceEvent>,
        warnings: MutableList<String>,
    ) {
        // Within a block the first record's method and timestamps are absolute; the rest are deltas.
        var methodValue = 0L
        var timeNanos = 0L
        var cpuNanos = 0L
        repeat(numRecords) {
            methodValue += block.readSleb128()
            timeNanos += block.readUleb128()
            if (dualClock) {
                cpuNanos += block.readUleb128()
            }
            val action = actionOf(methodValue)
            if (action == null) {
                warnings += "Ignored method record with unused action bits 0x03."
                return@repeat
            }
            events +=
                ArtTraceEvent(
                    threadId = threadId,
                    methodId = methodValue ushr 2,
                    action = action,
                    timeNanos = timeNanos,
                    cpuNanos = if (dualClock) cpuNanos else null,
                )
        }
    }

    // --- Classic format (versions 2/3) --------------------------------------------------

    private fun parseClassic(reader: ArtTraceBinaryReader, version: Int): ArtTraceParseResult {
        val dataOffset = reader.readU16() // bytes 6..7
        val startTimeNanos = reader.readU64() * NANOS_PER_MICRO // bytes 8..15
        reader.skip(HEADER_LENGTH - 16) // pad to the 32-byte header
        val dualClock = version == 3
        val methods = LinkedHashMap<Long, ArtMethod>()
        val threads = LinkedHashMap<Int, ArtThread>()
        val events = ArrayList<ArtTraceEvent>()
        val warnings = ArrayList<String>()

        // The text section (method/thread tables) sits between the header and the records. ART writes
        // `*threads` / `*methods` / `*end` here; fall back to the recorded data offset when absent.
        val textBytes = reader.readUntilInclusive(END_MARKER)
        if (textBytes.endsWith(END_MARKER)) {
            parseClassicTables(textBytes.decodeToString(), methods, threads)
        } else {
            reader.position = dataOffset.coerceAtLeast(HEADER_LENGTH).coerceAtMost(textBytes.size + HEADER_LENGTH)
        }

        val recordSize = if (dualClock) RECORD_SIZE_DUAL else RECORD_SIZE_SINGLE
        while (reader.remaining() >= recordSize) {
            val threadId = reader.readU16()
            val methodValue = reader.readU32()
            val cpuMicros = reader.readU32()
            val wallMicros = if (dualClock) reader.readU32() else 0L
            val action = actionOf(methodValue)
            if (action == null) {
                warnings += "Ignored method record with unused action bits 0x03."
                continue
            }
            events +=
                ArtTraceEvent(
                    threadId = threadId,
                    methodId = methodValue ushr 2,
                    action = action,
                    timeNanos = if (dualClock) wallMicros * NANOS_PER_MICRO else cpuMicros * NANOS_PER_MICRO,
                    cpuNanos = cpuMicros.times(NANOS_PER_MICRO).takeIf { dualClock },
                )
        }
        return buildAnalysis(header(version, startTimeNanos, dualClock), methods, threads, events, warnings)
    }

    private fun parseClassicTables(
        text: String,
        methods: MutableMap<Long, ArtMethod>,
        threads: MutableMap<Int, ArtThread>,
    ) {
        var section = ""
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("*threads") -> section = "threads"
                line.startsWith("*methods") -> section = "methods"
                line.startsWith("*end") -> section = ""
                line.isEmpty() -> Unit
                section == "threads" -> {
                    val tokens = line.split('\t').filter { it.isNotEmpty() }
                    if (tokens.isNotEmpty()) {
                        val id = tokens[0].toLongOrNull(16) ?: tokens[0].toLongOrNull(10) ?: return@forEach
                        threads[id.toInt()] = ArtThread(id.toInt(), tokens.drop(1).joinToString(" "))
                    }
                }
                section == "methods" -> {
                    val tokens = line.split('\t').filter { it.isNotEmpty() }
                    if (tokens.isNotEmpty()) {
                        val id = tokens[0].toLongOrNull(16) ?: tokens[0].toLongOrNull(10) ?: return@forEach
                        methods[id ushr 2] =
                            ArtMethod(
                                methodId = id ushr 2,
                                className = tokens.getOrElse(1) { "" },
                                methodName = tokens.getOrElse(2) { "" },
                                signature = tokens.getOrElse(3) { "" },
                                sourceFile = tokens.getOrElse(4) { "" },
                            )
                    }
                }
            }
        }
    }

    // --- Shared helpers -----------------------------------------------------------------

    private fun parseMethodInfo(methodId: Long, info: String): ArtMethod {
        val fields = info.trim().split('\t')
        return ArtMethod(
            methodId = methodId,
            className = fields.getOrElse(0) { "" },
            methodName = fields.getOrElse(1) { "" },
            signature = fields.getOrElse(2) { "" },
            sourceFile = fields.getOrElse(3) { "" },
        )
    }

    private fun actionOf(methodValue: Long): ArtTraceAction? =
        when (methodValue and ACTION_MASK) {
            0L -> ArtTraceAction.ENTER
            1L -> ArtTraceAction.EXIT
            2L -> ArtTraceAction.UNROLL
            else -> null
        }

    private fun buildAnalysis(
        header: ArtTraceHeader,
        methods: Map<Long, ArtMethod>,
        threads: Map<Int, ArtThread>,
        events: List<ArtTraceEvent>,
        warnings: List<String>,
    ): ArtTraceParseResult {
        var startNanos = Long.MAX_VALUE
        var endNanos = Long.MIN_VALUE
        events.forEach { event ->
            if (event.timeNanos < startNanos) startNanos = event.timeNanos
            if (event.timeNanos > endNanos) endNanos = event.timeNanos
        }
        if (startNanos == Long.MAX_VALUE) startNanos = header.startTimeNanos
        if (endNanos == Long.MIN_VALUE) endNanos = header.startTimeNanos
        return ArtTraceParseResult.Success(
            ArtTraceAnalysis(
                header = header,
                methods = methods,
                threads = threads,
                events = events,
                startTimeNanos = startNanos,
                endTimeNanos = endNanos,
                warnings = warnings,
            ),
        )
    }

    private fun header(version: Int, startTimeNanos: Long, dualClock: Boolean): ArtTraceHeader =
        ArtTraceHeader(
            version = version,
            startTimeNanos = startTimeNanos,
            clockSource = if (dualClock) ArtClockSource.DUAL else ArtClockSource.SINGLE,
        )

    private fun ByteArray.endsWith(needle: ByteArray): Boolean {
        if (size < needle.size) return false
        for (index in needle.indices) {
            if (this[size - needle.size + index] != needle[index]) return false
        }
        return true
    }

    private const val TRACE_MAGIC = 0x574f4c53L // 'SLOW'
    private const val HEADER_LENGTH = 32
    private const val PACKET_THREAD_INFO = 0
    private const val PACKET_METHOD_INFO = 1
    private const val PACKET_ENTRY_BLOCK = 2
    private const val PACKET_SUMMARY = 3
    private const val RECORD_SIZE_SINGLE = 10
    private const val RECORD_SIZE_DUAL = 14
    private const val NANOS_PER_MICRO = 1000L
    private const val ACTION_MASK = 0x03L
    private val END_MARKER = "*end\n".toByteArray(Charsets.US_ASCII)
}
