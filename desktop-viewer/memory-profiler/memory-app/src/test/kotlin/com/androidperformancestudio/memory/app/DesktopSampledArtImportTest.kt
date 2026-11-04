package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.analysis.ArtSampledAllocationAnalysis
import com.androidperformancestudio.memory.presentation.MemorySampledArtOrigin
import com.androidperformancestudio.platform.perfetto.TraceProcessorToolResolver
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSampledArtImportTest {
    @Test
    fun `imports and exports a real emulator trace with verified ART samples`() =
        runBlocking {
            val source = System.getenv("APS_ART_TRACE_FIXTURE")?.let(Path::of) ?: return@runBlocking
            assertTrue(Files.isRegularFile(source))
            val tool = Path.of("..", "..", "build", "perfetto-tools", "trace_processor_shell").toAbsolutePath()
            val backend =
                DesktopMemoryProfilerBackend(
                    dataRoot = Files.createTempDirectory("art-real-import"),
                    sampledArtArtifactAnalyzer =
                        PerfettoSampledArtArtifactAnalyzer(TraceProcessorToolResolver(configuredOverride = tool)),
                )

            val loaded =
                assertIs<MemoryBackendResult.Success<LoadedSampledArt>>(
                    backend.importSampledArtAllocations(source),
                ).value
            assertEquals(MemorySampledArtOrigin.IMPORT, loaded.trace.origin)
            assertNull(loaded.trace.pid)
            val analysis = assertIs<SampledArtProcessingResult.Success>(loaded.processing).analysis
            assertTrue(analysis.sampleRows > 0)
            val output = Files.createTempFile("art-real-export", ".pb")
            backend.exportSampledArtAllocations(loaded.trace, output)
            assertContentEquals(Files.readAllBytes(source), Files.readAllBytes(output))
        }

    @Test
    fun `imports an existing local trace without inventing device capture metadata and exports exact bytes`() =
        runBlocking {
            val source = Files.createTempFile("art-existing", ".pb")
            Files.write(source, byteArrayOf(1, 2, 3, 4))
            val backend =
                DesktopMemoryProfilerBackend(
                    dataRoot = Files.createTempDirectory("art-import"),
                    sampledArtArtifactAnalyzer =
                        SampledArtArtifactAnalyzer {
                            SampledArtProcessingResult.Success(ArtSampledAllocationAnalysis(0, 0, 0, emptyList()))
                        },
                )

            val loaded =
                assertIs<MemoryBackendResult.Success<LoadedSampledArt>>(
                    backend.importSampledArtAllocations(source),
                )
            assertEquals(MemorySampledArtOrigin.IMPORT, loaded.value.trace.origin)
            assertEquals(4L, loaded.value.trace.fileSizeBytes)
            assertNull(loaded.value.trace.pid)
            assertNull(loaded.value.trace.sdkLevel)
            assertEquals(0L, assertIs<SampledArtProcessingResult.Success>(loaded.value.processing).analysis.sampleRows)
            val output = Files.createTempFile("art-export", ".pb")
            backend.exportSampledArtAllocations(loaded.value.trace, output)
            assertContentEquals(Files.readAllBytes(source), Files.readAllBytes(output))
        }

    @Test
    fun `missing and empty local traces are rejected before analysis`() =
        runBlocking {
            val backend =
                DesktopMemoryProfilerBackend(
                    dataRoot = Files.createTempDirectory("art-reject"),
                    sampledArtArtifactAnalyzer = SampledArtArtifactAnalyzer { error("unexpected analysis") },
                )
            val missing = Files.createTempDirectory("art-missing").resolve("missing.pb")
            val empty = Files.createTempFile("art-empty", ".pb")
            assertIs<MemoryBackendResult.Failure>(backend.importSampledArtAllocations(missing))
            assertIs<MemoryBackendResult.Failure>(backend.importSampledArtAllocations(empty))
            Unit
        }
}
