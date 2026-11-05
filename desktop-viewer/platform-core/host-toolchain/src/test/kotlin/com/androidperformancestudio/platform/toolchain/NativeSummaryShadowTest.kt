package com.androidperformancestudio.platform.toolchain

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration

class NativeSummaryShadowTest {
    @Test
    fun `rejects duplicate malformed overflow and unsupported numeric summaries`() {
        val invalid =
            listOf(
                "formatVersion\t2\n",
                "formatVersion\t1\nformatVersion\t1\n",
                "formatVersion\t1",
                "formatVersion\t1\na\t-1\n",
                "formatVersion\t1\na\t9223372036854775808\n",
            )
        for (text in invalid) {
            assertFailsWith<IllegalArgumentException> { NativeSummaryShadow.parse(text) }
        }
        assertEquals(mapOf("formatVersion" to 1L, "sampleCount" to 12L), NativeSummaryShadow.parse("formatVersion\t1\nsampleCount\t12\n"))
    }

    @Test
    fun `compares summary while preserving input and never returns stderr`() =
        runBlocking {
            fixture { executable, input ->
                val result =
                    NativeSummaryShadow(
                        runner { request ->
                            assertEquals(listOf("summarize-tsv", input.toAbsolutePath().toString()), request.arguments)
                            success("formatVersion\t1\nsampleCount\t12\n")
                        },
                    ).compare(executable, input, mapOf("sampleCount" to 12L))
                assertEquals(NativeShadowStatus.SUMMARY_MATCHED, result.status)
                assertEquals(64, result.sourceDigest?.length)
                assertEquals("fixture", Files.readString(input))
                assertTrue(!result.properties().contains("private diagnostic"))
            }
        }

    @Test
    fun `mismatch invalid response and changed source do not become accepted results`() =
        runBlocking {
            fixture { executable, input ->
                val mismatch =
                    NativeSummaryShadow(runner { success("formatVersion\t1\nsampleCount\t13\n") })
                        .compare(executable, input, mapOf("sampleCount" to 12L))
                assertEquals(NativeShadowStatus.SUMMARY_MISMATCH, mismatch.status)
                assertEquals(setOf("sampleCount"), mismatch.mismatchedMetrics)
                val invalid =
                    NativeSummaryShadow(runner { success("not a summary") })
                        .compare(executable, input, mapOf("sampleCount" to 12L))
                assertEquals(NativeShadowStatus.UNAVAILABLE, invalid.status)
                val changed =
                    NativeSummaryShadow(
                        runner {
                            Files.writeString(input, "changed")
                            success("formatVersion\t1\nsampleCount\t12\n")
                        },
                    ).compare(executable, input, mapOf("sampleCount" to 12L))
                assertEquals(NativeShadowStatus.UNAVAILABLE, changed.status)
            }
        }

    @Test
    fun `cancellation is not swallowed as fallback`() =
        runBlocking {
            fixture { executable, input ->
                assertFailsWith<CancellationException> {
                    NativeSummaryShadow(runner { error("must not start") })
                        .compare(executable, input, mapOf("sampleCount" to 12L)) { true }
                }
            }
        }

    private suspend fun fixture(block: suspend (Path, Path) -> Unit) {
        val root = Files.createTempDirectory("aps-native-shadow-")
        try {
            val executable = Files.writeString(root.resolve("engine"), "fixture").also { it.toFile().setExecutable(true) }
            block(executable, Files.writeString(root.resolve("input"), "fixture"))
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun success(output: String) = HostProcessTextResult(1L, 0, output, "private diagnostic", Duration.ZERO, false, false)

    private fun runner(execute: (HostProcessRequest) -> HostProcessTextResult) =
        object : HostProcessRunner {
            override suspend fun executeText(request: HostProcessRequest) = execute(request)

            override suspend fun executeBinary(request: HostProcessRequest): HostProcessBinaryResult = error("not used")

            override fun launch(request: HostProcessLaunchRequest): RunningHostProcess = error("not used")
        }
}
