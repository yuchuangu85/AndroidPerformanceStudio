package com.androidperformancestudio.platform.toolchain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.time.Duration.Companion.minutes

enum class NativeShadowStatus {
    SUMMARY_MATCHED,
    SUMMARY_MISMATCH,
    UNAVAILABLE,
}

data class NativeShadowResult(
    val status: NativeShadowStatus,
    val sourceDigest: String? = null,
    val mismatchedMetrics: Set<String> = emptySet(),
) {
    fun properties(): String =
        buildString {
            append("status=${status.name}\n")
            sourceDigest?.let { append("sourceDigest=$it\n") }
            append("mismatchedMetrics=${mismatchedMetrics.sorted().joinToString(",")}\n")
            append("scope=numeric-summary-only\n")
        }
}

/** An opt-in comparison, never a replacement for a profiler's accepted result. */
class NativeSummaryShadow(
    private val runner: HostProcessRunner = JvmHostProcessRunner(),
) {
    suspend fun compare(
        executable: Path,
        input: Path,
        expected: Map<String, Long>,
        expectedSourceDigest: String? = null,
        isCancellationRequested: () -> Boolean = { false },
    ): NativeShadowResult =
        withContext(Dispatchers.IO) {
            try {
                require(expected.isNotEmpty() && expected.keys.all { METRIC.matches(it) })
                val resolved = executable.toRealPath()
                require(Files.isRegularFile(resolved) && Files.isExecutable(resolved))
                require(Files.isRegularFile(input) && Files.isReadable(input))
                checkCancellation(isCancellationRequested)
                val before = nativeInputDigest(input, isCancellationRequested)
                check(expectedSourceDigest == null || expectedSourceDigest == before)
                val output =
                    runner.executeText(
                        HostProcessRequest(
                            executable = resolved,
                            arguments = listOf("summarize-tsv", input.toAbsolutePath().toString()),
                            timeout = 5.minutes,
                            maxOutputBytesPerStream = MAX_OUTPUT_BYTES,
                            isCancellationRequested = isCancellationRequested,
                        ),
                    )
                checkCancellation(isCancellationRequested)
                check(output.exitCode == 0 && !output.stdoutTruncated)
                val actual = parse(output.stdout)
                check(before == nativeInputDigest(input, isCancellationRequested))
                val mismatches = expected.keys.filterTo(sortedSetOf()) { expected[it] != actual[it] }
                NativeShadowResult(
                    if (mismatches.isEmpty()) NativeShadowStatus.SUMMARY_MATCHED else NativeShadowStatus.SUMMARY_MISMATCH,
                    before,
                    mismatches,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Native errors and stderr can contain private input; keep them out of reports.
                NativeShadowResult(NativeShadowStatus.UNAVAILABLE)
            }
        }

    private suspend fun checkCancellation(cancelled: () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if (cancelled()) throw CancellationException("Native shadow comparison cancelled")
    }

    companion object {
        private const val MAX_OUTPUT_BYTES = 8192
        private const val MAX_METRICS = 32
        private val METRIC = Regex("[A-Za-z][A-Za-z0-9]{0,63}")

        fun configured(
            property: String,
            environmentVariable: String,
        ): Path? =
            runCatching {
                (System.getProperty(property) ?: System.getenv(environmentVariable))
                    ?.takeIf(String::isNotBlank)
                    ?.let(Path::of)
            }.getOrNull()

        internal fun parse(text: String): Map<String, Long> {
            require(text.length <= MAX_OUTPUT_BYTES && text.endsWith('\n'))
            val lines = text.dropLast(1).split('\n')
            require(lines.size in 1..MAX_METRICS)
            val values = linkedMapOf<String, Long>()
            for (line in lines) {
                val columns = line.split('\t')
                require(columns.size == 2 && METRIC.matches(columns[0]))
                require(columns[1].isNotEmpty() && columns[1].all { it in '0'..'9' })
                val value = columns[1].toLong()
                require(values.put(columns[0], value) == null)
            }
            require(values["formatVersion"] == 1L)
            return values
        }
    }
}
