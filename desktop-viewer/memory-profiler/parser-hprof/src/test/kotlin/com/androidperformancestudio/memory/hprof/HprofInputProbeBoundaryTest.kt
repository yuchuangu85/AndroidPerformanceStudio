package com.androidperformancestudio.memory.hprof

import com.androidperformancestudio.memory.model.PrimitiveType
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HprofInputProbeBoundaryTest {
    private val probe = HprofInputProbe()

    @Test
    fun `valid twenty byte record at offset 65515 is not truncated by the probe limit`() {
        val input = crossingRecordFixture(65515)
        assertEquals(20, ByteBuffer.wrap(input).getInt(65515 + 5))

        val result = inspectFile(input)

        assertEquals(HprofInputProbe.DEFAULT_MAX_PROBE_BYTES.toLong(), result.inspectedBytes)
        assertFalse(result.inspectionComplete)
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
        assertEquals(listOf(0x01, 0x01), result.recordTags)
    }

    @Test
    fun `large heap record is recognized when its payload extends beyond the sample`() {
        val records = HprofFixtureBuilder()
        val input =
            records
                .heapDump(
                    records.primitiveArrayDump(3, PrimitiveType.BYTE, HprofInputProbe.DEFAULT_MAX_PROBE_BYTES),
                ).build()

        val result = inspectFile(input)

        assertFalse(result.inspectionComplete)
        assertTrue(result.hasHeapDump)
        assertEquals(1, result.recordCount)
        assertEquals(HprofSourceHint.ANDROID_OR_JAVA_SE_COMPATIBLE, result.sourceHint)
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
    }

    @Test
    fun `record header crossing the sample boundary is not an incomplete file header`() {
        val result = inspectFile(crossingRecordFixture(HprofInputProbe.DEFAULT_MAX_PROBE_BYTES - 4))

        assertFalse(result.inspectionComplete)
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
    }

    @Test
    fun `genuine truncation beyond the sampled prefix still produces the record warning`() {
        val result = inspectFile(crossingRecordFixture(65515).copyOf(65540))

        assertFalse(result.inspectionComplete)
        assertTrue(result.warnings.contains("Truncated HPROF record at offset 65515: expected 20 bytes"))
        assertFalse(result.warnings.contains("Trailing incomplete HPROF record header"))
    }

    @Test
    fun `fully inspected byte arrays report a trailing incomplete header even above the file probe limit`() {
        val input = crossingRecordFixture(65515) + byteArrayOf(0x01, 0)

        val result = probe.inspect(input)

        assertTrue(result.inspectionComplete)
        assertEquals(input.size.toLong(), result.inspectedBytes)
        assertTrue(result.warnings.contains("Trailing incomplete HPROF record header"))
    }

    private fun crossingRecordFixture(recordOffset: Int): ByteArray {
        val headerBytes = HprofFixtureBuilder().build().size
        val recordHeaderBytes = 1 + Int.SIZE_BYTES + Int.SIZE_BYTES
        val paddingLength = recordOffset - headerBytes - recordHeaderBytes - Int.SIZE_BYTES
        return HprofFixtureBuilder()
            .string(1, "x".repeat(paddingLength))
            .string(2, "0123456789abcdef")
            .heapDump(HprofFixtureBuilder().classDump(3, 24))
            .build()
    }

    private fun inspectFile(input: ByteArray): HprofInputProbeResult {
        val file = Files.createTempFile("hprof-probe-boundary-", ".hprof")
        return try {
            Files.write(file, input)
            probe.inspect(file)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
