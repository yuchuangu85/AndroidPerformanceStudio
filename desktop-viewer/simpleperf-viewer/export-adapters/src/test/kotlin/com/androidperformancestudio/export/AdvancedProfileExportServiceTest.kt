package com.androidperformancestudio.export

import com.androidperformancestudio.profileanalysis.CallStackFrame
import com.androidperformancestudio.profileanalysis.CallStackTable
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FrameImplementation
import com.androidperformancestudio.profileanalysis.WeightedCallStack
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdvancedProfileExportServiceTest {
    @Test
    fun `exports folded stacks and valid gzip pprof ids`() {
        val table =
            CallStackTable(
                framesById =
                    mapOf(
                        0L to CallStackFrame(0, FlameFunctionId(10), "root", "lib.so", 100, FrameImplementation.NATIVE),
                        1L to CallStackFrame(1, FlameFunctionId(11), "leaf", "lib.so", 200, FrameImplementation.NATIVE),
                    ),
                stacks = listOf(WeightedCallStack(1, 100, 7, "main", null, null, listOf(0L, 1L))),
            )
        val directory = Files.createTempDirectory("advanced-export")
        val folded = directory.resolve("profile.folded")
        val pprof = directory.resolve("profile.pprof.gz")

        FoldedStacksExportService().export(table, folded)
        PprofProfileExportService().export(table, pprof)

        assertContains(Files.readString(folded), "root;leaf 7")
        val compressed = Files.readAllBytes(pprof)
        assertEquals(0x1f, compressed[0].toInt() and 0xff)
        assertEquals(0x8b, compressed[1].toInt() and 0xff)
        val payload = GZIPInputStream(Files.newInputStream(pprof)).use { it.readAllBytes() }
        assertTrue(payload.size > 20)
        assertContains(payload.toString(Charsets.ISO_8859_1), "root")

        val profileFields = decodeFields(payload)
        val locationIds =
            profileFields
                .filter { it.number == 4 }
                .map { field -> decodeFields(field.bytes!!).first { it.number == 1 }.varint!! }
        val functionIds =
            profileFields
                .filter { it.number == 5 }
                .map { field -> decodeFields(field.bytes!!).first { it.number == 1 }.varint!! }
        val sampleLocationIds =
            profileFields
                .filter { it.number == 2 }
                .flatMap { field ->
                    decodeFields(field.bytes!!).filter { it.number == 1 }.map { it.varint!! }
                }

        assertEquals(listOf(1L, 2L), locationIds)
        assertEquals(listOf(1L, 2L), functionIds)
        assertEquals(setOf(1L, 2L), sampleLocationIds.toSet())
    }

    private fun decodeFields(payload: ByteArray): List<ProtoField> {
        val fields = mutableListOf<ProtoField>()
        var offset = 0
        while (offset < payload.size) {
            val (tag, afterTag) = readVarint(payload, offset)
            offset = afterTag
            val number = (tag ushr 3).toInt()
            when (val wireType = (tag and 0x7).toInt()) {
                0 -> {
                    val (value, next) = readVarint(payload, offset)
                    fields += ProtoField(number, wireType, varint = value)
                    offset = next
                }
                2 -> {
                    val (length, afterLength) = readVarint(payload, offset)
                    val end = afterLength + length.toInt()
                    fields += ProtoField(number, wireType, bytes = payload.copyOfRange(afterLength, end))
                    offset = end
                }
                else -> error("Unsupported protobuf wire type $wireType")
            }
        }
        return fields
    }

    private fun readVarint(
        payload: ByteArray,
        start: Int,
    ): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var offset = start
        while (offset < payload.size) {
            val byte = payload[offset].toInt() and 0xff
            result = result or ((byte and 0x7f).toLong() shl shift)
            offset += 1
            if (byte and 0x80 == 0) return result to offset
            shift += 7
            require(shift < 64) { "Invalid protobuf varint" }
        }
        error("Truncated protobuf varint")
    }

    private data class ProtoField(
        val number: Int,
        val wireType: Int,
        val varint: Long? = null,
        val bytes: ByteArray? = null,
    )
}
