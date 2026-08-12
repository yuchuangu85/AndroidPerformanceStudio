@file:Suppress("MaxLineLength", "ktlint:standard:max-line-length")

package com.androidperformancestudio.winscope.analysis

import com.androidperformancestudio.contracts.ArtifactAcquisition
import com.androidperformancestudio.contracts.ArtifactAcquisitionKind
import com.androidperformancestudio.contracts.ArtifactFileEvidence
import com.androidperformancestudio.contracts.ArtifactId
import com.androidperformancestudio.contracts.ArtifactKind
import com.androidperformancestudio.contracts.ArtifactLocation
import com.androidperformancestudio.contracts.ArtifactProvenance
import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.perfetto.TraceAnalysisContext
import com.androidperformancestudio.platform.perfetto.TraceAnalysisContexts
import com.androidperformancestudio.platform.perfetto.TraceProcessorToolResolver
import com.androidperformancestudio.platform.perfetto.TraceQueryResult
import com.androidperformancestudio.winscope.model.WinscopeLogRow
import com.androidperformancestudio.winscope.model.WinscopeNode
import com.androidperformancestudio.winscope.model.WinscopeProperty
import com.androidperformancestudio.winscope.model.WinscopeQueryResult
import com.androidperformancestudio.winscope.model.WinscopeRect
import com.androidperformancestudio.winscope.model.WinscopeSession
import com.androidperformancestudio.winscope.model.WinscopeSource
import com.androidperformancestudio.winscope.model.WinscopeState
import com.androidperformancestudio.winscope.model.WinscopeTimeline
import com.androidperformancestudio.winscope.model.WinscopeTimelineEntry
import com.androidperformancestudio.winscope.model.WinscopeTraceBounds
import java.time.Instant
import java.util.UUID

class WinscopeAnalyzer private constructor(
    private val context: TraceAnalysisContext,
) : AutoCloseable {
    suspend fun probeSources(): StudioResult<Set<WinscopeSource>> =
        query(INCLUDE_MODULES + "\nSELECT name FROM sqlite_master WHERE type IN ('table','view')").map { result ->
            val names = result.rows.mapNotNull { it.string("name") }.toSet()
            SOURCE_TABLES.filterValues { candidates -> candidates.any(names::contains) }.keys
        }

    suspend fun timeline(): StudioResult<WinscopeTimeline> {
        val boundsResult = query("SELECT start_ts, end_ts FROM trace_bounds")
        if (boundsResult is StudioResult.Failure) return boundsResult
        val boundsRow = (boundsResult as StudioResult.Success).value.rows.firstOrNull()
        val traceBounds = WinscopeTraceBounds(boundsRow?.long("start_ts") ?: 0, boundsRow?.long("end_ts") ?: 0)
        val available =
            when (val probed = probeSources()) {
                is StudioResult.Success -> probed.value
                is StudioResult.Failure -> return probed
            }
        val entries = linkedMapOf<WinscopeSource, List<WinscopeTimelineEntry>>()
        available.forEach { source ->
            timelineSql(source)?.let { sql ->
                when (val rows = query(INCLUDE_MODULES + "\n" + sql)) {
                    is StudioResult.Success -> entries[source] = rows.value.rows.mapNotNull { it.toTimeline(source) }
                    is StudioResult.Failure -> Unit
                }
            }
        }
        val evidenceTimestamps = entries.values.flatten().map(WinscopeTimelineEntry::timestampNanos)
        val bounds =
            if (evidenceTimestamps.isEmpty()) {
                traceBounds
            } else {
                val start = evidenceTimestamps.min()
                WinscopeTraceBounds(start, maxOf(start + 1, evidenceTimestamps.max()))
            }
        return StudioResult.Success(WinscopeTimeline(bounds, entries))
    }

    suspend fun state(
        source: WinscopeSource,
        timestampNanos: Long,
    ): StudioResult<WinscopeState> {
        val sql =
            stateSql(source, timestampNanos)
                ?: return failure("WINSCOPE_STATE_UNAVAILABLE", "${source.displayName} has no hierarchy state")
        return query(INCLUDE_MODULES + "\n" + sql).map { result ->
            val groups = result.rows.groupBy { it.string("node_id").orEmpty() }
            val nodes = groups.mapNotNull { (id, rows) -> rows.firstOrNull()?.toNode(id, rows) }
            WinscopeState(source, timestampNanos, nodes)
        }
    }

    suspend fun logs(
        source: WinscopeSource,
        startNanos: Long,
        endNanos: Long,
    ): StudioResult<List<WinscopeLogRow>> {
        val sql =
            when (source) {
                WinscopeSource.PROTO_LOG ->
                    "SELECT id,ts,level,tag,message,stacktrace,location FROM protolog WHERE ts BETWEEN $startNanos AND $endNanos ORDER BY ts LIMIT $ROW_LIMIT"
                WinscopeSource.IME ->
                    "SELECT id,ts,'IME' AS kind,display_value AS detail FROM android_inputmethod_service LEFT JOIN args USING(arg_set_id) WHERE ts BETWEEN $startNanos AND $endNanos ORDER BY ts LIMIT $ROW_LIMIT"
                WinscopeSource.INPUT ->
                    "SELECT id,event_id AS ts,vsync_id,window_id,display_value AS detail FROM __intrinsic_android_input_event_dispatch LEFT JOIN args USING(arg_set_id) LIMIT $ROW_LIMIT"
                WinscopeSource.EVENT_LOG ->
                    "SELECT id,ts,CAST(prio AS TEXT) AS level,tag,msg AS message FROM android_logs WHERE ts BETWEEN $startNanos AND $endNanos ORDER BY ts LIMIT $ROW_LIMIT"
                else -> return failure("WINSCOPE_LOG_UNAVAILABLE", "${source.displayName} is not a log source")
            }
        return query(INCLUDE_MODULES + "\n" + sql).map { result ->
            result.rows.mapIndexed { index, row ->
                val columns = result.columns.associateWith { row.string(it).orEmpty() }
                WinscopeLogRow(row.long("id") ?: index.toLong(), source, row.long("ts") ?: 0, columns)
            }
        }
    }

    suspend fun runReadOnlySql(sql: String): StudioResult<WinscopeQueryResult> {
        val validated = ReadOnlyTraceSql.validate(sql)
        if (validated is StudioResult.Failure) return validated
        return query((validated as StudioResult.Success).value).map { result ->
            val timestamps = result.rows.mapNotNull { it.long("ts") }
            WinscopeQueryResult(
                result.columns,
                result.rows.map { row -> result.columns.map(row::string) },
                timestamps,
            )
        }
    }

    private suspend fun query(sql: String): StudioResult<TraceQueryResult> = context.queryRaw(sql)

    override fun close() = context.close()

    companion object {
        suspend fun open(session: WinscopeSession): StudioResult<WinscopeAnalyzer> {
            val tool =
                when (val resolved = TraceProcessorToolResolver().resolve()) {
                    is StudioResult.Success -> resolved.value
                    is StudioResult.Failure -> return resolved
                }
            val artifact =
                CaptureArtifact(
                    id = ArtifactId("winscope-${session.id}-${UUID.randomUUID()}"),
                    kind = ArtifactKind("perfetto.trace"),
                    location =
                        ArtifactLocation(
                            session.traceFile
                                .toAbsolutePath()
                                .normalize()
                                .toString(),
                        ),
                    sha256 = ArtifactFileEvidence.sha256(session.traceFile),
                    provenance =
                        ArtifactProvenance(
                            acquisition =
                                ArtifactAcquisition(
                                    ArtifactAcquisitionKind.IMPORT,
                                    "android-performance-studio-winscope",
                                    performedAtEpochMillis = Instant.now().toEpochMilli(),
                                ),
                        ),
                )
            return when (val opened = TraceAnalysisContexts(tool).open(artifact, session.traceFile)) {
                is StudioResult.Success -> StudioResult.Success(WinscopeAnalyzer(opened.value))
                is StudioResult.Failure -> opened
            }
        }

        private const val ROW_LIMIT = 50_000
        private const val INCLUDE_MODULES = """
            INCLUDE PERFETTO MODULE android.winscope.windowmanager;
            INCLUDE PERFETTO MODULE android.winscope.inputmethod;
            INCLUDE PERFETTO MODULE android.winscope.viewcapture;
        """
        private val SOURCE_TABLES =
            mapOf(
                WinscopeSource.WINDOW_MANAGER to setOf("android_windowmanager"),
                WinscopeSource.SURFACE_FLINGER to setOf("surfaceflinger_layers_snapshot"),
                WinscopeSource.TRANSACTIONS to setOf("surfaceflinger_transactions"),
                WinscopeSource.TRANSITIONS to setOf("window_manager_shell_transitions"),
                WinscopeSource.EVENT_LOG to setOf("android_logs", "android_log"),
                WinscopeSource.INPUT to setOf("__intrinsic_android_input_event_dispatch"),
                WinscopeSource.IME to setOf("android_inputmethod_service", "android_inputmethod_manager_service"),
                WinscopeSource.VIEW_CAPTURE to setOf("android_viewcapture"),
                WinscopeSource.PROTO_LOG to setOf("protolog"),
            )

        private fun timelineSql(source: WinscopeSource): String? =
            when (source) {
                WinscopeSource.WINDOW_MANAGER -> "SELECT id,ts,NULL AS dur,'WindowManager snapshot' AS label FROM android_windowmanager ORDER BY ts LIMIT $ROW_LIMIT"
                WinscopeSource.SURFACE_FLINGER -> "SELECT id,ts,NULL AS dur,'SurfaceFlinger snapshot' AS label FROM surfaceflinger_layers_snapshot ORDER BY ts LIMIT $ROW_LIMIT"
                WinscopeSource.TRANSACTIONS -> "SELECT id,ts,NULL AS dur,'Transaction' AS label FROM surfaceflinger_transactions ORDER BY ts LIMIT $ROW_LIMIT"
                WinscopeSource.TRANSITIONS -> "SELECT id,ts,duration_ns AS dur,'Transition ' || transition_id || ' · ' || status AS label FROM window_manager_shell_transitions ORDER BY ts LIMIT $ROW_LIMIT"
                WinscopeSource.PROTO_LOG -> "SELECT id,ts,NULL AS dur,level || ' ' || tag AS label,level AS severity FROM protolog ORDER BY ts LIMIT $ROW_LIMIT"
                WinscopeSource.EVENT_LOG -> "SELECT id,ts,NULL AS dur,tag || ' ' || msg AS label,CAST(prio AS TEXT) AS severity FROM android_logs ORDER BY ts LIMIT $ROW_LIMIT"
                WinscopeSource.IME -> "SELECT id,ts,NULL AS dur,'IME' AS label FROM android_inputmethod_service ORDER BY ts LIMIT $ROW_LIMIT"
                WinscopeSource.VIEW_CAPTURE -> "SELECT id,ts,NULL AS dur,package_name || ' · ' || window_name AS label FROM android_viewcapture ORDER BY ts LIMIT $ROW_LIMIT"
                else -> null
            }

        private fun stateSql(
            source: WinscopeSource,
            ts: Long,
        ): String? =
            when (source) {
                WinscopeSource.WINDOW_MANAGER ->
                    """
                    WITH snapshot AS (SELECT id FROM android_windowmanager WHERE ts <= $ts ORDER BY ts DESC LIMIT 1)
                    SELECT CAST(n.token AS TEXT) node_id, CAST(n.parent_token AS TEXT) parent_id,
                           COALESCE(n.name_override,n.title,'WindowContainer') name,n.container_type type,n.is_visible visible,
                           CAST(n.child_index AS REAL) z,r.x,r.y,r.w,r.h,a.key property_key,a.display_value property_value
                    FROM android_windowmanager_windowcontainer n
                    LEFT JOIN __intrinsic_winscope_rect r ON r.id=n.window_rect_id
                    LEFT JOIN args a ON a.arg_set_id=n.arg_set_id
                    WHERE n.snapshot_id=(SELECT id FROM snapshot) ORDER BY n.child_index LIMIT $ROW_LIMIT
                    """.trimIndent()
                WinscopeSource.SURFACE_FLINGER ->
                    """
                    WITH snapshot AS (SELECT id FROM surfaceflinger_layers_snapshot WHERE ts <= $ts ORDER BY ts DESC LIMIT 1)
                    SELECT CAST(n.layer_id AS TEXT) node_id,CAST(n.parent AS TEXT) parent_id,n.layer_name name,
                           'Layer' type,n.is_visible visible,CAST(n.layer_id AS REAL) z,r.x,r.y,r.w,r.h,
                           a.key property_key,a.display_value property_value
                    FROM surfaceflinger_layer n
                    LEFT JOIN __intrinsic_winscope_rect r ON r.id=n.layer_rect_id
                    LEFT JOIN args a ON a.arg_set_id=n.arg_set_id
                    WHERE n.snapshot_id=(SELECT id FROM snapshot) ORDER BY n.layer_id LIMIT $ROW_LIMIT
                    """.trimIndent()
                WinscopeSource.VIEW_CAPTURE ->
                    """
                    WITH snapshot AS (SELECT id FROM android_viewcapture WHERE ts <= $ts ORDER BY ts DESC LIMIT 1)
                    SELECT CAST(n.node_id AS TEXT) node_id,CAST(n.parent_id AS TEXT) parent_id,
                           COALESCE(NULLIF(n.view_id,''),n.class_name,'View') name,n.class_name type,n.is_visible visible,
                           CAST(t.depth AS REAL) z,r.x,r.y,r.w,r.h,a.key property_key,a.display_value property_value
                    FROM android_viewcapture_view n
                    LEFT JOIN __intrinsic_winscope_trace_rect t ON t.id=n.trace_rect_id
                    LEFT JOIN __intrinsic_winscope_rect r ON r.id=t.rect_id
                    LEFT JOIN args a ON a.arg_set_id=n.arg_set_id
                    WHERE n.snapshot_id=(SELECT id FROM snapshot) ORDER BY t.depth LIMIT $ROW_LIMIT
                    """.trimIndent()
                else -> null
            }

        private fun com.androidperformancestudio.platform.perfetto.TraceQueryRow.toTimeline(
            source: WinscopeSource,
        ): WinscopeTimelineEntry? {
            val ts = long("ts") ?: return null
            val duration = long("dur")?.takeIf { it >= 0 }
            return WinscopeTimelineEntry(
                id = long("id") ?: ts,
                source = source,
                timestampNanos = ts,
                endNanos = duration?.let(ts::plus),
                label = string("label") ?: source.displayName,
                severity = string("severity"),
            )
        }

        private fun com.androidperformancestudio.platform.perfetto.TraceQueryRow.toNode(
            id: String,
            rows: List<com.androidperformancestudio.platform.perfetto.TraceQueryRow>,
        ): WinscopeNode? {
            if (id.isBlank()) return null
            val width = double("w")?.toFloat()
            val height = double("h")?.toFloat()
            val x = double("x")?.toFloat()
            val y = double("y")?.toFloat()
            val properties =
                rows
                    .mapNotNull { row ->
                        row.string("property_key")?.let { WinscopeProperty(it, row.string("property_value")) }
                    }.distinctBy(WinscopeProperty::path)
            return WinscopeNode(
                id = id,
                parentId = string("parent_id")?.takeUnless { it == "0" },
                name = string("name") ?: id,
                type = string("type") ?: "Node",
                visible = long("visible")?.let { it != 0L },
                z = double("z")?.toFloat() ?: 0f,
                bounds = if (x != null && y != null && width != null && height != null) WinscopeRect(x, y, x + width, y + height) else null,
                properties = properties,
            )
        }
    }
}

object ReadOnlyTraceSql {
    private val forbidden =
        Regex("\\b(ATTACH|DETACH|INSERT|UPDATE|DELETE|REPLACE|CREATE|DROP|ALTER|VACUUM|PRAGMA|REINDEX|ANALYZE)\\b", RegexOption.IGNORE_CASE)

    fun validate(sql: String): StudioResult<String> {
        val stripped = stripComments(sql).trim().removeSuffix(";").trim()
        if (stripped.isBlank()) return failure("WINSCOPE_SQL_EMPTY", "Enter a SELECT or WITH query")
        if (stripped.contains(';')) return failure("WINSCOPE_SQL_MULTIPLE", "Only one SQL statement is allowed")
        val first = stripped.substringBefore(' ').substringBefore('\n').uppercase()
        if (first !in setOf("SELECT", "WITH")) return failure("WINSCOPE_SQL_READ_ONLY", "Only SELECT or WITH queries are allowed")
        if (forbidden.containsMatchIn(maskStrings(stripped))) {
            return failure("WINSCOPE_SQL_READ_ONLY", "The query contains a non-read-only operation")
        }
        return StudioResult.Success(stripped)
    }

    private fun stripComments(sql: String): String = sql.lineSequence().joinToString("\n") { it.substringBefore("--") }

    private fun maskStrings(sql: String): String {
        val result = StringBuilder(sql.length)
        var quoted = false
        sql.forEach { character ->
            if (character == '\'') quoted = !quoted
            result.append(if (quoted) ' ' else character)
        }
        return result.toString()
    }
}

private fun <T, R> StudioResult<T>.map(transform: (T) -> R): StudioResult<R> =
    when (this) {
        is StudioResult.Success -> StudioResult.Success(transform(value))
        is StudioResult.Failure -> this
    }

private fun <T> failure(
    code: String,
    message: String,
): StudioResult<T> = StudioResult.Failure(StudioError(ErrorCategory.DATA_VALIDATION, code, message))
