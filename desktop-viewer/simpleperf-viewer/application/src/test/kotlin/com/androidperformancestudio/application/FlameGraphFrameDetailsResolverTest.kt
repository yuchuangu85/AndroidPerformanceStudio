@file:Suppress("MaxLineLength")

package com.androidperformancestudio.application

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.toolchain.AndroidLlvmTool
import com.androidperformancestudio.toolchain.AndroidLlvmToolProvider
import com.androidperformancestudio.toolchain.CapturedProcessText
import com.androidperformancestudio.toolchain.ProcessOutput
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlameGraphFrameDetailsResolverTest {
    @Test
    fun `verified symbolizer location resolves source text`() =
        runTest {
            val fixture = resolverFixture()
            val source = fixture.root.resolve("Render.cpp").also { it.writeLines(listOf("one", "two", "three")) }
            fixture.invoker.results += completed("Build ID: aa11")
            fixture.invoker.results += completed("renderFrame\n$source:2:3")

            val result = fixture.resolver.resolve(fixture.request(buildId = "AA 11"))

            val resolved = assertIs<FlameGraphFrameDetails.Source>(result)
            assertEquals(source, resolved.file)
            assertEquals(2, resolved.line)
            assertEquals(3, resolved.column)
            assertEquals(listOf("one", "two", "three"), resolved.text)
        }

    @Test
    fun `source miss falls through to bounded disassembly`() =
        runTest {
            val fixture = resolverFixture()
            fixture.invoker.results += completed("??\n??:0:0")
            fixture.invoker.results += completed("0x100 <renderFrame>:\n mov x0, x1")

            val result = fixture.resolver.resolve(fixture.request())

            val resolved = assertIs<FlameGraphFrameDetails.Disassembly>(result)
            assertEquals(0x100, resolved.address)
            assertTrue(resolved.text.any { line -> line.contains("mov") })
            assertTrue(
                fixture.invoker.requests
                    .last()
                    .arguments
                    .any { it == "--start-address=0xc0" },
            )
            assertTrue(
                fixture.invoker.requests
                    .last()
                    .arguments
                    .any { it == "--stop-address=0x180" },
            )
        }

    @Test
    fun `missing binary traversal build mismatch and tool failures return truthful fallbacks`() =
        runTest {
            val missing = resolverFixture(createBinary = false)
            assertFallback(missing.resolver.resolve(missing.request()), "binary")
            assertEquals(emptyList(), missing.invoker.requests)

            val traversal = resolverFixture(createBinary = false)
            assertFallback(traversal.resolver.resolve(traversal.request(resource = "/../../secret.so")), "unsafe")
            assertEquals(emptyList(), traversal.invoker.requests)

            val mismatch = resolverFixture()
            mismatch.invoker.results += completed("Build ID: bb22")
            assertFallback(mismatch.resolver.resolve(mismatch.request(buildId = "aa11")), "Build ID mismatch")
            assertEquals(1, mismatch.invoker.requests.size)

            val failures = resolverFixture()
            failures.invoker.results += failed("PROCESS_EXIT_1")
            failures.invoker.results += failed("PROCESS_EXIT_2")
            assertFallback(failures.resolver.resolve(failures.request()), "PROCESS_EXIT_2")
        }

    @Test
    fun `coroutine cancellation is never converted into a fallback`() =
        runTest {
            val fixture = resolverFixture(invoker = RecordingInvoker { awaitCancellation() })
            val resolving = async { fixture.resolver.resolve(fixture.request()) }

            resolving.cancel()

            assertFailsWith<CancellationException> { resolving.await() }
        }
}

private data class ResolverFixture(
    val root: Path,
    val binary: Path,
    val resolver: FlameGraphFrameDetailsResolver,
    val invoker: RecordingInvoker,
) {
    fun request(
        resource: String = "/system/lib64/libui.so",
        buildId: String? = null,
    ): FlameGraphFrameDetailsRequest =
        FlameGraphFrameDetailsRequest(
            sessionDirectory = root,
            function = "renderFrame",
            resource = resource,
            address = 0x100,
            libraryOffset = 0x80,
            buildId = buildId,
        )
}

private fun resolverFixture(
    createBinary: Boolean = true,
    invoker: RecordingInvoker = RecordingInvoker(),
): ResolverFixture {
    val root = Files.createTempDirectory("aps-frame-details-")
    val binary = root.resolve("symbols/system/lib64/libui.so")
    if (createBinary) binary.parent.createDirectories().also { binary.createFile() }
    val tools =
        AndroidLlvmTool.entries.associateWith { tool ->
            root.resolve("tools/${tool.executableName}").also { path ->
                path.parent.createDirectories()
                path.createFile()
            }
        }
    val resolver =
        FlameGraphFrameDetailsResolver(
            toolProvider = AndroidLlvmToolProvider(tools::get),
            processInvoker = invoker,
        )
    return ResolverFixture(root, binary, resolver, invoker)
}

private class RecordingInvoker(
    private val block: (suspend (ProcessRequest) -> ProcessRunResult)? = null,
) : FlameGraphProcessInvoker {
    val requests = mutableListOf<ProcessRequest>()
    val results = ArrayDeque<ProcessRunResult>()

    override suspend fun run(request: ProcessRequest): ProcessRunResult {
        requests += request
        return block?.invoke(request) ?: results.removeFirst()
    }
}

private fun completed(stdout: String): ProcessRunResult =
    ProcessRunResult.Completed(
        ProcessOutput(
            pid = 1,
            command = emptyList(),
            exitCode = 0,
            stdout = CapturedProcessText(stdout, truncated = false),
            stderr = CapturedProcessText("", truncated = false),
            startedAt = Instant.EPOCH,
            finishedAt = Instant.EPOCH,
        ),
    )

private fun failed(code: String): ProcessRunResult = ProcessRunResult.Failed(StudioError(ErrorCategory.PROCESS_EXIT, code, code))

private fun assertFallback(
    result: FlameGraphFrameDetails,
    expectedReason: String,
) {
    val fallback = assertIs<FlameGraphFrameDetails.SymbolFallback>(result)
    assertTrue(fallback.reason.contains(expectedReason, ignoreCase = true), fallback.reason)
}
