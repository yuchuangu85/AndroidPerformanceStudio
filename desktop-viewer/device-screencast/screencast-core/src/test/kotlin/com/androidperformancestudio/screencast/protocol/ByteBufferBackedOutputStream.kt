package com.androidperformancestudio.screencast.protocol

import java.io.ByteArrayOutputStream

/** Simple collecting output stream for protocol encoder assertions. */
class ByteBufferBackedOutputStream : java.io.OutputStream() {
    private val buffer = ByteArrayOutputStream()

    override fun write(value: Int) {
        buffer.write(value)
    }

    override fun write(
        data: ByteArray,
        offset: Int,
        length: Int,
    ) {
        buffer.write(data, offset, length)
    }

    fun toBytes(): ByteArray = buffer.toByteArray()
}
