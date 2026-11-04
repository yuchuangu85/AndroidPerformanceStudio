package com.androidperformancestudio.perfetto.analysis

import com.androidperformancestudio.contracts.ArtifactAcquisition
import com.androidperformancestudio.contracts.ArtifactAcquisitionKind
import com.androidperformancestudio.contracts.ArtifactFileEvidence
import com.androidperformancestudio.contracts.ArtifactId
import com.androidperformancestudio.contracts.ArtifactKind
import com.androidperformancestudio.contracts.ArtifactLocation
import com.androidperformancestudio.contracts.ArtifactProvenance
import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.perfetto.TraceAnalysisContext
import com.androidperformancestudio.platform.perfetto.TraceAnalysisContexts
import com.androidperformancestudio.platform.perfetto.TraceProcessorTool
import com.androidperformancestudio.platform.perfetto.TraceQuerySchema
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertIs

class PerfettoDiagnosticsSchemaCompatibilityTest {
    @Test
    fun `every commercial diagnostic executes against the pinned schema when the tool is installed`() =
        runBlocking {
            val binary = installedTraceProcessor() ?: return@runBlocking
            val trace = Path.of("..", "..", "desktop-app", "src", "test", "resources", "fixtures", "synthetic-empty.pftrace")
            val tool =
                TraceProcessorTool(
                    binary,
                    TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION,
                    ArtifactFileEvidence.sha256(binary).value,
                )
            val context =
                assertIs<StudioResult.Success<TraceAnalysisContext>>(
                    TraceAnalysisContexts(tool).open(artifact(trace), trace),
                ).value
            try {
                (PerfettoDiagnostics.all + listOfNotNull(TraceSliceSearch.query("it's main"))).forEach { diagnostic ->
                    assertIs<StudioResult.Success<List<DiagnosticRow>>>(
                        context.query(diagnostic.typedQuery()),
                        diagnostic.id,
                    )
                }
            } finally {
                context.close()
            }
        }

    private fun installedTraceProcessor(): Path? {
        val binaryName =
            if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
                "trace_processor_shell.exe"
            } else {
                "trace_processor_shell"
            }
        return listOf(
            Path.of("..", "..", "build", "perfetto-tools", binaryName),
            Path.of(
                System.getProperty("user.home"),
                ".android-performance-studio",
                "tools",
                "perfetto",
                TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION,
                binaryName,
            ),
        ).map(Path::toAbsolutePath).firstOrNull(Files::isExecutable)
    }

    private fun artifact(trace: Path): CaptureArtifact =
        CaptureArtifact(
            id = ArtifactId("commercial-diagnostics-schema-fixture"),
            kind = ArtifactKind("perfetto.schema_fixture"),
            location = ArtifactLocation(trace.toAbsolutePath().normalize().toString()),
            sha256 = ArtifactFileEvidence.sha256(trace),
            provenance =
                ArtifactProvenance(
                    acquisition =
                        ArtifactAcquisition(
                            ArtifactAcquisitionKind.IMPORT,
                            "perfetto-diagnostics-schema-test",
                            performedAtEpochMillis = 0,
                        ),
                ),
        )
}
