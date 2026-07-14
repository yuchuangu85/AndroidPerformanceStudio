package com.androidperformancestudio.parser

import com.android.tools.profiler.proto.SimpleperfReport
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.google.protobuf.InvalidProtocolBufferException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

data class SimpleperfRecordEnvelope(
    val index: Long,
    val byteOffset: Long,
    val encodedSize: Int,
    val record: SimpleperfReport.Record,
)

data class SimpleperfReadSummary(
    val version: Int,
    val recordCount: Long,
    val bytesRead: Long,
)

class SimpleperfRecordReader(
    private val maxRecordBytes: Int = DEFAULT_MAX_RECORD_BYTES,
) {
    init {
        require(maxRecordBytes > 0) { "maxRecordBytes must be positive" }
    }

    fun read(
        input: InputStream,
        onRecord: (SimpleperfRecordEnvelope) -> Unit = {},
    ): StudioResult<SimpleperfReadSummary> =
        try {
            StudioResult.Success(parse(CountingInputStream(input), onRecord))
        } catch (exception: SimpleperfFormatException) {
            StudioResult.Failure(exception.error)
        } catch (exception: IOException) {
            StudioResult.Failure(
                StudioError(
                    ErrorCategory.IO,
                    "SIMPLEPERF_STREAM_READ_FAILED",
                    "Failed to read Simpleperf protobuf stream",
                    exception,
                ),
            )
        }

    private fun parse(
        input: CountingInputStream,
        onRecord: (SimpleperfRecordEnvelope) -> Unit,
    ): SimpleperfReadSummary {
        validateMagic(input)
        val version = readVersion(input)
        var recordIndex = 0L
        while (true) {
            val lengthOffset = input.bytesRead
            val encodedSize = readLittleEndian32(input, recordIndex, lengthOffset)
            if (encodedSize == 0L) break
            validateRecordSize(encodedSize, recordIndex, lengthOffset)
            val byteOffset = input.bytesRead
            val payload = readPayload(input, encodedSize.toInt(), recordIndex, byteOffset)
            val record = parseRecord(payload, recordIndex, byteOffset)
            onRecord(SimpleperfRecordEnvelope(recordIndex, byteOffset, payload.size, record))
            recordIndex++
        }
        return SimpleperfReadSummary(version, recordIndex, input.bytesRead)
    }

    private fun validateMagic(input: CountingInputStream) {
        val actual = input.readNBytes(MAGIC.size)
        if (!actual.contentEquals(MAGIC)) {
            formatFailure(
                "SIMPLEPERF_MAGIC_INVALID",
                "Invalid SIMPLEPERF magic at offset 0",
            )
        }
    }

    private fun readVersion(input: CountingInputStream): Int {
        val low = input.read()
        val high = input.read()
        if (low < 0 || high < 0) {
            formatFailure("SIMPLEPERF_VERSION_TRUNCATED", "Missing format version at offset ${MAGIC.size}")
        }
        val version = low or (high shl Byte.SIZE_BITS)
        if (version != SUPPORTED_VERSION) {
            formatFailure(
                "SIMPLEPERF_VERSION_UNSUPPORTED",
                "Unsupported SIMPLEPERF version $version at offset ${MAGIC.size}",
            )
        }
        return version
    }

    private fun readLittleEndian32(
        input: CountingInputStream,
        recordIndex: Long,
        offset: Long,
    ): Long {
        val bytes = input.readNBytes(Int.SIZE_BYTES)
        if (bytes.size != Int.SIZE_BYTES) {
            formatFailure(
                "SIMPLEPERF_LENGTH_TRUNCATED",
                "Truncated length for record $recordIndex at offset $offset",
            )
        }
        return bytes.foldIndexed(0L) { index, value, byte ->
            value or ((byte.toLong() and UNSIGNED_BYTE_MASK) shl (index * Byte.SIZE_BITS))
        }
    }

    private fun validateRecordSize(
        encodedSize: Long,
        recordIndex: Long,
        offset: Long,
    ) {
        if (encodedSize > maxRecordBytes.toLong()) {
            formatFailure(
                "SIMPLEPERF_RECORD_TOO_LARGE",
                "Record $recordIndex has $encodedSize bytes at offset $offset; limit is $maxRecordBytes",
            )
        }
    }

    private fun readPayload(
        input: CountingInputStream,
        encodedSize: Int,
        recordIndex: Long,
        offset: Long,
    ): ByteArray {
        val payload = input.readNBytes(encodedSize)
        if (payload.size != encodedSize) {
            formatFailure(
                "SIMPLEPERF_RECORD_TRUNCATED",
                "Truncated payload for record $recordIndex at offset $offset: " +
                    "expected $encodedSize, got ${payload.size}",
            )
        }
        return payload
    }

    private fun parseRecord(
        payload: ByteArray,
        recordIndex: Long,
        offset: Long,
    ): SimpleperfReport.Record =
        try {
            SimpleperfReport.Record.parseFrom(payload)
        } catch (exception: InvalidProtocolBufferException) {
            formatFailure(
                "SIMPLEPERF_RECORD_INVALID",
                "Invalid protobuf for record $recordIndex at offset $offset",
                exception,
            )
        }

    companion object {
        const val DEFAULT_MAX_RECORD_BYTES: Int = 64 * 1024 * 1024
        private const val SUPPORTED_VERSION = 1
        private const val UNSIGNED_BYTE_MASK = 0xffL
        private val MAGIC = "SIMPLEPERF".encodeToByteArray()
    }
}

private class CountingInputStream(
    input: InputStream,
) : FilterInputStream(input) {
    var bytesRead: Long = 0L
        private set

    override fun read(): Int =
        super.read().also { value ->
            if (value >= 0) bytesRead++
        }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        super.read(buffer, offset, length).also { count ->
            if (count > 0) bytesRead += count
        }
}

private class SimpleperfFormatException(
    val error: StudioError,
) : IOException(error.message, error.cause)

private fun formatFailure(
    code: String,
    message: String,
    cause: Throwable? = null,
): Nothing =
    throw SimpleperfFormatException(
        StudioError(ErrorCategory.DATA_VALIDATION, code, message, cause),
    )
