package com.androidperformancestudio.parser

import com.android.tools.profiler.proto.SimpleperfReport
import com.androidperformancestudio.model.StudioResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SimpleperfRecordReaderTest {
    @Test
    fun `streams framed records with index and byte offsets`() {
        val thread =
            SimpleperfReport.Record
                .newBuilder()
                .setThread(
                    SimpleperfReport.Thread
                        .newBuilder()
                        .setProcessId(100)
                        .setThreadId(101)
                        .setThreadName("RenderThread"),
                ).build()
        val lost =
            SimpleperfReport.Record
                .newBuilder()
                .setLost(
                    SimpleperfReport.LostSituation
                        .newBuilder()
                        .setSampleCount(200)
                        .setLostCount(3),
                ).build()
        val records = mutableListOf<SimpleperfRecordEnvelope>()

        val result =
            SimpleperfRecordReader().read(ByteArrayInputStream(framed(thread, lost))) { envelope ->
                records += envelope
            }

        val summary = assertIs<StudioResult.Success<SimpleperfReadSummary>>(result).value
        assertEquals(2L, summary.recordCount)
        assertEquals(1, summary.version)
        assertEquals(listOf(0L, 1L), records.map(SimpleperfRecordEnvelope::index))
        assertEquals(16L, records.first().byteOffset)
        assertEquals(
            "RenderThread",
            records
                .first()
                .record.thread.threadName,
        )
        assertEquals(
            3L,
            records
                .last()
                .record.lost.lostCount,
        )
    }

    @Test
    fun `rejects invalid magic and unsupported version`() {
        val invalidMagic = framed().also { it[0] = 'X'.code.toByte() }
        val invalidVersion = framed().also { it[10] = 2 }

        val magicFailure = readFailure(SimpleperfRecordReader(), invalidMagic)
        val versionFailure = readFailure(SimpleperfRecordReader(), invalidVersion)

        assertEquals("SIMPLEPERF_MAGIC_INVALID", magicFailure.error.code)
        assertEquals("SIMPLEPERF_VERSION_UNSUPPORTED", versionFailure.error.code)
    }

    @Test
    fun `rejects oversized and truncated records before unbounded allocation`() {
        val oversized =
            ByteArrayOutputStream().apply {
                write("SIMPLEPERF".encodeToByteArray())
                writeLittleEndian16(1)
                writeLittleEndian32(1025)
            }
        val truncated =
            ByteArrayOutputStream().apply {
                write("SIMPLEPERF".encodeToByteArray())
                writeLittleEndian16(1)
                writeLittleEndian32(8)
                write(byteArrayOf(1, 2))
            }
        val reader = SimpleperfRecordReader(maxRecordBytes = 1024)

        val oversizedFailure = readFailure(reader, oversized.toByteArray())
        val truncatedFailure = readFailure(reader, truncated.toByteArray())

        assertEquals("SIMPLEPERF_RECORD_TOO_LARGE", oversizedFailure.error.code)
        assertEquals("SIMPLEPERF_RECORD_TRUNCATED", truncatedFailure.error.code)
        assertTrue(oversizedFailure.error.message.contains("offset 12"))
        assertTrue(truncatedFailure.error.message.contains("record 0"))
    }

    private fun framed(vararg records: SimpleperfReport.Record): ByteArray =
        ByteArrayOutputStream()
            .apply {
                write("SIMPLEPERF".encodeToByteArray())
                writeLittleEndian16(1)
                records.forEach { record ->
                    val bytes = record.toByteArray()
                    writeLittleEndian32(bytes.size)
                    write(bytes)
                }
                writeLittleEndian32(0)
            }.toByteArray()

    private fun readFailure(
        reader: SimpleperfRecordReader,
        bytes: ByteArray,
    ): StudioResult.Failure = assertIs<StudioResult.Failure>(reader.read(ByteArrayInputStream(bytes)))

    private fun ByteArrayOutputStream.writeLittleEndian16(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
    }

    private fun ByteArrayOutputStream.writeLittleEndian32(value: Int) {
        repeat(Int.SIZE_BYTES) { shift -> write(value ushr (shift * Byte.SIZE_BITS) and 0xff) }
    }
}
