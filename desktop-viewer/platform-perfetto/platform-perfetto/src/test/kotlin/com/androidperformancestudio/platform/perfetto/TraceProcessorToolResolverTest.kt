package com.androidperformancestudio.platform.perfetto

import com.androidperformancestudio.contracts.ArtifactFileEvidence
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.toolchain.CpuArchitecture
import com.androidperformancestudio.platform.toolchain.HostOperatingSystem
import com.androidperformancestudio.platform.toolchain.HostPlatform
import com.androidperformancestudio.platform.toolchain.HostPlatformDetector
import com.androidperformancestudio.platform.toolchain.HostProcessBinaryResult
import com.androidperformancestudio.platform.toolchain.HostProcessLaunchRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRunner
import com.androidperformancestudio.platform.toolchain.HostProcessTextResult
import com.androidperformancestudio.platform.toolchain.RunningHostProcess
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking

class TraceProcessorToolResolverTest {
    @Test
    fun `pinned manifest covers every supported desktop host`() {
        val expected =
            setOf(
                HostPlatform(HostOperatingSystem.MACOS, CpuArchitecture.X64),
                HostPlatform(HostOperatingSystem.MACOS, CpuArchitecture.ARM64),
                HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.X64),
                HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.ARM64),
                HostPlatform(HostOperatingSystem.WINDOWS, CpuArchitecture.X64),
            )

        assertEquals(expected, TraceProcessorManifest.pinnedV57_2().checksums.keys)
    }

    @Test
    fun `resolves only a checksum verified packaged binary for the pinned schema`() =
        runBlocking {
            val resources = Files.createTempDirectory("packaged-perfetto")
            val binary = resources.resolve("perfetto-tools/trace_processor_shell")
            Files.createDirectories(binary.parent)
            Files.writeString(binary, "pinned binary")
            binary.toFile().setExecutable(true)
            val platform = HostPlatform(HostOperatingSystem.MACOS, CpuArchitecture.ARM64)
            val expectedHash = ArtifactFileEvidence.sha256(binary).value

            val result =
                TraceProcessorToolResolver(
                    platformDetector = HostPlatformDetector { StudioResult.Success(platform) },
                    manifest = TraceProcessorManifest(mapOf(platform to expectedHash)),
                    applicationResourcesPath = resources,
                    installedToolsPath = Files.createTempDirectory("no-installed-tool"),
                    configuredOverride = null,
                ).resolve()

            val tool = assertIs<StudioResult.Success<TraceProcessorTool>>(result).value
            assertEquals(binary, tool.path)
            assertEquals(expectedHash, tool.sha256)
        }

    @Test
    fun `rejects an explicit override that does not report the pinned version`() =
        runBlocking {
            val binary = Files.createTempFile("override-trace-processor", "")
            binary.toFile().setExecutable(true)
            val platform = HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.X64)
            val result =
                TraceProcessorToolResolver(
                    platformDetector = HostPlatformDetector { StudioResult.Success(platform) },
                    manifest = TraceProcessorManifest(emptyMap()),
                    applicationResourcesPath = null,
                    installedToolsPath = Files.createTempDirectory("no-installed-tool"),
                    configuredOverride = binary,
                    processRunner = VersionRunner("Trace Processor v56.0"),
                ).resolve()

            assertEquals(
                "TRACE_PROCESSOR_INCOMPATIBLE",
                assertIs<StudioResult.Failure>(result).error.code,
            )
        }

    @Test
    fun `rejects a packaged checksum mismatch without selecting an arbitrary PATH tool`() =
        runBlocking {
            val resources = Files.createTempDirectory("bad-packaged-perfetto")
            val binary = resources.resolve("perfetto-tools/trace_processor_shell")
            Files.createDirectories(binary.parent)
            Files.writeString(binary, "not the pinned binary")
            binary.toFile().setExecutable(true)
            val platform = HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.X64)
            val result =
                TraceProcessorToolResolver(
                    platformDetector = HostPlatformDetector { StudioResult.Success(platform) },
                    manifest = TraceProcessorManifest(mapOf(platform to "a".repeat(64))),
                    applicationResourcesPath = resources,
                    installedToolsPath = Files.createTempDirectory("no-installed-tool"),
                    configuredOverride = null,
                ).resolve()

            assertEquals("TRACE_PROCESSOR_CHECKSUM_MISMATCH", assertIs<StudioResult.Failure>(result).error.code)
        }

    @Test
    fun `reports actionable not found instead of falling back to PATH`() =
        runBlocking {
            val platform = HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.ARM64)
            val result =
                TraceProcessorToolResolver(
                    platformDetector = HostPlatformDetector { StudioResult.Success(platform) },
                    manifest = TraceProcessorManifest(mapOf(platform to "a".repeat(64))),
                    applicationResourcesPath = Files.createTempDirectory("no-packaged-tool"),
                    installedToolsPath = Files.createTempDirectory("no-installed-tool"),
                    configuredOverride = null,
                ).resolve()

            assertEquals("TRACE_PROCESSOR_NOT_FOUND", assertIs<StudioResult.Failure>(result).error.code)
            assertTrue(assertIs<StudioResult.Failure>(result).error.message.contains("explicitly"))
        }

    @Test
    fun `accepts an explicit compatible override and records its checksum`() =
        runBlocking {
            val binary = Files.createTempFile("override-trace-processor", "")
            Files.writeString(binary, "compatible override")
            binary.toFile().setExecutable(true)
            val platform = HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.X64)
            val result =
                TraceProcessorToolResolver(
                    platformDetector = HostPlatformDetector { StudioResult.Success(platform) },
                    manifest = TraceProcessorManifest(emptyMap()),
                    applicationResourcesPath = null,
                    installedToolsPath = Files.createTempDirectory("no-installed-tool"),
                    configuredOverride = binary,
                    processRunner = VersionRunner("Perfetto Trace Processor v57.2"),
                ).resolve()

            val tool = assertIs<StudioResult.Success<TraceProcessorTool>>(result).value
            assertEquals(ArtifactFileEvidence.sha256(binary).value, tool.sha256)
            assertEquals("v57.2", tool.version)
        }

    private class VersionRunner(
        private val version: String,
    ) : HostProcessRunner {
        override suspend fun executeText(request: HostProcessRequest): HostProcessTextResult =
            HostProcessTextResult(-1, 0, version, "", Duration.ZERO, false, false)

        override suspend fun executeBinary(request: HostProcessRequest): HostProcessBinaryResult = error("not used")

        override fun launch(request: HostProcessLaunchRequest): RunningHostProcess = error("not used")
    }
}
