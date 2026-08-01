package com.androidperformancestudio.perfetto.app

import com.androidperformancestudio.model.StudioResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TraceExportTest {
    @Test
    fun `exports the selected trace unchanged`() {
        val source = Files.createTempFile("trace-source", ".pftrace")
        val destination = Files.createTempFile("trace-export", ".pftrace")
        val expected = byteArrayOf(0x0a, 0x1b, 0x2c)
        try {
            Files.write(source, expected)

            val result = exportRawTraceFile(source, destination)

            assertEquals(StudioResult.Success(destination), result)
            assertContentEquals(expected, Files.readAllBytes(destination))
        } finally {
            Files.deleteIfExists(source)
            Files.deleteIfExists(destination)
        }
    }
}
