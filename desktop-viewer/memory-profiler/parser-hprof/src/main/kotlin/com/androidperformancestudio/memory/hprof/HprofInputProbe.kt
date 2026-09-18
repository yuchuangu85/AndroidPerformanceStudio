@file:Suppress("CyclomaticComplexMethod", "LongMethod", "MagicNumber", "ReturnCount", "LoopWithTooManyJumpStatements")

package com.androidperformancestudio.memory.hprof

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Performs a bounded structural inspection before the full HPROF parser runs.
 *
 * The probe deliberately does not parse heap objects. Its result is suitable for showing the
 * source format, ID width, file size, record inventory, and structural warnings before an
 * expensive graph analysis starts.
 */
public class HprofInputProbe(
    private val maxProbeBytes: Int = DEFAULT_MAX_PROBE_BYTES,
) {
    public fun inspect(path: Path): HprofInputProbeResult {
        require(Files.isRegularFile(path)) { "HPROF input is not a regular file: $path" }
        val fileSizeBytes = Files.size(path)
        val sampleSize = minOf(fileSizeBytes, maxProbeBytes.toLong()).toInt()
        val sample = ByteBuffer.allocate(sampleSize)
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            while (sample.hasRemaining() && channel.read(sample) >= 0) {
                // FileChannel may return a short read; continue until the bounded sample is full.
            }
        }
        sample.flip()
        return inspect(sample, fileSizeBytes, sampleSize.toLong())
    }

    public fun inspect(bytes: ByteArray): HprofInputProbeResult =
        inspect(
            ByteBuffer.wrap(bytes),
            bytes.size.toLong(),
            bytes.size.toLong(),
        )

    private fun inspect(
        buffer: ByteBuffer,
        fileSizeBytes: Long,
        inspectedBytes: Long,
    ): HprofInputProbeResult {
        val warnings = mutableListOf<String>()
        val header = readHeader(buffer, warnings)
        val idSize = buffer.takeIf { it.remaining() >= Int.SIZE_BYTES }?.int
        if (idSize != null && idSize !in SUPPORTED_ID_SIZES) {
            warnings += "Unsupported HPROF id size $idSize"
        }
        val timestampMillis = buffer.takeIf { it.remaining() >= Long.SIZE_BYTES }?.long
        if (header == null) {
            warnings += "HPROF header is missing or incomplete"
        } else if (!header.startsWith(HPROF_HEADER_PREFIX)) {
            warnings += "Unsupported HPROF header $header"
        }
        if (idSize == null || timestampMillis == null) {
            warnings += "HPROF header metadata is incomplete"
        }

        val recordTags = mutableListOf<Int>()
        var hasHeapDump = false
        var hasHeapDumpSegment = false
        var recordCount = 0
        while (buffer.remaining() >= RECORD_HEADER_BYTES) {
            val recordOffset = buffer.position().toLong()
            val tag = buffer.get().toInt() and 0xff
            buffer.int
            val length = buffer.int
            if (length < 0) {
                warnings += "Negative HPROF record length at offset $recordOffset"
                break
            }
            if (length > buffer.remaining()) {
                warnings += "Truncated HPROF record at offset $recordOffset: expected $length bytes"
                break
            }
            recordCount++
            if (recordTags.size < MAX_RECORDED_TAGS) recordTags += tag
            when (tag) {
                HEAP_DUMP -> hasHeapDump = true
                HEAP_DUMP_SEGMENT -> hasHeapDumpSegment = true
            }
            buffer.position(buffer.position() + length)
        }
        if (buffer.hasRemaining() && buffer.remaining() < RECORD_HEADER_BYTES && fileSizeBytes <= maxProbeBytes) {
            warnings += "Trailing incomplete HPROF record header"
        }
        val inspectionComplete = inspectedBytes >= fileSizeBytes
        if (fileSizeBytes > RECOMMENDED_MATERIALIZED_BYTES) {
            warnings +=
                "HPROF exceeds the recommended in-memory analysis budget; " +
                "indexed/out-of-core analysis is required for predictable resource use"
        }
        if (inspectionComplete && !hasHeapDump && !hasHeapDumpSegment) {
            warnings += "No heap dump record was found in the inspected HPROF records"
        }

        return HprofInputProbeResult(
            fileSizeBytes = fileSizeBytes,
            inspectedBytes = inspectedBytes,
            inspectionComplete = inspectionComplete,
            format = header,
            idSize = idSize,
            timestampMillis = timestampMillis,
            recordCount = recordCount,
            recordTags = recordTags,
            hasHeapDump = hasHeapDump,
            hasHeapDumpSegment = hasHeapDumpSegment,
            sourceHint = sourceHint(header, hasHeapDump, hasHeapDumpSegment),
            warnings = warnings.distinct(),
        )
    }

    private fun readHeader(
        buffer: ByteBuffer,
        warnings: MutableList<String>,
    ): String? {
        val bytes = ByteArrayOutputStream()
        while (buffer.hasRemaining()) {
            val value = buffer.get().toInt() and 0xff
            if (value == 0) return bytes.toByteArray().decodeToString()
            if (bytes.size() >= MAX_HEADER_BYTES) {
                warnings += "HPROF header exceeds $MAX_HEADER_BYTES bytes"
                return null
            }
            bytes.write(value)
        }
        return null
    }

    private fun sourceHint(
        header: String?,
        hasHeapDump: Boolean,
        hasHeapDumpSegment: Boolean,
    ): HprofSourceHint =
        when {
            header?.startsWith(HPROF_HEADER_PREFIX) != true -> HprofSourceHint.UNKNOWN
            hasHeapDump || hasHeapDumpSegment -> HprofSourceHint.ANDROID_OR_JAVA_SE_COMPATIBLE
            else -> HprofSourceHint.UNKNOWN
        }

    public companion object {
        public const val DEFAULT_MAX_PROBE_BYTES: Int = 64 * 1024
        public const val RECOMMENDED_MATERIALIZED_BYTES: Long = 512L * 1024L * 1024L
        private const val MAX_HEADER_BYTES = 256
        private const val MAX_RECORDED_TAGS = 32
        private const val RECORD_HEADER_BYTES = 1 + Int.SIZE_BYTES + Int.SIZE_BYTES
        private const val HPROF_HEADER_PREFIX = "JAVA PROFILE "
        private const val HEAP_DUMP = 0x0c
        private const val HEAP_DUMP_SEGMENT = 0x1c
        private val SUPPORTED_ID_SIZES = setOf(4, 8)
    }
}

public data class HprofInputProbeResult(
    val fileSizeBytes: Long,
    val inspectedBytes: Long,
    val inspectionComplete: Boolean,
    val format: String?,
    val idSize: Int?,
    val timestampMillis: Long?,
    val recordCount: Int,
    val recordTags: List<Int>,
    val hasHeapDump: Boolean,
    val hasHeapDumpSegment: Boolean,
    val sourceHint: HprofSourceHint,
    val warnings: List<String>,
) {
    public val isStructurallyRecognized: Boolean
        get() = format?.startsWith("JAVA PROFILE ") == true && idSize in setOf(4, 8)
}

public enum class HprofSourceHint {
    ANDROID_OR_JAVA_SE_COMPATIBLE,
    UNKNOWN,
}
