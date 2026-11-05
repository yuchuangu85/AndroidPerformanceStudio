package com.androidperformancestudio.platform.toolchain

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeValueWireTest {
    @Test
    fun `frozen wire preserves all types and exact signed bits`() {
        val data =
            mapOf(
                "values" to listOf(null, false, true, Long.MIN_VALUE, Long.MAX_VALUE, "\u4e2d\u6587"),
                "empty" to emptyMap<String, Any?>(),
            )
        assertEquals(data, NativeValueWire.decodeSingleFrame(encode(data)))
    }

    @Test
    fun `one-shot wire rejects trailing frames and every truncated prefix`() {
        val data = encode(mapOf("id" to 1L, "value" to "hello"))
        for (size in data.indices) assertFailsWith<IOException> { NativeValueWire.decodeSingleFrame(data.copyOf(size)) }
        assertFailsWith<IOException> { NativeValueWire.decodeSingleFrame(data + encode(null)) }
    }

    @Test
    fun `hostile lengths and unknown type tags fail before allocation`() {
        listOf(
            byteArrayOf(0, 0, 0, 1, 127),
            byteArrayOf(-1, -1, -1, -1),
            byteArrayOf(0, 0, 0, 5, 4, 127, -1, -1, -1),
        ).forEach { assertFailsWith<IOException> { NativeValueWire.decodeSingleFrame(it) } }
    }

    private fun encode(value: Any?): ByteArray =
        ByteArrayOutputStream()
            .also {
                NativeValueWire.writeFrame(DataOutputStream(it), value)
            }.toByteArray()
}
