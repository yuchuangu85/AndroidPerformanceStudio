package com.androidperformancestudio.frame.app

import com.androidperformancestudio.contracts.ArtifactAcquisitionKind
import com.androidperformancestudio.contracts.ArtifactCompleteness
import com.androidperformancestudio.contracts.ArtifactProducer
import com.androidperformancestudio.platform.perfetto.TraceProcessorTool
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class FrameCaptureArtifactFactoryTest {
    @Test
    fun `analyzed import reports available fields without inventing requested capture intent`() {
        val trace = Files.createTempFile("frame-import", ".pftrace").also { Files.writeString(it, "trace") }
        val factory = FrameCaptureArtifactFactory { Instant.EPOCH }

        val artifact =
            factory.analyzed(
                factory.imported(trace),
                TRACE_PROCESSOR,
                com.androidperformancestudio.frame.analysis.FrameTimelineTraceAdapter.FRAME_TIMELINE_CAPABILITIES,
            )

        assertEquals(ArtifactAcquisitionKind.IMPORT, artifact.provenance.acquisition.kind)
        assertEquals(ArtifactProducer.Unknown, artifact.provenance.producer)
        assertEquals(null, artifact.requestedCapabilities)
        assertEquals(ArtifactCompleteness.UNKNOWN, artifact.completeness)
    }

    private companion object {
        val TRACE_PROCESSOR =
            TraceProcessorTool(
                path = Path.of("trace_processor_shell"),
                version = "v57.2",
                sha256 = "a".repeat(64),
            )
    }
}
