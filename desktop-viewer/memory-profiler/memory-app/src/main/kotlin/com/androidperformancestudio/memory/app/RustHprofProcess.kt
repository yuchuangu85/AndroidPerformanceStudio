@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package com.androidperformancestudio.memory.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** One owned child, one in-flight request, and no retained query results. */
internal class RustHprofProcess internal constructor(
    private val child: Process,
    internal val sourceDigest: String,
    private val timeoutMillis: Long = 300_000,
) : AutoCloseable {
    private val input = DataInputStream(child.inputStream)
    private val output = DataOutputStream(child.outputStream)
    private val terminal = AtomicReference<String?>()
    private val terminated = AtomicBoolean()
    private val requestLock = ReentrantLock()
    private var nextId = 1L
    private val executor =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "aps-hprof-request").apply { isDaemon = true }
        }
    private val diagnostics = ByteArrayOutputStream()
    private val stderrThread = Thread({ drainStderr() }, "aps-hprof-stderr").apply { isDaemon = true }

    init {
        require(timeoutMillis > 0) { "Rust HPROF request timeout must be positive" }
        stderrThread.start()
    }

    @Suppress("ThrowsCount")
    fun request(
        op: String,
        args: Map<String, Any?> = emptyMap(),
    ): Any? {
        checkUsable()
        val started = System.nanoTime()
        var locked = false
        try {
            locked = requestLock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!locked) throw TimeoutException("Request queue timed out")
            checkUsable()
            val id = nextId++
            val future =
                executor.submit<Any?> {
                    RustHprofWire.writeFrame(output, mapOf("version" to 1L, "id" to id, "op" to op, "args" to args))
                    output.flush()
                    decodeResponse(RustHprofWire.readFrame(input), id)
                }
            val remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - (System.nanoTime() - started)
            if (remaining <= 0) throw TimeoutException("Request timed out")
            val payload = future.get(remaining, TimeUnit.NANOSECONDS)
            checkUsable()
            return payload
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw fail("Rust HPROF request '$op' interrupted", error)
        } catch (error: TimeoutException) {
            throw fail("Rust HPROF request '$op' timed out after ${timeoutMillis}ms", error)
        } catch (error: ExecutionException) {
            throw fail("Rust HPROF request '$op' failed: ${error.cause?.message}", error.cause ?: error)
        } catch (error: IOException) {
            throw fail(error.message ?: "Rust HPROF transport failed", error)
        } catch (error: RuntimeException) {
            throw fail("Rust HPROF request '$op' failed: ${error.message}", error)
        } finally {
            if (locked) requestLock.unlock()
        }
    }

    fun invalidate(error: IOException): IOException = fail("Invalid Rust HPROF payload: ${error.message}", error)

    @Suppress("ThrowsCount")
    private fun decodeResponse(
        value: Any?,
        id: Long,
    ): Any? {
        val response = value as? Map<*, *> ?: throw IOException("Response must be a map")
        if (response["version"] != 1L) throw IOException("Unsupported Rust HPROF response version")
        if (response["id"] != id) throw IOException("Rust HPROF response request id mismatch")
        if (response["sourceDigest"] != sourceDigest) throw IOException("Rust HPROF response source digest mismatch")
        when (response["status"]) {
            "ok" -> {
                if (!response.containsKey("payload")) throw IOException("Rust HPROF response is missing payload")
                return response["payload"]
            }
            "error" -> throw IOException(response["error"] as? String ?: "Malformed Rust HPROF error response")
            else -> throw IOException("Invalid Rust HPROF response status")
        }
    }

    private fun checkUsable() {
        terminal.get()?.let { throw IOException(it) }
    }

    private fun fail(
        message: String,
        cause: Throwable,
    ): IOException {
        terminal.compareAndSet(null, message)
        terminate()
        val stderr = synchronized(diagnostics) { diagnostics.toString(Charsets.UTF_8) }
        val detail = if (stderr.isBlank()) "" else "\nSidecar stderr (bounded): $stderr"
        return IOException("${terminal.get()}$detail", cause)
    }

    private fun drainStderr() {
        try {
            val buffer = ByteArray(4096)
            while (true) {
                val count = child.errorStream.read(buffer)
                if (count < 0) break
                synchronized(diagnostics) {
                    val retained = minOf(count, MAX_STDERR_BYTES - diagnostics.size())
                    if (retained > 0) diagnostics.write(buffer, 0, retained)
                }
            }
        } catch (_: IOException) {
            // Destroying the owned child closes this pipe.
        }
    }

    private fun terminate() {
        if (!terminated.compareAndSet(false, true)) return
        executor.shutdownNow()
        runCatching { child.destroy() }
        try {
            if (!child.waitFor(200, TimeUnit.MILLISECONDS)) {
                child.destroyForcibly()
                child.waitFor(200, TimeUnit.MILLISECONDS)
            }
        } catch (_: InterruptedException) {
            runCatching { child.destroyForcibly() }
            Thread.currentThread().interrupt()
        }
        runCatching { child.outputStream.close() }
        runCatching { child.inputStream.close() }
        runCatching { child.errorStream.close() }
    }

    override fun close() {
        terminal.compareAndSet(null, "Rust HPROF snapshot is closed")
        terminate()
    }

    companion object {
        internal const val MAX_STDERR_BYTES = 16 * 1024

        fun start(
            executable: Path,
            sourceDigest: String,
        ): RustHprofProcess {
            require(sourceDigest.isNotBlank()) { "Rust HPROF source digest must not be blank" }
            val resolved = executable.toRealPath()
            require(Files.isRegularFile(resolved) && Files.isExecutable(resolved)) {
                "Rust HPROF executable is not an executable regular file: $resolved"
            }
            val child = ProcessBuilder(resolved.toString()).start()
            return try {
                RustHprofProcess(child, sourceDigest)
            } catch (error: Throwable) {
                child.destroyForcibly()
                throw error
            }
        }
    }
}

/** Frozen v1 typed-value codec. All allocation bounds are checked before allocation. */
internal object RustHprofWire {
    const val MAX_FRAME_BYTES = 32 * 1024 * 1024
    const val MAX_STRING_BYTES = 4 * 1024 * 1024
    const val MAX_COUNT = 1_000_000
    const val MAX_DEPTH = 64

    fun readFrame(input: DataInputStream): Any? {
        val length = input.readInt().toLong() and 0xffff_ffffL
        if (length !in 1..MAX_FRAME_BYTES.toLong()) throw IOException("Invalid Rust HPROF frame length: $length")
        val bytes = ByteArray(length.toInt())
        input.readFully(bytes)
        val frame = DataInputStream(ByteArrayInputStream(bytes))
        val value = readValue(frame, 0, Budget())
        if (frame.available() != 0) throw IOException("Trailing bytes in Rust HPROF frame")
        return value
    }

    fun writeFrame(
        output: DataOutputStream,
        value: Any?,
    ) {
        val bytes = BoundedBytes()
        val frame = DataOutputStream(bytes)
        writeValue(frame, value, 0, Budget())
        output.writeInt(bytes.size())
        bytes.writeTo(output)
    }

    private fun readValue(
        input: DataInputStream,
        depth: Int,
        budget: Budget,
    ): Any? {
        budget.consume(depth)
        return when (val tag = input.readUnsignedByte()) {
            0 -> null
            1 -> false
            2 -> true
            3 -> input.readLong()
            4 -> readString(input)
            5 -> {
                val count = readCount(input, input.available())
                buildList {
                    repeat(count) { add(readValue(input, depth + 1, budget)) }
                }
            }
            6 -> {
                val count = readCount(input, input.available() / 5)
                val result = linkedMapOf<String, Any?>()
                repeat(count) {
                    val key = readString(input)
                    if (result.containsKey(key)) throw IOException("Duplicate Rust HPROF map key: $key")
                    result[key] = readValue(input, depth + 1, budget)
                }
                result
            }
            else -> throw IOException("Invalid Rust HPROF value tag: $tag")
        }
    }

    private fun readCount(
        input: DataInputStream,
        remaining: Int,
    ): Int {
        val count = input.readInt().toLong() and 0xffff_ffffL
        if (count > MAX_COUNT || count > remaining) throw IOException("Excessive Rust HPROF collection count: $count")
        return count.toInt()
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt().toLong() and 0xffff_ffffL
        if (length > MAX_STRING_BYTES || length > input.available()) {
            throw IOException("Invalid Rust HPROF string length: $length")
        }
        val bytes = ByteArray(length.toInt())
        input.readFully(bytes)
        return Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun writeValue(
        output: DataOutputStream,
        value: Any?,
        depth: Int,
        budget: Budget,
    ) {
        budget.consume(depth)
        when (value) {
            null -> output.writeByte(0)
            false -> output.writeByte(1)
            true -> output.writeByte(2)
            is Long -> {
                output.writeByte(3)
                output.writeLong(value)
            }
            is Int -> {
                output.writeByte(3)
                output.writeLong(value.toLong())
            }
            is String -> {
                output.writeByte(4)
                writeString(output, value)
            }
            is List<*> -> {
                checkCount(value.size)
                output.writeByte(5)
                output.writeInt(value.size)
                value.forEach { writeValue(output, it, depth + 1, budget) }
            }
            is Map<*, *> -> {
                checkCount(value.size)
                output.writeByte(6)
                output.writeInt(value.size)
                value.forEach { (key, item) ->
                    writeString(output, key as? String ?: throw IOException("Rust HPROF map keys must be strings"))
                    writeValue(output, item, depth + 1, budget)
                }
            }
            else -> throw IOException("Unsupported Rust HPROF value type: ${value.javaClass.name}")
        }
    }

    private fun writeString(
        output: DataOutputStream,
        value: String,
    ) {
        if (value.length > MAX_STRING_BYTES) throw IOException("Rust HPROF string exceeds size limit")
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_STRING_BYTES) throw IOException("Rust HPROF string exceeds size limit")
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun checkCount(count: Int) {
        if (count > MAX_COUNT) throw IOException("Excessive Rust HPROF collection count: $count")
    }

    private class Budget {
        private var values = 0

        fun consume(depth: Int) {
            if (depth > MAX_DEPTH || ++values > MAX_COUNT) {
                throw IOException("Excessive Rust HPROF nesting or value count")
            }
        }
    }

    private class BoundedBytes : ByteArrayOutputStream() {
        override fun write(value: Int) {
            ensureCapacityFor(1)
            super.write(value)
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            ensureCapacityFor(length)
            super.write(bytes, offset, length)
        }

        private fun ensureCapacityFor(length: Int) {
            if (length > MAX_FRAME_BYTES - size()) throw IOException("Rust HPROF frame exceeds size limit")
        }
    }
}
