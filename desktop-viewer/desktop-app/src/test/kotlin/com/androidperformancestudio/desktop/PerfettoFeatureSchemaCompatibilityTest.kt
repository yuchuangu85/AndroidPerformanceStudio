package com.androidperformancestudio.desktop

import com.androidperformancestudio.contracts.ArtifactAcquisition
import com.androidperformancestudio.contracts.ArtifactAcquisitionKind
import com.androidperformancestudio.contracts.ArtifactFileEvidence
import com.androidperformancestudio.contracts.ArtifactId
import com.androidperformancestudio.contracts.ArtifactKind
import com.androidperformancestudio.contracts.ArtifactLocation
import com.androidperformancestudio.contracts.ArtifactProvenance
import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.frame.analysis.FrameTimelineTraceAdapter
import com.androidperformancestudio.memory.analysis.JavaHeapTraceProcessorAdapter
import com.androidperformancestudio.memory.analysis.NativeHeapTraceProcessorAdapter
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.perfetto.TraceAnalysisContext
import com.androidperformancestudio.platform.perfetto.TraceAnalysisContexts
import com.androidperformancestudio.platform.perfetto.TraceProcessorTool
import com.androidperformancestudio.platform.perfetto.TraceQuery
import com.androidperformancestudio.platform.perfetto.TraceQuerySchema
import com.androidperformancestudio.startup.analysis.StartupPerfettoTraceAdapter
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking

class PerfettoFeatureSchemaCompatibilityTest {
    @Test
    fun `feature owned queries match the pinned Trace Processor schema`() =
        runBlocking {
            val binary = installedTraceProcessor()
            assumeTrue(binary != null, "Install the pinned Trace Processor to execute native schema compatibility")
            val trace =
                Path.of(
                    checkNotNull(javaClass.getResource("/fixtures/synthetic-empty.pftrace")).toURI(),
                )
            val tool =
                TraceProcessorTool(
                    checkNotNull(binary),
                    TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION,
                    ArtifactFileEvidence.sha256(binary).value,
                )
            val context =
                (TraceAnalysisContexts(tool).open(artifact(trace), trace) as StudioResult.Success<TraceAnalysisContext>).value
            try {
                val native = NativeHeapTraceProcessorAdapter()
                assertQuery(context, native.allocationQuery)
                assertQuery(context, native.callStackQuery)

                val java = JavaHeapTraceProcessorAdapter()
                assertQuery(context, java.classQuery)
                assertQuery(context, java.objectQuery)
                assertQuery(context, java.referenceQuery)

                assertQuery(context, FrameTimelineTraceAdapter().timelineQuery(null))

                val startup = StartupPerfettoTraceAdapter()
                assertQuery(context, startup.schedulingQuery(null))
                assertQuery(context, startup.binderQuery(null))
                assertQuery(context, startup.mainThreadQuery(null))
                assertQuery(context, startup.frameQuery(null))
            } finally {
                context.close()
            }
        }

    private suspend fun <T> assertQuery(
        context: TraceAnalysisContext,
        query: TraceQuery<T>,
    ) {
        val result = context.query(query)
        assertTrue(result is StudioResult.Success, (result as? StudioResult.Failure)?.error?.message)
    }

    private fun installedTraceProcessor(): Path? {
        val binaryName = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
            "trace_processor_shell.exe"
        } else {
            "trace_processor_shell"
        }
        val candidates =
            listOf(
                Path.of("..", "build", "perfetto-tools", binaryName),
                Path.of(
                    System.getProperty("user.home"),
                    ".android-performance-studio",
                    "tools",
                    "perfetto",
                    TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION,
                    binaryName,
                ),
            )
        return candidates.map(Path::toAbsolutePath).firstOrNull { Files.isExecutable(it) }
    }

    private fun artifact(trace: Path): CaptureArtifact =
        CaptureArtifact(
            id = ArtifactId("schema-fixture"),
            kind = ArtifactKind("perfetto.schema_fixture"),
            location = ArtifactLocation(trace.toString()),
            sha256 = ArtifactFileEvidence.sha256(trace),
            provenance =
                ArtifactProvenance(
                    acquisition =
                        ArtifactAcquisition(
                            ArtifactAcquisitionKind.IMPORT,
                            "schema-test",
                            performedAtEpochMillis = 0,
                        ),
                ),
        )
}
