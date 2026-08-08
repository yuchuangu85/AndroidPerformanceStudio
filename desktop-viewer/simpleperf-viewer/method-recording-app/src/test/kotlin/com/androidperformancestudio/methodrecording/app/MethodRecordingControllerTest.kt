package com.androidperformancestudio.methodrecording.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MethodRecordingControllerTest {
    @Test
    fun `importing a trace populates analysis, top methods and flame graph`() = runTest {
        val controller = MethodRecordingController(adbExecutable = null, sessionRoot = Files.createTempDirectory("mr"))
        val traceFile = writeTraceFixture()

        controller.importTrace(traceFile)

        val state = controller.state.value
        assertNotNull(state.analysis)
        assertNotNull(state.flameGraph)
        assertTrue(state.topMethods.isNotEmpty())
        assertEquals(null, state.error)
        assertNotNull(state.traceLabel)
    }

    @Test
    fun `importing a non-trace file reports an error`() = runTest {
        val controller = MethodRecordingController(adbExecutable = null, sessionRoot = Files.createTempDirectory("mr"))
        val badFile = Files.createTempFile("not-a-trace", ".trace")
        Files.writeString(badFile, "this is not a trace")

        controller.importTrace(badFile)

        assertNotNull(controller.state.value.error)
        assertEquals(null, controller.state.value.analysis)
    }

    @Test
    fun `refresh devices without adb reports a helpful message`() = runTest {
        val controller = MethodRecordingController(adbExecutable = null, sessionRoot = Files.createTempDirectory("mr"))
        controller.refreshDevices()
        assertNotNull(controller.state.value.error)
    }

    private fun writeTraceFixture(): Path {
        // Minimal streaming v4 trace: one thread, one method, enter + exit.
        val bytes =
            TraceBuilder()
                .apply {
                    u32(0x574f4c53L)
                    u16(4)
                    u64(0L)
                    repeat(18) { u8(0) }
                    // thread info
                    u8(0)
                    u32(1)
                    u16(4)
                    string("main")
                    // method info: index 1
                    u8(1)
                    u64(1)
                    u16(0)
                    string("")
                    // entry block: enter(1) at t=0, exit(1) at t=1000 (single clock)
                    entryBlock(threadId = 1, records = listOf(Pair(4L, 0L), Pair(5L, 1_000L)))
                }
                .build()
        val file = Files.createTempFile("fixture", ".trace")
        Files.write(file, bytes)
        return file
    }

    private fun TraceBuilder.entryBlock(threadId: Int, records: List<Pair<Long, Long>>) {
        val payload =
            TraceBuilder().apply {
                records.forEach { (method, time) ->
                    sleb(method)
                    uleb(time)
                }
            }.build()
        u8(2)
        u32(threadId.toLong())
        u24(records.size)
        u32(payload.size.toLong())
        bytes(payload)
    }

    private class TraceBuilder {
        private val out = ArrayList<Byte>()

        fun u8(value: Int) = apply { out.add((value and 0xff).toByte()) }

        fun u16(value: Int) = apply {
            out.add((value and 0xff).toByte())
            out.add(((value ushr 8) and 0xff).toByte())
        }

        fun u24(value: Int) = apply {
            out.add((value and 0xff).toByte())
            out.add(((value ushr 8) and 0xff).toByte())
            out.add(((value ushr 16) and 0xff).toByte())
        }

        fun u32(value: Long) = apply {
            repeat(4) { index -> out.add(((value ushr (8 * index)) and 0xff).toByte()) }
        }

        fun u64(value: Long) = apply {
            repeat(8) { index -> out.add(((value ushr (8 * index)) and 0xff).toByte()) }
        }

        fun uleb(value: Long) = apply {
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

        fun sleb(value: Long) = apply {
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

        fun string(value: String) = apply { value.toByteArray(Charsets.UTF_8).forEach { out.add(it) } }

        fun bytes(value: ByteArray) = apply { value.forEach { out.add(it) } }

        fun build(): ByteArray = out.toByteArray()
    }
}
