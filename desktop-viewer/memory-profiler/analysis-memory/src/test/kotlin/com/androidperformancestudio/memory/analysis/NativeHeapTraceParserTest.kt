@file:Suppress("MaxLineLength")

package com.androidperformancestudio.memory.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeHeapTraceParserTest {
    @Test
    fun `truncated protobuf degrades to an empty summary`() {
        val result = NativeHeapTraceParser.parse(byteArrayOf(0x0a, 0x80.toByte()))

        assertEquals(0, result.sampleCount)
        assertTrue(result.topAllocations.isEmpty())
    }

    @Test
    fun `parses profile packets and aggregates allocations by leaf function`() {
        val trace =
            TraceBuilder()
                .packet(
                    ProfilePacketBuilder()
                        .internedString(1, "malloc")
                        .internedString(2, "operator new")
                        .frame(1, functionNameId = 1)
                        .frame(2, functionNameId = 2)
                        .callstack(iid = 10, frames = listOf(1, 2))
                        .processDump(
                            pid = 42,
                            sample(callstackId = 10, allocated = 1024, freed = 128, allocCount = 4, freeCount = 1),
                        ).build(),
                ).build()

        val result = NativeHeapTraceParser.parse(trace)

        assertEquals(1024L, result.totalAllocatedBytes)
        assertEquals(128L, result.totalFreedBytes)
        assertEquals(1, result.sampleCount)
        val top = result.topAllocations.single()
        assertEquals("operator new", top.functionName)
        assertEquals(1024L, top.allocatedBytes)
        assertEquals(4L, top.allocCount)
    }

    @Test
    fun `missing callstack resolution falls back to unknown symbol`() {
        val trace =
            TraceBuilder()
                .packet(
                    ProfilePacketBuilder()
                        .processDump(pid = 1, sample(callstackId = 99, allocated = 512, allocCount = 2))
                        .build(),
                ).build()

        val result = NativeHeapTraceParser.parse(trace)

        assertEquals(512L, result.totalAllocatedBytes)
        assertEquals("<unknown>", result.topAllocations.single().functionName)
    }

    @Test
    fun `resolves symbols from trace level interned data`() {
        // Android R+ traces keep the interning tables in TracePacket.interned_data (field 12)
        // instead of inside each ProfilePacket.
        val functionNames = PB.field(5, concat(PB.fieldVarint(1, 1), PB.field(2, "malloc".encodeToByteArray())))
        val frames = PB.field(6, concat(PB.fieldVarint(1, 1), PB.fieldVarint(2, 1)))
        val callstacks = PB.field(7, concat(PB.fieldVarint(1, 10), PB.fieldVarint(2, 1)))
        val internedData = concat(functionNames, frames, callstacks)
        val profilePacket =
            PB.field(
                37,
                PB.field(5, concat(PB.fieldVarint(1, 42), PB.field(2, sample(callstackId = 10, allocated = 2048, allocCount = 8)))),
            )
        val tracePacket = concat(PB.field(12, internedData), profilePacket)
        val trace = PB.field(1, tracePacket)

        val result = NativeHeapTraceParser.parse(trace)

        assertEquals(2048L, result.totalAllocatedBytes)
        assertEquals("malloc", result.topAllocations.single().functionName)
        assertEquals(8L, result.topAllocations.single().allocCount)
    }

    @Test
    fun `interned ids are isolated by trusted packet sequence`() {
        fun packet(
            sequence: Long,
            function: String,
            allocated: Long,
        ): ByteArray {
            val internedData =
                concat(
                    PB.field(5, concat(PB.fieldVarint(1, 1), PB.field(2, function.encodeToByteArray()))),
                    PB.field(6, concat(PB.fieldVarint(1, 1), PB.fieldVarint(2, 1))),
                    PB.field(7, concat(PB.fieldVarint(1, 1), PB.fieldVarint(2, 1))),
                )
            val profile =
                PB.field(
                    37,
                    PB.field(5, concat(PB.fieldVarint(1, 42), PB.field(2, sample(1, allocated = allocated)))),
                )
            return PB.field(1, concat(PB.fieldVarint(10, sequence), PB.field(12, internedData), profile))
        }
        val trace = concat(packet(1, "first", 100), packet(2, "second", 200))

        val result = NativeHeapTraceParser.parse(trace)

        assertEquals(listOf("second", "first"), result.topAllocations.map { it.functionName })
    }

    @Test
    fun `aggregates multiple samples sharing a leaf function`() {
        val trace =
            TraceBuilder()
                .packet(
                    ProfilePacketBuilder()
                        .internedString(1, "malloc")
                        .frame(1, functionNameId = 1)
                        .callstack(iid = 1, frames = listOf(1))
                        .processDump(pid = 1, sample(callstackId = 1, allocated = 100, allocCount = 1))
                        .processDump(pid = 1, sample(callstackId = 1, allocated = 200, allocCount = 2))
                        .build(),
                ).build()

        val result = NativeHeapTraceParser.parse(trace)

        assertEquals(300L, result.totalAllocatedBytes)
        assertEquals("malloc", result.topAllocations.single().functionName)
        assertEquals(300L, result.topAllocations.single().allocatedBytes)
        assertEquals(3L, result.topAllocations.single().allocCount)
        assertTrue(result.topAllocations.size == 1)
    }
}

/** Minimal protobuf encoder helpers for building a synthetic Perfetto trace in tests. */
private class TraceBuilder {
    private val packets = mutableListOf<ByteArray>()

    fun packet(profilePacket: ByteArray): TraceBuilder {
        packets.add(PB.field(1, profilePacket))
        return this
    }

    fun build(): ByteArray = concat(packets)
}

private class ProfilePacketBuilder {
    private val fields = mutableListOf<ByteArray>()

    fun internedString(
        iid: Long,
        str: String,
    ): ProfilePacketBuilder {
        fields.add(PB.field(1, concat(PB.fieldVarint(1, iid), PB.field(2, str.encodeToByteArray()))))
        return this
    }

    fun frame(
        iid: Long,
        functionNameId: Long,
    ): ProfilePacketBuilder {
        fields.add(PB.field(2, concat(PB.fieldVarint(1, iid), PB.fieldVarint(2, functionNameId))))
        return this
    }

    fun callstack(
        iid: Long,
        frames: List<Long>,
    ): ProfilePacketBuilder {
        val frameBytes = frames.map { PB.fieldVarint(2, it) }
        fields.add(PB.field(3, concat(PB.fieldVarint(1, iid), concat(frameBytes))))
        return this
    }

    fun processDump(
        pid: Long,
        vararg samples: ByteArray,
    ): ProfilePacketBuilder {
        val dump = mutableListOf(PB.fieldVarint(1, pid))
        samples.forEach { dump.add(PB.field(2, it)) }
        fields.add(PB.field(5, concat(dump)))
        return this
    }

    fun build(): ByteArray = PB.field(37, concat(fields))
}

private fun sample(
    callstackId: Long,
    allocated: Long = 0,
    freed: Long = 0,
    allocCount: Long = 0,
    freeCount: Long = 0,
): ByteArray =
    concat(
        PB.fieldVarint(1, callstackId),
        PB.fieldVarint(2, allocated),
        PB.fieldVarint(3, freed),
        PB.fieldVarint(5, allocCount),
        PB.fieldVarint(6, freeCount),
    )

private object PB {
    fun tag(
        field: Int,
        wire: Int,
    ): ByteArray = varint(((field shl 3) or wire).toLong())

    fun varint(value: Long): ByteArray {
        val out = mutableListOf<Byte>()
        var v = value
        while (v and 0x7fL.inv() != 0L) {
            out.add(((v and 0x7f) or 0x80).toByte())
            v = v ushr 7
        }
        out.add(v.toByte())
        return out.toByteArray()
    }

    fun field(
        field: Int,
        message: ByteArray,
    ): ByteArray = concat(tag(field, 2), varint(message.size.toLong()), message)

    fun fieldVarint(
        field: Int,
        value: Long,
    ): ByteArray = concat(tag(field, 0), varint(value))
}

private fun concat(parts: List<ByteArray>): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    parts.forEach(out::write)
    return out.toByteArray()
}

private fun concat(vararg parts: ByteArray): ByteArray = concat(parts.toList())
