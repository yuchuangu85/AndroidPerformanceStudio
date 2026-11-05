@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package com.androidperformancestudio.network.har

import com.androidperformancestudio.network.model.NetworkCaptureResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/** A sanitized failure: native stderr, source paths and raw JSON never enter diagnostics. */
public class NativeHarException internal constructor() : IllegalArgumentException("Native HAR import rejected or unavailable")

/**
 * Experimental opt-in transport. Rust validates and streams bounded entries; Kotlin
 * owns the retained formal mapping and redaction. No raw entry spool is created.
 * Two passes preserve the baseline even when the earliest entry is skipped or last.
 */
public class NativeHarParser(
    private val executable: Path,
    private val maxBytes: Long = 512L * 1024L * 1024L,
    private val timeoutMillis: Long = 120_000,
    private val pageSize: Int = 16,
) {
    @Suppress("ThrowsCount")
    public fun parse(path: Path, checkCancelled: () -> Unit = {}): NetworkCaptureResult {
        try {
            require(maxBytes in 1..MAX_INPUT_BYTES && timeoutMillis in 1..MAX_TIMEOUT_MILLIS && pageSize in 1..MAX_PAGE_SIZE)
            require(Files.isRegularFile(path) && Files.size(path) <= maxBytes)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            val guard = {
                checkCancelled()
                if (Thread.currentThread().isInterrupted) throw CancellationException("HAR import cancelled")
                check(System.nanoTime() < deadline)
            }
            val fingerprint = fingerprint(path, guard)
            val mapper = HarParser(maxBytes)
            var base: Instant? = null
            val metadata = stream(path, guard) { page ->
                page.forEach { record ->
                    mapper.entryStart(record.value, guard)?.let { start ->
                        if (base == null || start < base) base = start
                    }
                }
            }
            check(fingerprint == fingerprint(path, guard))
            val accumulator = mapper.EntryAccumulator(base ?: Instant.EPOCH, guard)
            val second = stream(path, guard) { page ->
                page.forEach { record ->
                    guard()
                    accumulator.add(record.value, record.index)
                }
            }
            check(metadata == second && fingerprint == fingerprint(path, guard))
            return accumulator.finish(metadata.version, metadata.creator, fingerprint)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("HAR import cancelled")
        } catch (_: Exception) {
            throw NativeHarException()
        }
    }

    private fun fingerprint(path: Path, guard: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                guard()
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                check(count <= maxBytes)
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun stream(path: Path, guard: () -> Unit, consumePage: (List<EntryRecord>) -> Unit): Metadata {
        guard()
        val process = ProcessBuilder(executable.toAbsolutePath().toString(), "stream-entries", path.toAbsolutePath().toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        try {
            process.outputStream.close()
            val wire = WireReader(process.inputStream, process, maxBytes * 2 + 4096, guard)
            check(wire.line() == "APS-HAR-ENTRIES\t1")
            var version: String? = null
            var creator: JsonElement? = null
            var creatorSeen = false
            var count = 0
            var pageBytes = 0
            val page = mutableListOf<EntryRecord>()

            fun flush() {
                if (page.isNotEmpty()) consumePage(page)
                page.clear()
                pageBytes = 0
            }
            while (true) {
                guard()
                val fields = wire.line().split('\t')
                when (fields.first()) {
                    "entry" -> {
                        check(fields.size == 3 && fields[1].toInt() == count && count < MAX_ENTRIES)
                        val length = fields[2].toInt()
                        check(length in 1..MAX_VALUE_BYTES)
                        if (pageBytes + length > MAX_VALUE_BYTES) flush()
                        val value = Json.parseToJsonElement(wire.value(length))
                        page += EntryRecord(count++, value)
                        pageBytes += length
                        if (page.size == pageSize) flush()
                    }
                    "version" -> {
                        check(fields.size == 2 && version == null)
                        version = wire.value(fields[1].toInt())
                        check(version == "1.1" || version == "1.2")
                    }
                    "creator" -> {
                        check(fields.size == 2 && !creatorSeen)
                        creatorSeen = true
                        creator = Json.parseToJsonElement(wire.value(fields[1].toInt()))
                    }
                    "end" -> {
                        check(fields.size == 2 && fields[1].toInt() == count && version != null)
                        check(wire.next() == -1)
                        while (!process.waitFor(10, TimeUnit.MILLISECONDS)) guard()
                        check(process.exitValue() == 0)
                        flush()
                        return Metadata(version, creator, count)
                    }
                    else -> error("Invalid native HAR protocol")
                }
            }
        } finally {
            process.inputStream.close()
            if (process.isAlive) process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
        }
    }

    private data class EntryRecord(
        val index: Int,
        val value: JsonElement,
    )

    private data class Metadata(
        val version: String,
        val creator: JsonElement?,
        val count: Int,
    )

    /** Poll the pipe so cancellation and timeout also work when native emits no bytes. */
    private class WireReader(
        private val input: InputStream,
        private val process: Process,
        private val maxBytes: Long,
        private val guard: () -> Unit,
    ) {
        private val buffer = ByteArray(8192)
        private var offset = 0
        private var size = 0
        private var count = 0L

        fun next(): Int {
            if (offset == size) {
                while (true) {
                    guard()
                    val available = input.available()
                    if (available > 0) {
                        size = input.read(buffer, 0, minOf(available, buffer.size)).coerceAtLeast(0)
                        offset = 0
                        count += size
                        check(count <= maxBytes)
                        if (size > 0) break
                    } else if (!process.isAlive) {
                        return -1
                    } else {
                        Thread.sleep(5)
                    }
                }
            }
            return buffer[offset++].toInt() and 0xff
        }

        fun line(): String = buildString {
            while (true) {
                val byte = next()
                check(byte in 0..127)
                if (byte == 10) break
                check(length < 128)
                append(byte.toChar())
            }
        }

        fun value(length: Int): String {
            check(length in 1..MAX_VALUE_BYTES)
            val bytes = ByteArray(length)
            for (index in bytes.indices) {
                val byte = next()
                check(byte >= 0)
                bytes[index] = byte.toByte()
            }
            check(next() == 10)
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }
    }

    private companion object {
        const val MAX_VALUE_BYTES = 2 * 1024 * 1024
        const val MAX_ENTRIES = 1_000_000
        const val MAX_PAGE_SIZE = 256
        const val MAX_INPUT_BYTES = 512L * 1024L * 1024L
        const val MAX_TIMEOUT_MILLIS = 3_600_000L
    }
}
