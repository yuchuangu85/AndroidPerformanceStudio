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
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.perfetto.TraceAnalysisContexts
import com.androidperformancestudio.platform.perfetto.TraceColumn
import com.androidperformancestudio.platform.perfetto.TraceProcessorToolResolver
import com.androidperformancestudio.platform.perfetto.TraceQuery
import com.androidperformancestudio.platform.perfetto.TraceQuerySchema
import kotlinx.coroutines.CancellationException
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

internal data class HprofClassMetric(
    val heapName: String,
    val className: String,
    val objectCount: Long,
    val shallowSize: Long,
    val nativeSize: Long,
    val reachableShallowSize: Long,
    val reachableNativeSize: Long,
)

internal fun interface HprofClassMetricsAnalyzer {
    suspend fun analyze(file: Path): List<HprofClassMetric>?
}

/** Optional, source-bound class metrics; TP object IDs are not raw HPROF object IDs. */
internal class TraceProcessorHprofClassMetricsAnalyzer(
    private val resolver: TraceProcessorToolResolver = TraceProcessorToolResolver(),
) : HprofClassMetricsAnalyzer {
    private val heap = TraceColumn.string("heap_type")
    private val className = TraceColumn.string("class_name")
    private val count = TraceColumn.long("object_count")
    private val shallow = TraceColumn.long("shallow_size")
    private val native = TraceColumn.long("native_size")
    private val reachableShallow = TraceColumn.long("reachable_shallow_size")
    private val reachableNative = TraceColumn.long("reachable_native_size")
    private val query =
        TraceQuery(
            """
            SELECT o.heap_type AS heap_type,
                   CASE WHEN c.name GLOB 'java.lang.Class<*>'
                     THEN substr(c.name, length('java.lang.Class<') + 1,
                                 length(c.name) - length('java.lang.Class<') - 1)
                     ELSE c.name END AS class_name,
                   COUNT(*) AS object_count,
                   -- Studio excludes synthetic class-object self_size from Shallow, but its
                   -- Retained Size summary includes reachable class-object self_size.
                   SUM(CASE WHEN c.name GLOB 'java.lang.Class<*>' THEN 0 ELSE o.self_size END) AS shallow_size,
                   COALESCE(SUM(o.native_size), 0) AS native_size,
                   COALESCE(SUM(CASE WHEN o.reachable = 1 THEN o.self_size ELSE 0 END), 0) AS reachable_shallow_size,
                   COALESCE(SUM(CASE WHEN o.reachable = 1 THEN o.native_size ELSE 0 END), 0) AS reachable_native_size
            FROM heap_graph_object o JOIN heap_graph_class c ON c.id = o.type_id
            GROUP BY heap_type, class_name
            ORDER BY heap_type, class_name
            """.trimIndent(),
            TraceQuerySchema.v57_2(heap, className, count, shallow, native, reachableShallow, reachableNative),
        ) { row ->
            HprofClassMetric(
                heapName = requireNotNull(row[heap]),
                className = requireNotNull(row[className]),
                objectCount = requireNotNull(row[count]),
                shallowSize = requireNotNull(row[shallow]),
                nativeSize = requireNotNull(row[native]),
                reachableShallowSize = requireNotNull(row[reachableShallow]),
                reachableNativeSize = requireNotNull(row[reachableNative]),
            )
        }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun analyze(file: Path): List<HprofClassMetric>? =
        try {
            val tool = (resolver.resolve() as? StudioResult.Success)?.value ?: return null
            val context =
                (
                    TraceAnalysisContexts(tool, startupTimeout = 30.seconds)
                        .open(artifact(file), file) as? StudioResult.Success
                )?.value ?: return null
            try {
                (context.query(query) as? StudioResult.Success)?.value
            } finally {
                context.close()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private fun artifact(file: Path): CaptureArtifact =
        CaptureArtifact(
            id = ArtifactId("hprof-metrics-${java.util.UUID.randomUUID()}"),
            kind = ArtifactKind("memory.hprof.metrics"),
            location = ArtifactLocation(file.toAbsolutePath().normalize().toString()),
            sha256 = ArtifactFileEvidence.sha256(file),
            format = ArtifactFormat("hprof", "android"),
            provenance =
                ArtifactProvenance(
                    acquisition =
                        ArtifactAcquisition(
                            ArtifactAcquisitionKind.IMPORT,
                            "Android Performance Studio",
                            performedAtEpochMillis = System.currentTimeMillis(),
                        ),
                ),
        )
}
