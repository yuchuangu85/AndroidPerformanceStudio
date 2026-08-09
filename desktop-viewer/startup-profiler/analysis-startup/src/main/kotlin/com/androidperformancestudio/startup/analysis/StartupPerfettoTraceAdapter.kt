package com.androidperformancestudio.startup.analysis

import com.androidperformancestudio.contracts.CapabilityId
import com.androidperformancestudio.contracts.ClockDomain
import com.androidperformancestudio.contracts.ClockMapping
import com.androidperformancestudio.platform.perfetto.TraceColumn
import com.androidperformancestudio.platform.perfetto.TraceQuery
import com.androidperformancestudio.platform.perfetto.TraceQueryResult
import com.androidperformancestudio.platform.perfetto.TraceQuerySchema
import com.androidperformancestudio.startup.model.StartupPerfettoRootCauseEvidence
import com.androidperformancestudio.startup.model.StartupPerfettoSlice

/** Startup-owned SQL and mapping; platform-perfetto never exposes startup DTOs. */
class StartupPerfettoTraceAdapter(
    private val acceptableClockErrorNs: Long = 5_000_000L,
) {
    private val ts = TraceColumn.long("ts")
    private val dur = TraceColumn.long("dur")
    private val name = TraceColumn.string("name")
    private val threadName = TraceColumn.string("thread_name")

    fun schedulingQuery(processId: Int?): TraceQuery<StartupPerfettoSlice> =
        sliceQuery(
            "SELECT s.ts, s.dur, 'sched' AS name, t.name AS thread_name " +
                "FROM sched_slice AS s JOIN thread AS t USING (utid) " +
                "JOIN process AS p USING (upid) ${processFilter(processId)} ORDER BY s.ts",
        )

    fun binderQuery(processId: Int?): TraceQuery<StartupPerfettoSlice> =
        sliceQuery(
            "SELECT s.ts, s.dur, s.name, t.name AS thread_name FROM slice AS s " +
                "JOIN thread_track AS tt ON tt.id = s.track_id " +
                "JOIN thread AS t ON t.utid = tt.utid JOIN process AS p USING (upid) " +
                "WHERE (s.name GLOB '*binder*' OR s.name GLOB '*Binder*') " +
                processPredicate(processId) + " ORDER BY s.ts",
        )

    fun mainThreadQuery(processId: Int?): TraceQuery<StartupPerfettoSlice> =
        sliceQuery(
            "SELECT s.ts, s.dur, s.name, t.name AS thread_name FROM slice AS s " +
                "LEFT JOIN thread_track AS tt ON tt.id = s.track_id " +
                "LEFT JOIN thread AS t ON t.utid = tt.utid " +
                "JOIN process AS p USING (upid) WHERE t.is_main_thread = 1 " +
                processPredicate(processId) + " ORDER BY s.ts",
        )

    fun frameQuery(processId: Int?): TraceQuery<StartupPerfettoSlice> =
        sliceQuery(
            "SELECT a.ts, a.dur, a.name, NULL AS thread_name FROM actual_frame_timeline_slice AS a " +
                "JOIN process AS p USING (upid) ${processFilter(processId)} ORDER BY a.ts",
        )

    fun mapFixture(
        schedulingCsv: String,
        binderCsv: String,
        mainThreadCsv: String,
        frameCsv: String,
        clockMapping: ClockMapping? = null,
    ): StartupPerfettoRootCauseEvidence =
        map(
            schedulingQuery(null).map(TraceQueryResult.parse(schedulingCsv)),
            binderQuery(null).map(TraceQueryResult.parse(binderCsv)),
            mainThreadQuery(null).map(TraceQueryResult.parse(mainThreadCsv)),
            frameQuery(null).map(TraceQueryResult.parse(frameCsv)),
            clockMapping,
        )

    fun map(
        scheduling: List<StartupPerfettoSlice>,
        binder: List<StartupPerfettoSlice>,
        mainThread: List<StartupPerfettoSlice>,
        frames: List<StartupPerfettoSlice>,
        clockMapping: ClockMapping? = null,
    ): StartupPerfettoRootCauseEvidence {
        val timestamps = scheduling + binder + mainThread + frames
        val error = clockMapping?.errorBoundNanos
        val correlated = clockMapping?.isAcceptableFor(timestamps) == true
        return StartupPerfettoRootCauseEvidence(
            schedulingSlices = scheduling,
            binderSlices = binder,
            mainThreadSlices = mainThread,
            frameSlices = frames,
            correlated = correlated,
            correlationErrorBoundNs = error,
            limitations =
                if (correlated) {
                    emptyList()
                } else {
                    listOf("Perfetto evidence is not correlated: no acceptable Clock Mapping was provided.")
                },
        )
    }

    private fun sliceQuery(sql: String): TraceQuery<StartupPerfettoSlice> =
        TraceQuery(
            sql,
            TraceQuerySchema.v57_2(ts, dur, name, threadName),
        ) { row ->
            StartupPerfettoSlice(
                timestampNs = requireNotNull(row[ts]),
                durationNs = row[dur] ?: 0L,
                name = row[name].orEmpty(),
                threadName = row[threadName],
            )
        }

    private fun processFilter(processId: Int?): String {
        if (processId == null) return "WHERE p.pid IS NOT NULL"
        require(processId > 0) { "startup process id must be positive" }
        return "WHERE p.pid = $processId"
    }

    private fun processPredicate(processId: Int?): String {
        if (processId == null) return "AND p.pid IS NOT NULL"
        require(processId > 0) { "startup process id must be positive" }
        return "AND p.pid = $processId"
    }

    private fun ClockMapping.isAcceptableFor(slices: List<StartupPerfettoSlice>): Boolean {
        val first = slices.minOfOrNull(StartupPerfettoSlice::timestampNs)
        val last = slices.maxOfOrNull { it.timestampNs + it.durationNs.coerceAtLeast(0L) }
        val validFrom = validFromSourceNanos
        val validTo = validToSourceNanos
        return source == PERFETTO_TRACE_CLOCK &&
            target == STARTUP_ELAPSED_REALTIME_CLOCK &&
            errorBoundNanos <= acceptableClockErrorNs &&
            first != null &&
            last != null &&
            (validFrom == null || first >= validFrom) &&
            (validTo == null || last <= validTo)
    }

    companion object {
        val PERFETTO_TRACE_CLOCK = ClockDomain("perfetto.trace_time")
        val STARTUP_ELAPSED_REALTIME_CLOCK = ClockDomain("android.elapsed_realtime")
        val SCHEDULING = CapabilityId("startup.scheduling")
        val BINDER = CapabilityId("startup.binder")
        val MAIN_THREAD = CapabilityId("startup.main_thread")
        val FRAME = CapabilityId("startup.frame")
        val ALL: Set<CapabilityId> = setOf(SCHEDULING, BINDER, MAIN_THREAD, FRAME)
    }
}
