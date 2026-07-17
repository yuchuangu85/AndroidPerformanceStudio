@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "MagicNumber",
    "MaxLineLength",
    "NestedBlockDepth",
    "ReturnCount",
    "ThrowsCount",
    "TooManyFunctions",
)

package com.androidperformancestudio.application

import com.androidperformancestudio.model.NormalizedProfileRecord
import com.androidperformancestudio.model.NormalizedSample
import com.androidperformancestudio.model.ProfileExecutionType
import com.androidperformancestudio.model.ProfileFrame
import com.androidperformancestudio.model.ProfileMetadata
import com.androidperformancestudio.model.ProfileThread
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import kotlin.math.roundToLong

internal data class GeckoProfileReadSummary(
    val recordCount: Long,
    val sampleCount: Long,
    val decompressedBytes: Long,
)

internal class GeckoProfileReader(
    private val maxDecompressedBytes: Long = DEFAULT_MAX_DECOMPRESSED_BYTES,
    private val maxJsonDepth: Int = DEFAULT_MAX_JSON_DEPTH,
) {
    init {
        require(maxDecompressedBytes > 0) { "maxDecompressedBytes must be positive" }
        require(maxJsonDepth > 0) { "maxJsonDepth must be positive" }
    }

    fun read(
        input: InputStream,
        ensureActive: () -> Unit,
        onRecord: (NormalizedProfileRecord) -> Unit,
    ): GeckoProfileReadSummary {
        val decompressed = LimitedInputStream(GZIPInputStream(input), maxDecompressedBytes)
        val json = JsonStreamReader(InputStreamReader(decompressed, StandardCharsets.UTF_8), maxJsonDepth)
        val ids = FrameIdentityRegistry()
        var threadCount = 0L
        var sampleCount = 0L
        var sawThreads = false
        onRecord(
            NormalizedProfileRecord.Metadata(
                ProfileMetadata(
                    eventTypes = listOf(GECKO_SAMPLE_EVENT),
                    appPackageName = null,
                    appType = "gecko-profile",
                    androidSdkVersion = null,
                    androidBuildType = null,
                    traceOffCpu = false,
                ),
            ),
        )
        json.readObject { name ->
            ensureActive()
            if (name == "threads") {
                sawThreads = true
                json.readArray {
                    ensureActive()
                    val thread = readThread(json)
                    onRecord(NormalizedProfileRecord.Thread(ProfileThread(thread.pid, thread.tid, thread.name)))
                    thread.samples.forEach { sample ->
                        ensureActive()
                        onRecord(NormalizedProfileRecord.Sample(thread.normalizedSample(sample, ids)))
                        sampleCount++
                    }
                    threadCount++
                }
            } else {
                json.skipValue()
            }
        }
        if (!sawThreads) throw GeckoProfileFormatException("Gecko profile does not contain a threads array")
        return GeckoProfileReadSummary(
            recordCount = 1 + threadCount + sampleCount,
            sampleCount = sampleCount,
            decompressedBytes = decompressed.bytesRead,
        )
    }

    private fun readThread(json: JsonStreamReader): GeckoThread {
        var pid: Int? = null
        var tid: Int? = null
        var name: String? = null
        var samples: GeckoTable? = null
        var frames: GeckoTable? = null
        var stacks: GeckoTable? = null
        var strings: List<String>? = null
        json.readObject { field ->
            when (field) {
                "pid" -> pid = json.readIntValue("thread.pid")
                "tid" -> tid = json.readIntValue("thread.tid")
                "name" -> name = json.readStringValue("thread.name")
                "samples" -> samples = readTable(json)
                "frameTable" -> frames = readTable(json)
                "stackTable" -> stacks = readTable(json)
                "stringTable" -> strings = readStringTable(json)
                else -> json.skipValue()
            }
        }
        val requiredPid = pid ?: throw GeckoProfileFormatException("Gecko thread is missing pid")
        val requiredTid = tid ?: throw GeckoProfileFormatException("Gecko thread is missing tid")
        return GeckoThread(
            pid = requiredPid,
            tid = requiredTid,
            name = name ?: "<unknown-thread:$requiredTid>",
            samples = decodeSamples(samples ?: missingTable("samples")),
            frames = decodeFrames(frames ?: missingTable("frameTable")),
            stacks = decodeStacks(stacks ?: missingTable("stackTable")),
            strings = strings ?: throw GeckoProfileFormatException("Gecko thread is missing stringTable"),
        ).validated()
    }

    private fun readTable(json: JsonStreamReader): GeckoTable {
        var schema: Map<String, Int>? = null
        var data: List<List<Any?>>? = null
        json.readObject { field ->
            when (field) {
                "schema" -> schema = json.readIntObject()
                "data" -> data = json.readPrimitiveRows()
                else -> json.skipValue()
            }
        }
        return GeckoTable(
            schema ?: throw GeckoProfileFormatException("Gecko table is missing schema"),
            data ?: throw GeckoProfileFormatException("Gecko table is missing data"),
        )
    }

    private fun readStringTable(json: JsonStreamReader): List<String> {
        val result = mutableListOf<String>()
        json.readArray { result += json.readStringValue("stringTable entry") }
        return result
    }

    private fun decodeSamples(table: GeckoTable): List<GeckoSample> {
        val stack = table.requiredIndex("stack")
        val time = table.requiredIndex("time")
        return table.rows.mapIndexed { index, row ->
            GeckoSample(
                stackId = row.valueAt(stack, "samples[$index].stack").nullableInt(),
                timeMillis = row.valueAt(time, "samples[$index].time").requiredDouble(),
            )
        }
    }

    private fun decodeFrames(table: GeckoTable): List<GeckoFrame> {
        val location = table.requiredIndex("location")
        val category = table.schema["category"]
        return table.rows.mapIndexed { index, row ->
            GeckoFrame(
                locationStringId = row.valueAt(location, "frameTable[$index].location").requiredInt(),
                categoryId = category?.let { row.valueAt(it, "frameTable[$index].category").nullableInt() },
            )
        }
    }

    private fun decodeStacks(table: GeckoTable): List<GeckoStack> {
        val prefix = table.requiredIndex("prefix")
        val frame = table.requiredIndex("frame")
        return table.rows.mapIndexed { index, row ->
            GeckoStack(
                prefixId = row.valueAt(prefix, "stackTable[$index].prefix").nullableInt(),
                frameId = row.valueAt(frame, "stackTable[$index].frame").requiredInt(),
            )
        }
    }

    private fun missingTable(name: String): Nothing = throw GeckoProfileFormatException("Gecko thread is missing $name")

    companion object {
        private const val GECKO_SAMPLE_EVENT = "samples"
        private const val DEFAULT_MAX_JSON_DEPTH = 64
        private const val DEFAULT_MAX_DECOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024
    }
}

private data class GeckoTable(
    val schema: Map<String, Int>,
    val rows: List<List<Any?>>,
) {
    fun requiredIndex(name: String): Int = schema[name] ?: throw GeckoProfileFormatException("Gecko table schema is missing $name")
}

private data class GeckoSample(
    val stackId: Int?,
    val timeMillis: Double,
)

private data class GeckoFrame(
    val locationStringId: Int,
    val categoryId: Int?,
)

private data class GeckoStack(
    val prefixId: Int?,
    val frameId: Int,
)

private data class GeckoThread(
    val pid: Int,
    val tid: Int,
    val name: String,
    val samples: List<GeckoSample>,
    val frames: List<GeckoFrame>,
    val stacks: List<GeckoStack>,
    val strings: List<String>,
) {
    fun validated(): GeckoThread {
        stacks.forEachIndexed { index, stack ->
            stack.prefixId?.let { prefix ->
                if (prefix !in stacks.indices || prefix >= index) {
                    throw GeckoProfileFormatException("Invalid stack prefix $prefix at stackTable[$index]")
                }
            }
            if (stack.frameId !in frames.indices) {
                throw GeckoProfileFormatException("Invalid frame ${stack.frameId} at stackTable[$index]")
            }
        }
        frames.forEachIndexed { index, frame ->
            if (frame.locationStringId !in strings.indices) {
                throw GeckoProfileFormatException(
                    "Invalid string ${frame.locationStringId} at frameTable[$index]",
                )
            }
        }
        samples.forEachIndexed { index, sample ->
            if (sample.stackId != null && sample.stackId !in stacks.indices) {
                throw GeckoProfileFormatException("Invalid stack ${sample.stackId} at samples[$index]")
            }
            if (!sample.timeMillis.isFinite()) {
                throw GeckoProfileFormatException("Invalid time at samples[$index]")
            }
        }
        return this
    }

    fun normalizedSample(
        sample: GeckoSample,
        ids: FrameIdentityRegistry,
    ): NormalizedSample {
        val leafToRoot = mutableListOf<ProfileFrame>()
        var stackId = sample.stackId
        while (stackId != null) {
            val stack = stacks[stackId]
            val frame = frames[stack.frameId]
            leafToRoot += ids.frame(strings[frame.locationStringId], frame.categoryId)
            stackId = stack.prefixId
        }
        val timestampNanos = (sample.timeMillis * NANOS_PER_MILLISECOND).roundToLong()
        return NormalizedSample(
            timestampNanos = timestampNanos,
            processId = pid,
            threadId = tid,
            threadName = name,
            eventType = GECKO_SAMPLE_EVENT,
            eventCount = 1,
            frames = leafToRoot,
            unwindError = null,
        )
    }

    companion object {
        private const val GECKO_SAMPLE_EVENT = "samples"
        private const val NANOS_PER_MILLISECOND = 1_000_000.0
    }
}

private class FrameIdentityRegistry {
    private val fileIds = linkedMapOf<String, Int>()
    private val symbolIds = linkedMapOf<Pair<String, String>, Int>()

    fun frame(
        location: String,
        categoryId: Int?,
    ): ProfileFrame {
        val parsed = parseLocation(location)
        val fileId = fileIds.getOrPut(parsed.filePath) { fileIds.size + 1 }
        val symbolId = symbolIds.getOrPut(parsed.filePath to parsed.symbolName) { symbolIds.size + 1 }
        return ProfileFrame(
            virtualAddress = 0,
            fileId = fileId,
            symbolId = symbolId,
            filePath = parsed.filePath,
            symbolName = parsed.symbolName,
            executionType = executionType(parsed.filePath, categoryId),
        )
    }

    private fun parseLocation(location: String): ParsedLocation {
        val marker = location.lastIndexOf(" (in ")
        if (marker <= 0 || !location.endsWith(')')) {
            return ParsedLocation(location.ifBlank { "<unknown-symbol>" }, "<gecko-profile>")
        }
        val symbol = location.substring(0, marker).ifBlank { "<unknown-symbol>" }
        val file = location.substring(marker + LOCATION_SEPARATOR_LENGTH, location.length - 1).ifBlank { "<unknown-file>" }
        return ParsedLocation(symbol, file)
    }

    private fun executionType(
        filePath: String,
        categoryId: Int?,
    ): ProfileExecutionType =
        when {
            "kallsyms" in filePath || filePath.endsWith(".ko") -> ProfileExecutionType.KERNEL
            filePath.endsWith(".vdex") -> ProfileExecutionType.INTERPRETED_JVM
            filePath.endsWith(".oat") -> ProfileExecutionType.ART
            "[JIT app cache]" in filePath -> ProfileExecutionType.JIT_JVM
            ".so" in filePath -> ProfileExecutionType.NATIVE
            categoryId == KERNEL_CATEGORY_ID -> ProfileExecutionType.KERNEL
            else -> ProfileExecutionType.UNKNOWN
        }

    private data class ParsedLocation(
        val symbolName: String,
        val filePath: String,
    )

    companion object {
        private const val LOCATION_SEPARATOR_LENGTH = 5
        private const val KERNEL_CATEGORY_ID = 1
    }
}

internal class GeckoProfileFormatException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

private class LimitedInputStream(
    input: InputStream,
    private val limit: Long,
) : FilterInputStream(input) {
    var bytesRead: Long = 0
        private set

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) account(1)
        return value
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) account(count.toLong())
        return count
    }

    private fun account(count: Long) {
        bytesRead += count
        if (bytesRead > limit) throw GeckoProfileFormatException("Gecko profile exceeds decompressed size limit")
    }
}

private class JsonStreamReader(
    private val reader: Reader,
    private val maxDepth: Int,
) {
    private var lookahead = EMPTY
    private var depth = 0

    fun readObject(onField: (String) -> Unit) {
        enter('{')
        if (peekNonWhitespace() == '}'.code) {
            readNonWhitespace()
            leave()
            return
        }
        while (true) {
            val name = readStringValue("object key")
            expect(':')
            onField(name)
            when (val delimiter = readNonWhitespace()) {
                ','.code -> Unit
                '}'.code -> {
                    leave()
                    return
                }
                else -> invalid("Expected ',' or '}', found ${delimiter.display()}")
            }
        }
    }

    fun readArray(onElement: () -> Unit) {
        enter('[')
        if (peekNonWhitespace() == ']'.code) {
            readNonWhitespace()
            leave()
            return
        }
        while (true) {
            onElement()
            when (val delimiter = readNonWhitespace()) {
                ','.code -> Unit
                ']'.code -> {
                    leave()
                    return
                }
                else -> invalid("Expected ',' or ']', found ${delimiter.display()}")
            }
        }
    }

    fun readIntObject(): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        readObject { name -> result[name] = readIntValue("schema.$name") }
        return result
    }

    fun readPrimitiveRows(): List<List<Any?>> {
        val result = mutableListOf<List<Any?>>()
        readArray {
            val row = mutableListOf<Any?>()
            readArray { row += readPrimitive() }
            result += row
        }
        return result
    }

    fun readStringValue(label: String): String = readPrimitive().asString() ?: invalid("Expected string for $label")

    fun readIntValue(label: String): Int = readPrimitive().requiredIntOrNull() ?: invalid("Expected integer for $label")

    fun skipValue() {
        when (peekNonWhitespace()) {
            '{'.code -> readObject { skipValue() }
            '['.code -> readArray { skipValue() }
            else -> readPrimitive()
        }
    }

    private fun readPrimitive(): Any? =
        when (val next = peekNonWhitespace()) {
            '"'.code -> readString()
            't'.code -> readLiteral("true", true)
            'f'.code -> readLiteral("false", false)
            'n'.code -> readLiteral("null", null)
            '-'.code, in '0'.code..'9'.code -> readNumber()
            else -> invalid("Expected JSON value, found ${next.display()}")
        }

    private fun readString(): String {
        expect('"')
        val result = StringBuilder()
        while (true) {
            val value = readRaw()
            when (value) {
                END -> invalid("Unterminated string")
                '"'.code -> return result.toString()
                '\\'.code -> result.append(readEscape())
                in 0 until ' '.code -> invalid("Control character in string")
                else -> result.append(value.toChar())
            }
        }
    }

    private fun readEscape(): Char =
        when (val escaped = readRaw()) {
            '"'.code -> '"'
            '\\'.code -> '\\'
            '/'.code -> '/'
            'b'.code -> '\b'
            'f'.code -> '\u000C'
            'n'.code -> '\n'
            'r'.code -> '\r'
            't'.code -> '\t'
            'u'.code -> readUnicodeEscape()
            else -> invalid("Invalid string escape ${escaped.display()}")
        }

    private fun readUnicodeEscape(): Char {
        var value = 0
        repeat(4) {
            val digit = Character.digit(readRaw(), 16)
            if (digit < 0) invalid("Invalid unicode escape")
            value = (value shl 4) or digit
        }
        return value.toChar()
    }

    private fun readNumber(): Number {
        val text = StringBuilder()
        while (true) {
            val value = peekRaw()
            if (value == END || value.toChar() !in NUMBER_CHARACTERS) break
            text.append(readRaw().toChar())
        }
        val raw = text.toString()
        if (raw.isEmpty()) invalid("Expected number")
        return if (raw.any { it == '.' || it == 'e' || it == 'E' }) {
            raw.toDoubleOrNull() ?: invalid("Invalid number $raw")
        } else {
            raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: invalid("Invalid number $raw")
        }
    }

    private fun <T> readLiteral(
        expected: String,
        result: T,
    ): T {
        expected.forEach { character ->
            if (readRaw() != character.code) invalid("Invalid JSON literal")
        }
        return result
    }

    private fun enter(expected: Char) {
        expect(expected)
        depth++
        if (depth > maxDepth) invalid("JSON nesting exceeds $maxDepth")
    }

    private fun leave() {
        depth--
    }

    private fun expect(expected: Char) {
        val actual = readNonWhitespace()
        if (actual != expected.code) invalid("Expected '$expected', found ${actual.display()}")
    }

    private fun peekNonWhitespace(): Int {
        var value = peekRaw()
        while (value != END && value.toChar().isWhitespace()) {
            readRaw()
            value = peekRaw()
        }
        return value
    }

    private fun readNonWhitespace(): Int {
        val value = peekNonWhitespace()
        if (value != END) readRaw()
        return value
    }

    private fun peekRaw(): Int {
        if (lookahead == EMPTY) lookahead = reader.read()
        return lookahead
    }

    private fun readRaw(): Int {
        val value = peekRaw()
        lookahead = EMPTY
        return value
    }

    private fun invalid(message: String): Nothing = throw GeckoProfileFormatException(message)

    private fun Int.display(): String = if (this == END) "end of input" else "'${toChar()}'"

    companion object {
        private const val EMPTY = -2
        private const val END = -1
        private const val NUMBER_CHARACTERS = "-+0123456789.eE"
    }
}

private fun List<Any?>.valueAt(
    index: Int,
    label: String,
): Any? = getOrNull(index) ?: if (index in indices) null else throw GeckoProfileFormatException("Missing $label")

private fun Any?.asString(): String? = this as? String

private fun Any?.requiredIntOrNull(): Int? =
    when (this) {
        is Int -> this
        is Long -> takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        is Double -> takeIf { it.isFinite() && it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt()
        else -> null
    }

private fun Any?.requiredInt(): Int = requiredIntOrNull() ?: throw GeckoProfileFormatException("Expected integer")

private fun Any?.nullableInt(): Int? = if (this == null) null else requiredInt()

private fun Any?.requiredDouble(): Double =
    when (this) {
        is Number -> toDouble()
        else -> throw GeckoProfileFormatException("Expected number")
    }
