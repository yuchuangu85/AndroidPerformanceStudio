package com.androidperformancestudio.memory.app

import com.androidperformancestudio.contracts.ArtifactAcquisition
import com.androidperformancestudio.contracts.ArtifactAcquisitionKind
import com.androidperformancestudio.contracts.ArtifactFileEvidence
import com.androidperformancestudio.contracts.ArtifactFormat
import com.androidperformancestudio.contracts.ArtifactId
import com.androidperformancestudio.contracts.ArtifactKind
import com.androidperformancestudio.contracts.ArtifactLocation
import com.androidperformancestudio.contracts.ArtifactProvenance
import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.memory.analysis.ArtSampledAllocationAnalysis
import com.androidperformancestudio.memory.analysis.ArtSampledAllocationTraceProcessorAdapter
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.perfetto.TraceAnalysisContexts
import com.androidperformancestudio.platform.perfetto.TraceProcessorToolResolver
import kotlinx.coroutines.CancellationException
import java.nio.file.Path

internal sealed interface SampledArtProcessingResult {
    data class Success(
        val analysis: ArtSampledAllocationAnalysis,
    ) : SampledArtProcessingResult

    data class Unavailable(
        val reason: String,
    ) : SampledArtProcessingResult

    data class Failure(
        val reason: String,
    ) : SampledArtProcessingResult
}

internal fun interface SampledArtArtifactAnalyzer {
    suspend fun analyze(file: Path): SampledArtProcessingResult
}

internal class PerfettoSampledArtArtifactAnalyzer(
    private val resolver: TraceProcessorToolResolver = TraceProcessorToolResolver(),
    private val adapter: ArtSampledAllocationTraceProcessorAdapter = ArtSampledAllocationTraceProcessorAdapter(),
) : SampledArtArtifactAnalyzer {
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    override suspend fun analyze(file: Path): SampledArtProcessingResult =
        try {
            val tool =
                when (val resolved = resolver.resolve()) {
                    is StudioResult.Success -> resolved.value
                    is StudioResult.Failure -> {
                        val reason = "${resolved.error.code}: ${resolved.error.message}"
                        return if (resolved.error.code in UNAVAILABLE_TOOL_ERRORS) {
                            SampledArtProcessingResult.Unavailable(reason)
                        } else {
                            SampledArtProcessingResult.Failure(reason)
                        }
                    }
                }
            val context =
                when (val opened = TraceAnalysisContexts(tool).open(captureArtifact(file), file)) {
                    is StudioResult.Success -> opened.value
                    is StudioResult.Failure -> {
                        val reason = "${opened.error.code}: ${opened.error.message}"
                        return if (opened.error.code == "TRACE_PROCESSOR_START_FAILED") {
                            SampledArtProcessingResult.Unavailable(reason)
                        } else {
                            SampledArtProcessingResult.Failure(reason)
                        }
                    }
                }
            try {
                val allocations =
                    when (val queried = context.query(adapter.allocationQuery)) {
                        is StudioResult.Success -> queried.value
                        is StudioResult.Failure ->
                            return SampledArtProcessingResult.Failure(
                                "${queried.error.code}: ${queried.error.message}",
                            )
                    }
                val stacks =
                    when (val queried = context.query(adapter.callStackQuery)) {
                        is StudioResult.Success -> queried.value
                        is StudioResult.Failure ->
                            return SampledArtProcessingResult.Failure(
                                "${queried.error.code}: ${queried.error.message}",
                            )
                    }
                SampledArtProcessingResult.Success(adapter.map(allocations, stacks))
            } finally {
                context.close()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SampledArtProcessingResult.Failure(error.message ?: error::class.simpleName.orEmpty())
        }

    private fun captureArtifact(file: Path): CaptureArtifact =
        CaptureArtifact(
            id = ArtifactId("art-sampled-${java.util.UUID.randomUUID()}"),
            kind = ArtifactKind("memory.art_sampled"),
            location = ArtifactLocation(file.toAbsolutePath().normalize().toString()),
            sha256 = ArtifactFileEvidence.sha256(file),
            format = ArtifactFormat("perfetto", "heapprofd_art"),
            provenance =
                ArtifactProvenance(
                    acquisition =
                        ArtifactAcquisition(
                            ArtifactAcquisitionKind.CAPTURE,
                            "Android Performance Studio",
                            performedAtEpochMillis = System.currentTimeMillis(),
                        ),
                ),
        )

    private companion object {
        val UNAVAILABLE_TOOL_ERRORS: Set<String> =
            setOf(
                "TRACE_PROCESSOR_HOST_UNSUPPORTED",
                "TRACE_PROCESSOR_NOT_FOUND",
                "TRACE_PROCESSOR_NOT_EXECUTABLE",
                "TRACE_PROCESSOR_CHECKSUM_MISMATCH",
                "TRACE_PROCESSOR_OVERRIDE_INVALID",
                "TRACE_PROCESSOR_OVERRIDE_PROBE_FAILED",
                "TRACE_PROCESSOR_INCOMPATIBLE",
            )
    }
}
