@file:Suppress("MagicNumber")

package com.androidperformancestudio.perfetto.analysis

enum class DiagnosticFindingLevel {
    CRITICAL,
    WARNING,
    INFO,

    /** The query ran but the trace recorded no rows for it. */
    NO_DATA,
}

/** The aggregate a finding is derived from; the UI localizes it. */
enum class DiagnosticMetricKind {
    JANKY_FRAMES,
    MAX_LATENCY_MS,
    MAX_BLOCKED_MS,
    MAX_WAKEUP_US,
    PEAK_RSS_KB,
    TOP_CPU_MS,
    TOP_FREQUENCY_MHZ,
    TOP_STATE_MS,
    ROWS,
}

data class DiagnosticMetric(
    val kind: DiagnosticMetricKind,
    val value: Double,
    val total: Double? = null,
    val subject: String? = null,
)

data class DiagnosticFinding(
    val level: DiagnosticFindingLevel,
    val metric: DiagnosticMetric,
    /** True when the level came from an APS threshold rather than a trace-provided classification. */
    val heuristic: Boolean,
)

/**
 * Derives a severity and headline aggregate from the rows a diagnostic actually returned.
 * Thresholds are fixed APS heuristics; they never invent values the query did not report.
 */
object DiagnosticFindings {
    const val JANK_CRITICAL_RATIO = 0.05
    const val BINDER_CRITICAL_MS = 100.0
    const val BINDER_WARNING_MS = 16.0
    const val BLOCKED_CRITICAL_MS = 500.0
    const val BLOCKED_WARNING_MS = 100.0
    const val INPUT_CRITICAL_MS = 200.0
    const val INPUT_WARNING_MS = 100.0
    const val WAKEUP_WARNING_US = 5_000.0

    fun evaluate(
        query: DiagnosticQuery,
        result: DiagnosticResult,
    ): DiagnosticFinding {
        if (result.rows.isEmpty()) {
            return DiagnosticFinding(DiagnosticFindingLevel.NO_DATA, DiagnosticMetric(DiagnosticMetricKind.ROWS, 0.0), false)
        }
        return when (query.id) {
            "frame_jank" -> frameJank(result)
            "binder_latency" ->
                maxThreshold(
                    result,
                    "max_dur_ms",
                    "client",
                    DiagnosticMetricKind.MAX_LATENCY_MS,
                    BINDER_CRITICAL_MS,
                    BINDER_WARNING_MS,
                )
            "main_thread_blocked" ->
                maxThreshold(
                    result,
                    "max_blocked_ms",
                    "thread_name",
                    DiagnosticMetricKind.MAX_BLOCKED_MS,
                    BLOCKED_CRITICAL_MS,
                    BLOCKED_WARNING_MS,
                )
            "input_latency" ->
                maxThreshold(
                    result,
                    "max_dur_ms",
                    "process_name",
                    DiagnosticMetricKind.MAX_LATENCY_MS,
                    INPUT_CRITICAL_MS,
                    INPUT_WARNING_MS,
                )
            "wakeup_latency" ->
                maxThreshold(result, "max_latency_us", "thread_name", DiagnosticMetricKind.MAX_WAKEUP_US, null, WAKEUP_WARNING_US)
            "mem_counters" -> informational(result, "max_kb", "process_name", DiagnosticMetricKind.PEAK_RSS_KB)
            "cpu_hotspots" -> informational(result, "total_dur_ms", "thread_name", DiagnosticMetricKind.TOP_CPU_MS)
            "cpu_freq_dist" -> informational(result, "freq_mhz", "cpu", DiagnosticMetricKind.TOP_FREQUENCY_MHZ, firstRow = true)
            "thread_states" -> informational(result, "total_dur_ms", "scheduler_state", DiagnosticMetricKind.TOP_STATE_MS, firstRow = true)
            else -> rows(result)
        }
    }

    private fun frameJank(result: DiagnosticResult): DiagnosticFinding {
        val janky = result.sum("janky_frames")
        val total = result.sum("frame_count")
        if (janky == null || total == null || total <= 0.0) return rows(result)
        val level =
            when {
                janky / total >= JANK_CRITICAL_RATIO -> DiagnosticFindingLevel.CRITICAL
                janky > 0.0 -> DiagnosticFindingLevel.WARNING
                else -> DiagnosticFindingLevel.INFO
            }
        // was_jank is classified by FrameTimeline itself; only the critical ratio is an APS threshold.
        return DiagnosticFinding(level, DiagnosticMetric(DiagnosticMetricKind.JANKY_FRAMES, janky, total), heuristic = true)
    }

    @Suppress("LongParameterList")
    private fun maxThreshold(
        result: DiagnosticResult,
        column: String,
        subjectColumn: String,
        kind: DiagnosticMetricKind,
        critical: Double?,
        warning: Double,
    ): DiagnosticFinding {
        val (value, subject) = result.maxWithSubject(column, subjectColumn) ?: return rows(result)
        val level =
            when {
                critical != null && value >= critical -> DiagnosticFindingLevel.CRITICAL
                value >= warning -> DiagnosticFindingLevel.WARNING
                else -> DiagnosticFindingLevel.INFO
            }
        return DiagnosticFinding(level, DiagnosticMetric(kind, value, subject = subject), heuristic = true)
    }

    private fun informational(
        result: DiagnosticResult,
        column: String,
        subjectColumn: String,
        kind: DiagnosticMetricKind,
        firstRow: Boolean = false,
    ): DiagnosticFinding {
        val pair =
            if (firstRow) {
                val row = result.rows.first()
                result.number(row, column)?.let { it to result.text(row, subjectColumn) }
            } else {
                result.maxWithSubject(column, subjectColumn)
            } ?: return rows(result)
        return DiagnosticFinding(DiagnosticFindingLevel.INFO, DiagnosticMetric(kind, pair.first, subject = pair.second), heuristic = false)
    }

    private fun rows(result: DiagnosticResult): DiagnosticFinding =
        DiagnosticFinding(
            DiagnosticFindingLevel.INFO,
            DiagnosticMetric(DiagnosticMetricKind.ROWS, result.rows.size.toDouble()),
            heuristic = false,
        )

    private fun DiagnosticResult.sum(column: String): Double? {
        val index = columns.indexOf(column)
        if (index < 0) return null
        val values = rows.mapNotNull { row -> row.values.getOrNull(index)?.toDoubleOrNull() }
        return if (values.isEmpty()) null else values.sum()
    }

    private fun DiagnosticResult.maxWithSubject(
        column: String,
        subjectColumn: String,
    ): Pair<Double, String?>? =
        rows
            .mapNotNull { row -> number(row, column)?.let { it to text(row, subjectColumn) } }
            .maxByOrNull { it.first }

    private fun DiagnosticResult.number(
        row: DiagnosticRow,
        column: String,
    ): Double? = columns.indexOf(column).takeIf { it >= 0 }?.let { row.values.getOrNull(it)?.toDoubleOrNull() }

    private fun DiagnosticResult.text(
        row: DiagnosticRow,
        column: String,
    ): String? =
        columns
            .indexOf(column)
            .takeIf { it >= 0 }
            ?.let { row.values.getOrNull(it) }
            ?.takeIf(String::isNotBlank)
}

/** Builds a bounded, read-only slice name search; returns null when the term is blank. */
object TraceSliceSearch {
    const val MAX_TERM_LENGTH = 120
    const val RESULT_LIMIT = 200
    val columns = listOf("timestamp_ns", "duration_ns", "slice_name", "thread_name", "process_name")

    fun query(term: String): DiagnosticQuery? {
        val sanitized =
            term
                .filterNot(Char::isISOControl)
                .trim()
                .take(MAX_TERM_LENGTH)
        if (sanitized.isEmpty()) return null
        // The term is embedded as a single-quoted SQLite literal; doubling quotes is the complete escape.
        val literal = sanitized.lowercase().replace("'", "''")
        return DiagnosticQuery(
            id = "slice_search",
            category = DiagnosticCategory.CPU,
            title = "Slice search",
            description = "Slices whose name contains the search term",
            columns = columns,
            sql =
                """
                SELECT slice.ts AS timestamp_ns,
                       slice.dur AS duration_ns,
                       slice.name AS slice_name,
                       thread.name AS thread_name,
                       process.name AS process_name
                FROM slice
                LEFT JOIN thread_track ON thread_track.id = slice.track_id
                LEFT JOIN thread ON thread.utid = thread_track.utid
                LEFT JOIN process ON process.upid = thread.upid
                WHERE instr(lower(slice.name), '$literal') > 0
                ORDER BY slice.dur DESC
                LIMIT $RESULT_LIMIT
                """.trimIndent(),
        )
    }
}
