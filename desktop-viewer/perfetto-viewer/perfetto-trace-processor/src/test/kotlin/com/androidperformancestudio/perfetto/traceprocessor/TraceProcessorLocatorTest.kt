package com.androidperformancestudio.perfetto.traceprocessor

import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.SystemHostPlatformDetector
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TraceProcessorLocatorTest {
    @Test
    fun `prefers native binary packaged with Compose application resources`() =
        runBlocking {
            val resources = Files.createTempDirectory("perfetto-packaged-tools")
            val binary = resources.resolve("perfetto-tools/trace_processor_shell")
            binary.parent.createDirectories()
            Files.writeString(binary, "binary")
            binary.toFile().setExecutable(true)

            val result =
                TraceProcessorLocator(
                    hostPlatformDetector = SystemHostPlatformDetector(osName = { "Mac OS X" }, osArch = { "arm64" }),
                    extractionRoot = Files.createTempDirectory("perfetto-empty-install"),
                    configuredPath = null,
                    applicationResourcesPath = resources.toString(),
                    pathEnvironment = "",
                ).locate()

            val located = assertIs<StudioResult.Success<TraceProcessorBinary>>(result).value
            assertEquals(binary, located.path)
            assertEquals("packaged-v57.2", located.version)
        }
}
