package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.HeapRootKind
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies the perfetto `java_hprof` wire parser + `HeapDump` conversion against hand-built byte
 * fixtures matching `Trace.packet = 1` and `TracePacket.heap_graph = 56`.
 */
class JavaHeapTraceParserTest {
    @Test
    fun `parses a heap graph and converts it to a HeapDump`() {
        val result = JavaHeapTraceParser.parse(traceBytes())
        val graph = assertIs<JavaHeapParseResult.Success>(result).heapGraph

        assertEquals(2, graph.types.size)
        assertEquals("android/app/Activity", graph.types[0].className)
        assertEquals(16, graph.types[0].objectSize)
        assertEquals(2, graph.objects.size)
        assertEquals(1, graph.roots.size)
        assertEquals("mContext", graph.fieldNames[1])
        assertEquals("value", graph.fieldNames[2])

        val dump = HeapGraphToHeapDump.toHeapDump(graph)
        assertEquals(2, dump.classes.size)
        assertEquals(2, dump.instances.size)
        assertEquals(1, dump.gcRoots.size)

        val activity = dump.instances.first { it.objectId == 100L }
        assertEquals("android.app.Activity", activity.className)
        assertEquals(16L, activity.shallowSize)
        assertEquals(1, activity.references.size)
        assertEquals("mContext", activity.references[0].fieldName)
        assertEquals(200L, activity.references[0].targetObjectId)
        assertEquals(HeapRootKind.JNI_GLOBAL, dump.gcRoots[0].kind)
    }

    @Test
    fun `decodes packed repeated fields and reference base`() {
        val graph =
            Pb().apply {
                message(9) {
                    varint(1, 1)
                    string(3, "Foo")
                }
                // object 100 -> references [500, 0] with base 400 (so raw values 100 and 0)
                message(2) {
                    varint(1, 100)
                    varint(2, 1)
                    packedVarints(5, listOf(100L, 0L))
                    varint(6, 400L)
                }
            }
        val result = JavaHeapTraceParser.parse(wrapInTracePacket(graph.bytes()))
        val parsed = assertIs<JavaHeapParseResult.Success>(result).heapGraph
        assertEquals(1, parsed.objects.size)
        assertEquals(listOf(500L, 0L), parsed.objects[0].referenceObjectIds)
    }

    @Test
    fun `assembles continued packets by sequence and preserves delta state`() {
        val first =
            Pb()
                .apply {
                    varint(6, 0)
                    varint(5, 1)
                    message(2) {
                        varint(1, 100)
                        varint(2, 1)
                        varint(9, 1)
                    }
                }.bytes()
        val second =
            Pb()
                .apply {
                    varint(6, 1)
                    message(9) {
                        varint(1, 1)
                        string(3, "Foo")
                    }
                    message(2) {
                        varint(7, 5)
                        varint(2, 1)
                    }
                }.bytes()
        val trace =
            Pb()
                .apply {
                    message(1) {
                        varint(10, 7)
                        bytesField(56, first)
                    }
                    message(1) {
                        varint(10, 7)
                        bytesField(56, second)
                    }
                }.bytes()

        val graph = assertIs<JavaHeapParseResult.Success>(JavaHeapTraceParser.parse(trace)).heapGraph

        assertEquals(listOf(100L, 105L), graph.objects.map { it.id })
        assertEquals(listOf(1, 1), graph.objects.map { it.heapType })
        assertEquals(7L, graph.sequenceId)
    }

    @Test
    fun `uses type and superclass field ids and captures Android metadata`() {
        val graph =
            Pb()
                .apply {
                    message(4) {
                        varint(1, 1)
                        string(2, "child")
                    }
                    message(4) {
                        varint(1, 2)
                        string(2, "parent")
                    }
                    message(9) {
                        varint(1, 1)
                        string(3, "Parent")
                        packedVarints(6, listOf(2))
                    }
                    message(9) {
                        varint(1, 2)
                        string(3, "Child")
                        varint(5, 1)
                        varint(8, 99)
                        packedVarints(6, listOf(1))
                    }
                    message(2) {
                        varint(1, 100)
                        varint(2, 2)
                        packedVarints(5, listOf(200, 0))
                        packedVarints(10, listOf(300))
                        varint(8, 4096)
                        varint(9, 3)
                        varint(13, 20)
                        varint(14, 10)
                    }
                }.bytes()

        val parsed =
            assertIs<JavaHeapParseResult.Success>(JavaHeapTraceParser.parse(wrapInTracePacket(graph))).heapGraph
        val dump = HeapGraphToHeapDump.toHeapDump(parsed)
        val instance = dump.instances.single()

        assertEquals(listOf("child", "parent", "<runtime-internal-0>"), instance.references.map { it.fieldName })
        assertEquals(0L, instance.references[1].targetObjectId)
        assertEquals(4096L, instance.nativeSizeBytes)
        assertEquals(20L, instance.primitiveFields["mWidth"])
        assertEquals("Image", dump.heapByObjectId[100])
        assertEquals(99L, dump.classes.first { it.objectId == 2L }.classLoaderObjectId)
    }

    @Test
    fun `rejects missing continuation packet and truncated fields`() {
        val missing =
            Pb()
                .apply {
                    message(1) {
                        varint(10, 2)
                        bytesField(56, Pb().apply { varint(6, 1) }.bytes())
                    }
                }.bytes()
        assertIs<JavaHeapParseResult.Failure>(JavaHeapTraceParser.parse(missing))
        assertIs<JavaHeapParseResult.Failure>(JavaHeapTraceParser.parse(byteArrayOf(0x0a, 0x05, 0x01)))
    }

    @Test
    fun `rejects a trace without a heap graph`() {
        val trace = Pb().apply { message(1) { string(1, "not a heap graph") } }.bytes()
        val result = JavaHeapTraceParser.parse(trace)
        val failure = assertIs<JavaHeapParseResult.Failure>(result)
        assertTrue(failure.message.contains("heap graph"))
    }

    private fun wrapInTracePacket(heapGraph: ByteArray): ByteArray {
        val packet = Pb().apply { message(1) { bytesField(56, heapGraph) } }
        return packet.bytes()
    }

    private fun traceBytes(): ByteArray =
        wrapInTracePacket(
            Pb()
                .apply {
                    message(4) {
                        varint(1, 1)
                        string(2, "mContext")
                    }
                    message(4) {
                        varint(1, 2)
                        string(2, "value")
                    }
                    message(9) {
                        varint(1, 1)
                        string(3, "android/app/Activity")
                        varint(4, 16)
                        varint(7, 1)
                        packedVarints(6, listOf(1L))
                    }
                    message(9) {
                        varint(1, 2)
                        string(3, "java/lang/String")
                        varint(4, 24)
                    }
                    message(2) {
                        varint(1, 100)
                        varint(2, 1)
                        varint(3, 16)
                        packedVarints(4, listOf(1L))
                        packedVarints(5, listOf(200L))
                    }
                    message(2) {
                        varint(1, 200)
                        varint(2, 2)
                        varint(3, 24)
                    }
                    message(7) {
                        packedVarints(1, listOf(100L))
                        varint(2, 1)
                    }
                }.bytes(),
        )

    /** Minimal protobuf wire writer for the fixture. */
    private class Pb {
        private val out = ByteArrayOutputStream()

        fun varint(
            field: Int,
            value: Long,
        ) {
            writeVarint((field shl 3).toLong())
            writeVarint(value)
        }

        fun string(
            field: Int,
            value: String,
        ) = bytesField(field, value.toByteArray())

        fun bytesField(
            field: Int,
            bytes: ByteArray,
        ) {
            writeVarint((field shl 3 or 2).toLong())
            writeVarint(bytes.size.toLong())
            out.write(bytes)
        }

        fun message(
            field: Int,
            build: Pb.() -> Unit,
        ) {
            val inner = Pb().apply(build)
            bytesField(field, inner.bytes())
        }

        fun packedVarints(
            field: Int,
            values: List<Long>,
        ) {
            val payload = ByteArrayOutputStream()
            values.forEach { value ->
                var remaining = value
                while (true) {
                    val byte = (remaining and 0x7f).toInt()
                    remaining = remaining ushr 7
                    if (remaining == 0L) {
                        payload.write(byte)
                        break
                    } else {
                        payload.write(byte or 0x80)
                    }
                }
            }
            bytesField(field, payload.toByteArray())
        }

        private fun writeVarint(value: Long) {
            var remaining = value
            while (true) {
                val byte = (remaining and 0x7f).toInt()
                remaining = remaining ushr 7
                if (remaining == 0L) {
                    out.write(byte)
                    break
                } else {
                    out.write(byte or 0x80)
                }
            }
        }

        fun bytes(): ByteArray = out.toByteArray()
    }
}
