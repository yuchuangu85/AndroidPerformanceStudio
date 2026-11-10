package com.androidperformancestudio.memory.hprof

import com.androidperformancestudio.memory.model.PrimitiveType
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HprofInputProbeTest {
    private val probe = HprofInputProbe()

    @Test
    fun `reads bounded metadata and record inventory for four byte ids`() {
        val result =
            probe.inspect(
                HprofFixtureBuilder()
                    .string(1, "Sample")
                    .loadClass(2, 1)
                    .heapDump(HprofFixtureBuilder().classDump(2, 24))
                    .build(),
            )

        assertEquals(4, result.idSize)
        assertEquals("JAVA PROFILE 1.0.3", result.format)
        assertEquals(1234L, result.timestampMillis)
        assertEquals(3, result.recordCount)
        assertEquals(listOf(0x01, 0x02, 0x0c), result.recordTags)
        assertTrue(result.hasHeapDump)
        assertFalse(result.hasHeapDumpSegment)
        assertEquals(HprofSourceHint.ANDROID_OR_JAVA_SE_COMPATIBLE, result.sourceHint)
        assertTrue(result.isStructurallyRecognized)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `recognizes eight byte ids and segmented heap dumps`() {
        val result =
            probe.inspect(
                HprofFixtureBuilder(idSize = 8)
                    .string(1, "Sample")
                    .loadClass(2, 1)
                    .heapDumpSegment(HprofFixtureBuilder().primitiveArrayDump(3, PrimitiveType.BYTE, 2))
                    .build(),
            )

        assertEquals(8, result.idSize)
        assertFalse(result.hasHeapDump)
        assertTrue(result.hasHeapDumpSegment)
        assertTrue(result.isStructurallyRecognized)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `reports malformed header and incomplete record without throwing`() {
        val malformed =
            "NOT HPROF".encodeToByteArray() +
                byteArrayOf(0) +
                byteArrayOf(0, 0, 0, 4) +
                byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0) +
                byteArrayOf(0x0c, 0, 0, 0, 0, 0, 0, 0, 16)

        val result = probe.inspect(malformed)

        assertFalse(result.isStructurallyRecognized)
        assertTrue(result.warnings.any { it.contains("Unsupported HPROF header") })
        assertTrue(result.warnings.any { it.contains("Truncated HPROF record") })
    }

    @Test
    fun `warns when the input exceeds the materialized analysis budget`() {
        val file = Files.createTempFile("hprof-budget", ".hprof")
        try {
            RandomAccessFile(file.toFile(), "rw").use { handle ->
                handle.setLength(HprofInputProbe.RECOMMENDED_MATERIALIZED_BYTES + 1L)
            }
            val result = probe.inspect(file)
            assertTrue(result.warnings.any { it.contains("out-of-core") })
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `reports bounded inspection for large inputs`() {
        val fixture =
            HprofFixtureBuilder()
                .string(1, "Sample")
                .loadClass(2, 1)
                .heapDump(HprofFixtureBuilder().classDump(2, 24))
                .build()
        val input = fixture + ByteArray(HprofInputProbe.DEFAULT_MAX_PROBE_BYTES)

        val file = Files.createTempFile("hprof-probe", ".hprof")
        try {
            Files.write(file, input)
            val result = probe.inspect(file)

            assertTrue(result.inspectedBytes < result.fileSizeBytes)
            assertFalse(result.inspectionComplete)
            assertTrue(result.warnings.isEmpty())
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
