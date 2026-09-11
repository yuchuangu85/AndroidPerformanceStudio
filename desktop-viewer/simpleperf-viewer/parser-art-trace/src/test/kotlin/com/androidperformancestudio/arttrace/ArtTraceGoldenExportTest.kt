package com.androidperformancestudio.arttrace

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Exports a golden corpus for the TypeScript rewrite of the ART method-trace
 * parser.
 *
 * Both on-disk layouts are covered: the streaming versions 4 and 5 that modern
 * Android writes, and the classic version 2 with its text method and thread
 * tables. Each case is written as the raw bytes plus a JSON digest of what this
 * parser saw, and the TypeScript side must reproduce every field.
 *
 * Output directory: APS_GOLDEN_OUT, or the module build/golden.
 */
class ArtTraceGoldenExportTest {
    // Case names are not unique across parsers, so each exporter owns a directory.
    private val outputDirectory: File =
        File(System.getenv("APS_GOLDEN_OUT") ?: "build/golden", "art-trace").apply { mkdirs() }

    @Test
    fun `exports golden cases with their parse digests`() {
        val cases = listOf(streaming("streaming-dual", version = 5), streaming("streaming-single", version = 4), classic())
        assertEquals(3, cases.size)
        cases.forEach { case -> write(case) }
    }

    private data class GoldenCase(
        val name: String,
        val bytes: ByteArray,
    )

    /** One thread, two methods, one enter and one exit on the same method. */
    private fun streaming(name: String, version: Int): GoldenCase {
        val dual = version == 5
        val trace = TraceWriter()
        trace.u32(TRACE_MAGIC).u16(version).u64(1_000_000L)
        repeat(18) { trace.u8(0) }
        trace.u8(0).u32(7).u16(4).string("main")
        trace.bytes(methodInfoPacket(1L, "Lcom/example/Foo\tbar\t()V\tFoo.java"))
        trace.bytes(methodInfoPacket(2L, "Lcom/example/Baz\tqux\t()V\tBaz.java"))
        val entries = TraceWriter()
        entries.sleb(4L).uleb(100L)
        if (dual) entries.uleb(10L)
        entries.sleb(1L).uleb(50L)
        if (dual) entries.uleb(20L)
        val payload = entries.build()
        trace.u8(2).u32(7).u24(2).u32(payload.size.toLong()).bytes(payload)
        trace.u8(3).u16(0)
        return GoldenCase(name, trace.build())
    }

    /** The declared length must be the UTF-8 byte count, not the character count. */
    private fun methodInfoPacket(id: Long, info: String): ByteArray {
        val encoded = info.toByteArray(Charsets.UTF_8)
        return TraceWriter().u8(1).u64(id).u16(encoded.size).bytes(encoded).build()
    }

    /** Classic layout: 32 byte header, text tables, fixed size records. */
    private fun classic(): GoldenCase {
        val trace = TraceWriter()
        trace.u32(TRACE_MAGIC).u16(2).u16(32).u64(1_234L)
        repeat(16) { trace.u8(0) }
        trace.string("*threads\n7\tmain\n*methods\n4\tLcom/example/Foo\tbar\t()V\tFoo.java\n*end\n")
        trace.u16(7).u32(1L shl 2).u32(100L)
        trace.u16(7).u32((1L shl 2) or 1L).u32(300L)
        return GoldenCase("classic", trace.build())
    }

    private fun write(case: GoldenCase) {
        val inputFile = case.name + ".trace"
        File(outputDirectory, inputFile).writeBytes(case.bytes)
        File(outputDirectory, case.name + ".json").writeText(digest(case, inputFile))
    }

    private fun digest(case: GoldenCase, inputFile: String): String {
        val parsed = assertIs<ArtTraceParseResult.Success>(ArtTraceParser.parse(case.bytes))
        val analysis = parsed.analysis
        val methodNames =
            analysis.methods.values
                .map { method ->
                    val dotted = method.className.replace('/', '.').removeSuffix(";")
                    if (method.methodName.isBlank()) dotted else dotted + "." + method.methodName
                }.filter { name -> name.isNotBlank() }
                .sorted()
        val threadNames = analysis.threads.values.map { thread -> thread.name + " (tid " + thread.threadId + ")" }.sorted()
        val builder = StringBuilder()
        builder.append("{\n")
        builder.append("  \"parser\": \"ART_TRACE\",\n")
        builder.append("  \"case\": \"").append(case.name).append("\",\n")
        builder.append("  \"inputFile\": \"").append(inputFile).append("\",\n")
        builder.append("  \"inputSha256\": \"").append(sha256(case.bytes)).append("\",\n")
        builder.append("  \"expectations\": {\n")
        builder.append("    \"version\": ").append(analysis.header.version).append(",\n")
        builder.append("    \"clockSource\": \"").append(analysis.header.clockSource.name).append("\",\n")
        builder.append("    \"startTimeNanos\": \"").append(analysis.startTimeNanos).append("\",\n")
        builder.append("    \"endTimeNanos\": \"").append(analysis.endTimeNanos).append("\",\n")
        builder.append("    \"eventCount\": ").append(analysis.events.size).append(",\n")
        builder.append("    \"enterCount\": ").append(analysis.events.count { it.action == ArtTraceAction.ENTER }).append(",\n")
        builder.append("    \"exitCount\": ").append(analysis.events.count { it.action == ArtTraceAction.EXIT }).append(",\n")
        builder.append("    \"unrollCount\": ").append(analysis.events.count { it.action == ArtTraceAction.UNROLL }).append(",\n")
        builder.append("    \"methodNames\": ").append(stringArray(methodNames)).append(",\n")
        builder.append("    \"threadNames\": ").append(stringArray(threadNames)).append(",\n")
        builder.append("    \"warningCount\": ").append(analysis.warnings.size).append("\n")
        builder.append("  }\n")
        builder.append("}\n")
        return builder.toString()
    }

    private fun stringArray(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { value -> "\"" + value + "\"" }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private class TraceWriter {
        private val out = ByteArrayOutputStream()

        fun u8(value: Int): TraceWriter = apply { out.write(value and 0xff) }

        fun u16(value: Int): TraceWriter =
            apply {
                out.write(value and 0xff)
                out.write((value ushr 8) and 0xff)
            }

        fun u24(value: Int): TraceWriter =
            apply {
                out.write(value and 0xff)
                out.write((value ushr 8) and 0xff)
                out.write((value ushr 16) and 0xff)
            }

        fun u32(value: Long): TraceWriter =
            apply {
                repeat(4) { index -> out.write(((value ushr (8 * index)) and 0xff).toInt()) }
            }

        fun u64(value: Long): TraceWriter =
            apply {
                repeat(8) { index -> out.write(((value ushr (8 * index)) and 0xff).toInt()) }
            }

        fun uleb(value: Long): TraceWriter =
            apply {
                var remaining = value
                while (true) {
                    val byte = (remaining and 0x7f).toInt()
                    remaining = remaining ushr 7
                    if (remaining == 0L) {
                        out.write(byte)
                        break
                    }
                    out.write(byte or 0x80)
                }
            }

        fun sleb(value: Long): TraceWriter =
            apply {
                var remaining = value
                while (true) {
                    var byte = (remaining and 0x7f).toInt()
                    remaining = remaining shr 7
                    val signBitSet = byte and 0x40 != 0
                    if ((remaining == 0L && !signBitSet) || (remaining == -1L && signBitSet)) {
                        out.write(byte)
                        break
                    }
                    byte = byte or 0x80
                    out.write(byte)
                }
            }

        fun string(value: String): TraceWriter = apply { out.write(value.toByteArray(Charsets.UTF_8)) }

        fun bytes(value: ByteArray): TraceWriter = apply { out.write(value) }

        fun build(): ByteArray = out.toByteArray()
    }

    private companion object {
        const val TRACE_MAGIC = 0x574f4c53L
    }
}
