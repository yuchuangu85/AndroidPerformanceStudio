package com.androidperformancestudio.platform.toolchain

import com.androidperformancestudio.contracts.ArtifactFileEvidence
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration

class NativeBinaryQueryTest {
    @Test
    fun `query validates source and returns exactly one successful framed payload`() =
        runBlocking {
            fixture { executable, input, digest ->
                val worker =
                    NativeBinaryQuery(
                        runner { request ->
                            assertEquals(listOf("query", input.toRealPath().toString()), request.arguments)
                            success(frame())
                        },
                    )
                assertEquals(mapOf("total" to 1L), worker.execute(executable, "query", input, digest))
            }
        }

    @Test
    fun `retargeting an input symlink cannot change the worker input`() =
        runBlocking {
            fixture { executable, input, digest ->
                val link = input.resolveSibling("source-link")
                val other = Files.writeString(input.resolveSibling("other-source"), "wrong profile")
                assumeTrue(runCatching { Files.createSymbolicLink(link, input) }.isSuccess, "Host does not support symbolic links")
                val worker =
                    NativeBinaryQuery(
                        runner { request ->
                            Files.delete(link)
                            Files.createSymbolicLink(link, other)
                            assertEquals(input.toRealPath(), Path.of(request.arguments[1]))
                            assertEquals("fixture", Files.readString(Path.of(request.arguments[1])))
                            success(frame())
                        },
                    )
                assertEquals(mapOf("total" to 1L), worker.execute(executable, "query", link, digest))
            }
        }

    @Test
    fun `malformed partial failed or wrong version output cannot become a result`() =
        runBlocking {
            fixture { executable, input, digest ->
                for (response in listOf(
                    success(frame() + frame()),
                    success(frame().copyOf(5)),
                    success(frame(version = 2)),
                    success(frame(status = "error")),
                    success(frame()).copy(stdoutTruncated = true),
                )) {
                    assertFailsWith<IOException> { NativeBinaryQuery(runner { response }).execute(executable, "query", input, digest) }
                }
            }
        }

    @Test
    fun `source mutation and cancellation cannot return a successful query`() =
        runBlocking {
            fixture { executable, input, digest ->
                assertFailsWith<CancellationException> {
                    NativeBinaryQuery(runner { error("must not run") }).execute(executable, "query", input, digest) { true }
                }
                assertFailsWith<IOException> {
                    NativeBinaryQuery(
                        runner {
                            Files.writeString(input, "changed")
                            success(frame())
                        },
                    ).execute(executable, "query", input, digest)
                }
            }
        }

    private suspend fun fixture(block: suspend (Path, Path, String) -> Unit) {
        val root = Files.createTempDirectory("aps-native-query-")
        try {
            val executable = Files.writeString(root.resolve("engine"), "fixture").also { it.toFile().setExecutable(true) }
            val input = Files.writeString(root.resolve("source"), "fixture")
            block(executable, input, ArtifactFileEvidence.sha256(input).value)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun frame(
        version: Long = 1,
        status: String = "ok",
    ): ByteArray =
        ByteArrayOutputStream()
            .also {
                NativeValueWire.writeFrame(
                    DataOutputStream(it),
                    mapOf(
                        "version" to version,
                        "status" to status,
                        "payload" to mapOf("total" to 1L),
                    ),
                )
            }.toByteArray()

    private fun success(bytes: ByteArray) = HostProcessBinaryResult(1, 0, bytes, byteArrayOf(), Duration.ZERO, false, false)

    private fun runner(execute: (HostProcessRequest) -> HostProcessBinaryResult) =
        object : HostProcessRunner {
            override suspend fun executeBinary(request: HostProcessRequest) = execute(request)

            override suspend fun executeText(request: HostProcessRequest): HostProcessTextResult = error("not used")

            override fun launch(request: HostProcessLaunchRequest): RunningHostProcess = error("not used")
        }
}
