@file:Suppress("ComplexCondition", "MaxLineLength")

package com.androidperformancestudio.arttrace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Byte-level fixtures for the two ART trace layouts, built by hand from the AOSP
 * `art/runtime/trace.cc` writer. Kept independent of any reference tool so the parser is verified
 * against the exact documented layout.
 */
class ArtTraceParserTest {
    @Test
    fun `parses streaming v4 single-clock entry blocks`() {
        val trace =
            streamingTrace(
                version = 4,
                packets = {
                    threadPacket(1, "main")
                    methodPacket(1, "android/os/Looper\tloop\t()V\tLooper.java\n")
                    entryBlock(
                        threadId = 1,
                        entries =
                            listOf(
                                EntryDelta(method = 4, time = 1_000), // index 1 ENTER
                                EntryDelta(method = 5, time = 1_000), // delta -> index 2 EXIT
                            ),
                        dual = false,
                    )
                },
            )

        val result = ArtTraceParser.parse(trace)
        val analysis = assertIs<ArtTraceParseResult.Success>(result).analysis
        assertEquals(4, analysis.header.version)
        assertEquals(1_000_000_000L, analysis.header.startTimeNanos)

        val looper = analysis.methods.getValue(1)
        assertEquals("android/os/Looper", looper.className)
        assertEquals("loop", looper.methodName)
        assertEquals("main", analysis.threads.getValue(1).name)

        assertEquals(2, analysis.events.size)
        assertEquals(
            ArtTraceEvent(threadId = 1, methodId = 1, action = ArtTraceAction.ENTER, timeNanos = 1_000),
            analysis.events[0],
        )
        assertEquals(
            ArtTraceEvent(threadId = 1, methodId = 2, action = ArtTraceAction.EXIT, timeNanos = 2_000),
            analysis.events[1],
        )
    }

    @Test
    fun `parses streaming v5 dual-clock with cpu times`() {
        val trace =
            streamingTrace(
                version = 5,
                packets = {
                    threadPacket(2, "binder")
                    methodPacket(2, "android/os/Binder\ttransact\t(IJ)V\tBinder.java\n")
                    entryBlock(
                        threadId = 2,
                        entries =
                            listOf(
                                EntryDelta(method = 8, time = 100, cpu = 50),
                                EntryDelta(method = 9, time = 300, cpu = 120),
                            ),
                        dual = true,
                    )
                },
            )

        val analysis = assertIs<ArtTraceParseResult.Success>(ArtTraceParser.parse(trace)).analysis
        assertEquals(2, analysis.events.size)
        assertEquals(100L, analysis.events[0].timeNanos)
        assertEquals(50L, analysis.events[0].cpuNanos)
        assertEquals(400L, analysis.events[1].timeNanos)
        assertEquals(170L, analysis.events[1].cpuNanos)
    }

    @Test
    fun `parses classic v3 dual-clock records and text tables`() {
        val trace =
            classicTrace(
                version = 3,
                text =
                    "*threads\n" +
                        "\t1\tmain\n" +
                        "*methods\n" +
                        "\t8\tandroid/os/Looper\tloop\t()V\tLooper.java\n" +
                        "*end\n",
                records =
                    listOf(
                        ClassicRecord(thread = 1, methodValue = 8, cpuMicros = 1_000, wallMicros = 2_000),
                        ClassicRecord(thread = 1, methodValue = 9, cpuMicros = 2_000, wallMicros = 4_000),
                    ),
            )

        val analysis = assertIs<ArtTraceParseResult.Success>(ArtTraceParser.parse(trace)).analysis
        assertEquals(3, analysis.header.version)
        assertEquals("main", analysis.threads.getValue(1).name)
        assertEquals("android/os/Looper", analysis.methods.getValue(2).className)

        assertEquals(2, analysis.events.size)
        assertEquals(
            ArtTraceEvent(
                threadId = 1,
                methodId = 2,
                action = ArtTraceAction.ENTER,
                timeNanos = 2_000_000L,
                cpuNanos = 1_000_000L,
            ),
            analysis.events[0],
        )
        assertEquals(
            ArtTraceEvent(
                threadId = 1,
                methodId = 2,
                action = ArtTraceAction.EXIT,
                timeNanos = 4_000_000L,
                cpuNanos = 2_000_000L,
            ),
            analysis.events[1],
        )
    }

    @Test
    fun `parses classic v2 single-clock records`() {
        val trace =
            classicTrace(
                version = 2,
                text = "*threads\n\t1\tmain\n*methods\n\t4\tMainActivity\tonCreate\t(Landroid/os/Bundle;)V\tMainActivity.java\n*end\n",
                records = listOf(ClassicRecord(thread = 1, methodValue = 4, cpuMicros = 500, wallMicros = 0)),
            )

        val analysis = assertIs<ArtTraceParseResult.Success>(ArtTraceParser.parse(trace)).analysis
        assertEquals(1, analysis.events.size)
        assertEquals(500_000L, analysis.events[0].timeNanos)
        assertEquals(null, analysis.events[0].cpuNanos)
    }

    @Test
    fun `rejects a bad magic value`() {
        val result = ArtTraceParser.parse(byteArrayOf(1, 2, 3, 4))
        assertIs<ArtTraceParseResult.Failure>(result)
    }

    @Test
    fun `rejects an unsupported version`() {
        val trace =
            TraceBuilder()
                .apply {
                    u32(TRACE_MAGIC)
                    u16(7)
                }.build()
        assertIs<ArtTraceParseResult.Failure>(ArtTraceParser.parse(trace))
    }

    @Test
    fun `rejects a truncated streaming trace`() {
        val trace =
            TraceBuilder()
                .apply {
                    u32(TRACE_MAGIC)
                    u16(4)
                    u64(0L)
                    // header truncated — no padding, no packets
                }.build()
        assertIs<ArtTraceParseResult.Failure>(ArtTraceParser.parse(trace))
    }

    @Test
    fun `rejects the legacy version 1 format`() {
        val trace =
            TraceBuilder()
                .apply {
                    u32(TRACE_MAGIC)
                    u16(1)
                }.build()
        val result = ArtTraceParser.parse(trace)
        val failure = assertIs<ArtTraceParseResult.Failure>(result)
        assertTrue(failure.message.contains("version 1"))
    }

    // --- Fixture builders ----------------------------------------------------------------

    private fun streamingTrace(
        version: Int,
        packets: TraceBuilder.() -> Unit,
    ): ByteArray {
        val builder =
            TraceBuilder().apply {
                u32(TRACE_MAGIC)
                u16(version)
                u64(1_000_000_000L) // start time, ns
                repeat(18) { u8(0) } // pad to the 32-byte header
                packets()
            }
        return builder.build()
    }

    private fun classicTrace(
        version: Int,
        text: String,
        records: List<ClassicRecord>,
    ): ByteArray {
        val builder =
            TraceBuilder().apply {
                u32(TRACE_MAGIC)
                u16(version)
                u16(0) // data offset (unused when the text section is present)
                u64(1_000_000L) // start time, µs
                u16(if (version == 3) 14 else 10) // record size (offset 16, within the padding)
                repeat(14) { u8(0) } // pad to the 32-byte header
                string(text)
                records.forEach { record ->
                    u16(record.thread)
                    u32(record.methodValue)
                    u32(record.cpuMicros)
                    if (version == 3) u32(record.wallMicros)
                }
            }
        return builder.build()
    }

    private fun TraceBuilder.threadPacket(
        tid: Int,
        name: String,
    ) {
        u8(0) // kThreadInfoHeaderV2
        u32(tid.toLong())
        u16(name.length)
        string(name)
    }

    private fun TraceBuilder.methodPacket(
        methodId: Long,
        info: String,
    ) {
        u8(1) // kMethodInfoHeaderV2
        u64(methodId)
        u16(info.length)
        string(info)
    }

    private fun TraceBuilder.entryBlock(
        threadId: Int,
        entries: List<EntryDelta>,
        dual: Boolean,
    ) {
        val payload =
            TraceBuilder()
                .apply {
                    entries.forEach { entry ->
                        sleb(entry.method.toLong())
                        uleb(entry.time.toLong())
                        if (dual) uleb(entry.cpu.toLong())
                    }
                }.build()
        u8(2) // kEntryHeaderV2
        u32(threadId.toLong())
        u24(entries.size)
        u32(payload.size.toLong())
        bytes(payload)
    }

    private data class EntryDelta(
        val method: Int,
        val time: Int,
        val cpu: Int = 0,
    )

    private data class ClassicRecord(
        val thread: Int,
        val methodValue: Long,
        val cpuMicros: Long,
        val wallMicros: Long,
    )

    private class TraceBuilder {
        private val out = ArrayList<Byte>()

        fun u8(value: Int) {
            out.add((value and 0xff).toByte())
        }

        fun u16(value: Int) {
            out.add((value and 0xff).toByte())
            out.add(((value ushr 8) and 0xff).toByte())
        }

        fun u24(value: Int) {
            out.add((value and 0xff).toByte())
            out.add(((value ushr 8) and 0xff).toByte())
            out.add(((value ushr 16) and 0xff).toByte())
        }

        fun u32(value: Long) {
            repeat(4) { index -> out.add(((value ushr (8 * index)) and 0xff).toByte()) }
        }

        fun u64(value: Long) {
            repeat(8) { index -> out.add(((value ushr (8 * index)) and 0xff).toByte()) }
        }

        fun uleb(value: Long) {
            var remaining = value
            while (true) {
                val byte = (remaining and 0x7f).toInt()
                remaining = remaining ushr 7
                if (remaining == 0L) {
                    out.add(byte.toByte())
                    break
                } else {
                    out.add((byte or 0x80).toByte())
                }
            }
        }

        fun sleb(value: Long) {
            var remaining = value
            while (true) {
                var byte = (remaining and 0x7f).toInt()
                remaining = remaining shr 7
                val signBitSet = byte and 0x40 != 0
                if ((remaining == 0L && !signBitSet) || (remaining == -1L && signBitSet)) {
                    out.add(byte.toByte())
                    break
                } else {
                    byte = byte or 0x80
                    out.add(byte.toByte())
                }
            }
        }

        fun string(value: String) {
            value.toByteArray(Charsets.UTF_8).forEach { out.add(it) }
        }

        fun bytes(value: ByteArray) {
            value.forEach { out.add(it) }
        }

        fun build(): ByteArray = out.toByteArray()
    }

    private companion object {
        const val TRACE_MAGIC = 0x574f4c53L
    }
}
