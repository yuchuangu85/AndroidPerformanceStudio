@file:Suppress("MagicNumber", "MaxLineLength")

package com.androidperformancestudio.memory.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RustHprofProcessTest {
    @Test
    fun `typed values round trip preserves signed id bit patterns and unicode`() {
        val value =
            mapOf(
                "ids" to listOf(Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L),
                "values" to listOf(null, false, true, "\u4e2d\u6587 \ud83d\ude80", emptyMap<String, Any?>(), emptyList<Any?>()),
            )
        assertEquals(value, decodeFrame(wireFrame(value)))
    }

    @Test
    fun `decoder rejects invalid frame lengths before reading bodies`() {
        listOf(0, -1, Int.MIN_VALUE, RustHprofWire.MAX_FRAME_BYTES + 1).forEach { length ->
            val bytes = rawBytes { writeInt(length) }
            assertFailsWith<IOException> { decodeFrame(bytes) }
        }
    }

    @Test
    fun `decoder rejects truncated length body and value`() {
        listOf(
            byteArrayOf(0, 0, 0),
            rawBytes {
                writeInt(10)
                writeByte(0)
            },
            rawFrame {
                writeByte(3)
                writeInt(1)
            },
            rawFrame {
                writeByte(4)
                writeInt(20)
                writeByte(65)
            },
        ).forEach { bytes -> assertFailsWith<IOException> { decodeFrame(bytes) } }
    }

    @Test
    fun `decoder rejects duplicate keys even when previous value is null`() {
        val bytes =
            rawFrame {
                writeByte(6)
                writeInt(2)
                repeat(2) {
                    writeInt(1)
                    writeByte(65)
                    writeByte(0)
                }
            }
        assertTrue(assertFailsWith<IOException> { decodeFrame(bytes) }.message.orEmpty().contains("Duplicate"))
    }

    @Test
    fun `decoder rejects trailing bytes invalid tags and invalid utf8`() {
        listOf(
            rawFrame {
                writeByte(0)
                writeByte(0)
            },
            rawFrame { writeByte(7) },
            rawFrame {
                writeByte(4)
                writeInt(1)
                writeByte(0xff)
            },
        ).forEach { assertFailsWith<IOException> { decodeFrame(it) } }
    }

    @Test
    fun `decoder bounds strings collections and nesting`() {
        listOf(
            rawFrame {
                writeByte(4)
                writeInt(RustHprofWire.MAX_STRING_BYTES + 1)
            },
            rawFrame {
                writeByte(5)
                writeInt(RustHprofWire.MAX_COUNT + 1)
            },
            rawFrame {
                writeByte(6)
                writeInt(-1)
            },
            rawFrame {
                repeat(RustHprofWire.MAX_DEPTH + 1) {
                    writeByte(5)
                    writeInt(1)
                }
                writeByte(0)
            },
        ).forEach { assertFailsWith<IOException> { decodeFrame(it) } }
        val deepest =
            rawFrame {
                repeat(RustHprofWire.MAX_DEPTH) {
                    writeByte(5)
                    writeInt(1)
                }
                writeByte(0)
            }
        assertNotNull(decodeFrame(deepest))
    }

    @Test
    fun `encoder bounds strings counts total frame and unsupported float`() {
        assertFailsWith<IOException> { wireFrame("a".repeat(RustHprofWire.MAX_STRING_BYTES + 1)) }
        assertFailsWith<IOException> { wireFrame(List(RustHprofWire.MAX_COUNT + 1) { null }) }
        val largeString = "a".repeat(RustHprofWire.MAX_STRING_BYTES)
        assertFailsWith<IOException> { wireFrame(List(8) { largeString }) }
        assertFailsWith<IOException> { wireFrame(0.5f) }
    }

    @Test
    fun `all responses including errors validate version id and digest`() {
        listOf(
            mapOf("version" to 2L),
            mapOf("id" to 2L),
            mapOf("sourceDigest" to "other-digest"),
            mapOf("status" to "error", "error" to "source changed", "sourceDigest" to "other-digest"),
            mapOf("status" to "bad-status"),
        ).forEach { replacement ->
            val child = FakeRustChild(wireFrame(response(null) + replacement))
            RustHprofProcess(child, TEST_DIGEST).use { process ->
                assertFailsWith<IOException> { process.request("open") }
                assertFalse(child.isAlive)
                assertFailsWith<IOException> { process.request("identity") }
            }
        }
    }

    @Test
    fun `identity validation applies to later requests and ids advance`() {
        val child = FakeRustChild(wireFrame(response("first")) + wireFrame(response("stale", id = 1)))
        RustHprofProcess(child, TEST_DIGEST).use { process ->
            assertEquals("first", process.request("identity", mapOf("objectId" to Long.MIN_VALUE)))
            assertTrue(assertFailsWith<IOException> { process.request("findings") }.message.orEmpty().contains("id mismatch"))
            val requests = child.requests()
            assertEquals(listOf(1L, 2L), requests.map { it["id"] })
            assertEquals(mapOf("objectId" to Long.MIN_VALUE), requests.first()["args"])
        }
    }

    @Test
    fun `missing payload and remote errors invalidate owned child`() {
        listOf(
            response(null) - "payload",
            response(null) + mapOf("status" to "error", "error" to "source metadata changed"),
        ).forEach { envelope ->
            val child = FakeRustChild(wireFrame(envelope))
            RustHprofProcess(child, TEST_DIGEST).use { process ->
                assertFailsWith<IOException> { process.request("open") }
                assertEquals(1, child.destroyCount)
            }
            assertEquals(1, child.destroyCount)
        }
    }

    @Test
    fun `truncated transport response fails handle`() {
        val child = FakeRustChild(wireFrame(response(null)).dropLast(1).toByteArray())
        RustHprofProcess(child, TEST_DIGEST).use { process ->
            assertFailsWith<IOException> { process.request("findings") }
            assertFalse(child.isAlive)
        }
    }

    @Test
    fun `timeout kills child and future requests cannot reuse handle`() {
        val blocked = BlockingRustInput()
        val child = FakeRustChild(input = blocked)
        RustHprofProcess(child, TEST_DIGEST, timeoutMillis = 100).use { process ->
            val error = assertFailsWith<IOException> { process.request("open") }
            assertTrue(error.message.orEmpty().contains("timed out"))
            assertFalse(child.isAlive)
            assertFailsWith<IOException> { process.request("identity") }
        }
    }

    @Test
    fun `request timeout also bounds a blocked stdin write`() {
        val blocked = BlockingRustInput()
        val output =
            object : OutputStream() {
                override fun write(value: Int) {
                    blocked.read()
                }
            }
        val child = FakeRustChild(output = output, onDestroy = { blocked.close() })
        RustHprofProcess(child, TEST_DIGEST, timeoutMillis = 100).use { process ->
            assertTrue(assertFailsWith<IOException> { process.request("open") }.message.orEmpty().contains("timed out"))
            assertFalse(child.isAlive)
        }
    }

    @Test
    fun `close interrupts an in flight request and is idempotent`() {
        val blocked = BlockingRustInput()
        val child = FakeRustChild(input = blocked)
        val process = RustHprofProcess(child, TEST_DIGEST)
        val failure = AtomicReference<Throwable?>()
        val waiter = thread { runCatching { process.request("detail") }.onFailure(failure::set) }
        try {
            assertTrue(blocked.entered.await(2, TimeUnit.SECONDS))
            process.close()
            process.close()
            waiter.join(2000)
            assertFalse(waiter.isAlive)
            assertTrue(failure.get() is IOException)
            assertEquals(1, child.destroyCount)
            assertFailsWith<IOException> { process.request("identity") }
        } finally {
            process.close()
            waiter.join(2000)
        }
    }

    @Test
    fun `interrupted request kills child and preserves caller interrupt`() {
        val blocked = BlockingRustInput()
        val child = FakeRustChild(input = blocked)
        val interrupted = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        RustHprofProcess(child, TEST_DIGEST).use { process ->
            val waiter =
                thread {
                    try {
                        process.request("open")
                    } catch (error: IOException) {
                        failure.set(error)
                        interrupted.set(Thread.currentThread().isInterrupted)
                    }
                }
            try {
                assertTrue(blocked.entered.await(2, TimeUnit.SECONDS))
                waiter.interrupt()
                waiter.join(2000)
                assertFalse(waiter.isAlive)
                assertNotNull(failure.get())
                assertTrue(interrupted.get())
                assertFalse(child.isAlive)
            } finally {
                process.close()
                waiter.join(2000)
            }
        }
    }

    @Test
    fun `stderr is drained past retention limit without blocking request`() {
        val drained = CountDownLatch(1)
        val stderr =
            object : ByteArrayInputStream(ByteArray(RustHprofProcess.MAX_STDERR_BYTES * 8) { 65 }) {
                override fun read(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = super.read(bytes, offset, length).also { if (it < 0) drained.countDown() }
            }
        val child = FakeRustChild(wireFrame(response(null) + mapOf("status" to "error", "error" to "broken")), stderr = stderr)
        RustHprofProcess(child, TEST_DIGEST).use { process ->
            assertTrue(drained.await(2, TimeUnit.SECONDS))
            val error = assertFailsWith<IOException> { process.request("open") }
            assertTrue(error.message.orEmpty().contains("Sidecar stderr"))
            assertTrue(error.message.orEmpty().length < RustHprofProcess.MAX_STDERR_BYTES + 1024)
        }
    }

    @Test
    fun `start rejects missing or non executable files without spawning`() {
        val directory = Files.createTempDirectory("rust sidecar validation ")
        try {
            assertFailsWith<IOException> { RustHprofProcess.start(directory.resolve("missing"), TEST_DIGEST) }
            assertFailsWith<IllegalArgumentException> { RustHprofProcess.start(directory, TEST_DIGEST) }
            val plainFile = Files.write(directory.resolve("not executable"), byteArrayOf(1))
            assertFailsWith<IllegalArgumentException> { RustHprofProcess.start(plainFile, TEST_DIGEST) }
            assertFailsWith<IllegalArgumentException> { RustHprofProcess.start(plainFile, "") }
        } finally {
            Files.deleteIfExists(directory.resolve("not executable"))
            Files.deleteIfExists(directory)
        }
    }
}

internal const val TEST_DIGEST = "immutable-source-digest"

internal fun response(
    payload: Any?,
    id: Long = 1,
): Map<String, Any?> = mapOf("version" to 1L, "id" to id, "sourceDigest" to TEST_DIGEST, "status" to "ok", "payload" to payload)

internal fun rawBytes(write: DataOutputStream.() -> Unit): ByteArray =
    ByteArrayOutputStream().also { DataOutputStream(it).write() }.toByteArray()

internal fun rawFrame(write: DataOutputStream.() -> Unit): ByteArray {
    val body = rawBytes(write)
    return rawBytes {
        writeInt(body.size)
        write(body)
    }
}

internal fun wireFrame(value: Any?): ByteArray = rawBytes { RustHprofWire.writeFrame(this, value) }

private fun decodeFrame(bytes: ByteArray): Any? = RustHprofWire.readFrame(DataInputStream(ByteArrayInputStream(bytes)))

internal class FakeRustChild(
    replies: ByteArray = byteArrayOf(),
    private val input: InputStream = ByteArrayInputStream(replies),
    private val output: OutputStream = ByteArrayOutputStream(),
    private val stderr: InputStream = ByteArrayInputStream(byteArrayOf()),
    private val onDestroy: () -> Unit = {},
) : Process() {
    private val ended = CountDownLatch(1)
    var destroyCount = 0
        private set

    override fun getInputStream(): InputStream = input

    override fun getOutputStream(): OutputStream = output

    override fun getErrorStream(): InputStream = stderr

    override fun isAlive(): Boolean = ended.count != 0L

    override fun waitFor(): Int {
        ended.await()
        return 0
    }

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = ended.await(timeout, unit)

    override fun exitValue(): Int = if (isAlive) throw IllegalThreadStateException() else 0

    override fun destroy() {
        destroyCount++
        ended.countDown()
        onDestroy()
        input.close()
        output.close()
        stderr.close()
    }

    override fun destroyForcibly(): Process {
        destroy()
        return this
    }

    fun requests(): List<Map<*, *>> {
        val stream = DataInputStream(ByteArrayInputStream((output as ByteArrayOutputStream).toByteArray()))
        return buildList {
            while (stream.available() > 0) add(RustHprofWire.readFrame(stream) as Map<*, *>)
        }
    }
}

private class BlockingRustInput : InputStream() {
    val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)

    override fun read(): Int {
        entered.countDown()
        try {
            released.await()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted fake sidecar pipe", error)
        }
        return -1
    }

    override fun close() = released.countDown()
}
