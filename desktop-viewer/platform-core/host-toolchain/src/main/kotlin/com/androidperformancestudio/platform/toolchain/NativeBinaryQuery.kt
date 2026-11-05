package com.androidperformancestudio.platform.toolchain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.time.Duration.Companion.minutes

/** An owned, cancellable one-shot worker. It cannot replace an accepted profiler result by itself. */
class NativeBinaryQuery(
    private val runner: HostProcessRunner = JvmHostProcessRunner(),
) {
    suspend fun execute(
        executable: Path,
        operation: String,
        source: Path,
        sourceDigest: String,
        arguments: List<String> = emptyList(),
        isCancellationRequested: () -> Boolean = { false },
    ): Map<*, *> =
        withContext(Dispatchers.IO) {
            require(DIGEST.matches(sourceDigest)) { "Expected a lowercase SHA-256 input identity" }
            val binary = executable.toRealPath()
            val input = source.toRealPath()
            require(Files.isRegularFile(binary) && Files.isExecutable(binary))
            require(Files.isRegularFile(input) && Files.isReadable(input))
            checkCancellation(isCancellationRequested)
            if (nativeInputDigest(input, isCancellationRequested) != sourceDigest) throw IOException("Native input identity changed")
            val response =
                runner.executeBinary(
                    HostProcessRequest(
                        executable = binary,
                        // Bind execution to the same canonical input that was hashed.
                        arguments = listOf(operation, input.toString()) + arguments,
                        timeout = 5.minutes,
                        maxOutputBytesPerStream = NativeValueWire.MAX_FRAME_BYTES + Int.SIZE_BYTES,
                        isCancellationRequested = isCancellationRequested,
                    ),
                )
            checkCancellation(isCancellationRequested)
            if (response.exitCode != 0 || response.stdoutTruncated) throw IOException("Native query failed or exceeded output budget")
            if (nativeInputDigest(input, isCancellationRequested) != sourceDigest) throw IOException("Native input identity changed")
            checkCancellation(isCancellationRequested)
            val frame =
                NativeValueWire.decodeSingleFrame(response.stdout) as? Map<*, *>
                    ?: throw IOException("Invalid native response")
            if (frame["version"] != 1L || frame["status"] != "ok") throw IOException("Unsupported or failed native response")
            frame["payload"] as? Map<*, *> ?: throw IOException("Native payload must be a map")
        }

    private suspend fun checkCancellation(cancelled: () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if (cancelled()) throw CancellationException("Native query cancelled")
    }

    companion object {
        private val DIGEST = Regex("[0-9a-f]{64}")
    }
}

internal suspend fun nativeInputDigest(
    input: Path,
    cancelled: () -> Boolean,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(input).use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            if (cancelled()) throw CancellationException("Native input hashing cancelled")
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
