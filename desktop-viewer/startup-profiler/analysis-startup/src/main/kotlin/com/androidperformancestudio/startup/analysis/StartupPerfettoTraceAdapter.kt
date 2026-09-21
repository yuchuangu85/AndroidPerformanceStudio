@file:Suppress("TooManyFunctions")

package com.androidperformancestudio.startup.analysis

import com.androidperformancestudio.contracts.CapabilityId
import com.androidperformancestudio.contracts.ClockDomain
import com.androidperformancestudio.contracts.ClockMapping
import com.androidperformancestudio.platform.perfetto.TraceColumn
import com.androidperformancestudio.platform.perfetto.TraceQuery
import com.androidperformancestudio.platform.perfetto.TraceQueryResult
import com.androidperformancestudio.platform.perfetto.TraceQuerySchema
import com.androidperformancestudio.startup.model.StartupMilestone
import com.androidperformancestudio.startup.model.StartupPerfettoPhaseAttribution
import com.androidperformancestudio.startup.model.StartupPerfettoRootCauseEvidence
import com.androidperformancestudio.startup.model.StartupPerfettoSlice

data class StartupPerfettoEvidenceSlices(
    val scheduling: List<StartupPerfettoSlice>,
    val binder: List<StartupPerfettoSlice>,
    val mainThread: List<StartupPerfettoSlice>,
    val frames: List<StartupPerfettoSlice>,
    val waking: List<StartupPerfettoSlice> = emptyList(),
    val runQueue: List<StartupPerfettoSlice> = emptyList(),
    val gc: List<StartupPerfettoSlice> = emptyList(),
    val jit: List<StartupPerfettoSlice> = emptyList(),
    val classLoading: List<StartupPerfettoSlice> = emptyList(),
    val classVerification: List<StartupPerfettoSlice> = emptyList(),
) {
    fun allSlices(): List<StartupPerfettoSlice> =
        scheduling + binder + mainThread + frames + waking + runQueue + gc + jit + classLoading + classVerification
}

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

    fun wakingQuery(processId: Int?): TraceQuery<StartupPerfettoSlice> =
        sliceQuery(
            "WITH waking AS (" +
                "SELECT e.id, e.ts, e.utid AS waker_utid, " +
                "MAX(CASE WHEN args.key = 'pid' THEN args.int_value END) AS target_tid " +
                "FROM ftrace_event AS e LEFT JOIN args ON args.arg_set_id = e.arg_set_id " +
                "WHERE e.name = 'sched_waking' GROUP BY e.id, e.ts, e.utid) " +
                "SELECT waking.ts, 0 AS dur, " +
                "'sched_waking:' || COALESCE(waker.name, 'unknown') AS name, target.name AS thread_name " +
                "FROM waking LEFT JOIN thread AS waker ON waker.utid = waking.waker_utid " +
                "LEFT JOIN thread AS target ON target.tid = waking.target_tid " +
                "AND (target.start_ts IS NULL OR target.start_ts <= waking.ts) " +
                "AND (target.end_ts IS NULL OR waking.ts < target.end_ts) " +
                wakingTargetFilter(processId) + " ORDER BY waking.ts",
        )

    fun runQueueQuery(): TraceQuery<StartupPerfettoSlice> =
        sliceQuery(
            "INCLUDE PERFETTO MODULE sched.thread_level_parallelism; " +
                "SELECT ts, 0 AS dur, 'run_queue:' || runnable_thread_count AS name, NULL AS thread_name " +
                "FROM sched_runnable_thread_count WHERE runnable_thread_count > 0 ORDER BY ts",
        )

    fun gcQuery(processId: Int?): TraceQuery<StartupPerfettoSlice> =
        sliceQuery(
            "INCLUDE PERFETTO MODULE android.garbage_collection; " +
                "SELECT gc_ts AS ts, gc_dur AS dur, gc_type AS name, thread_name " +
                "FROM android_garbage_collection_events " +
                processFilterForUpid(processId) + " ORDER BY gc_ts",
        )

    fun jitQuery(processId: Int?): TraceQuery<StartupPerfettoSlice> =
        runtimeSliceQuery(processId, "LOWER(s.name) GLOB '*jit*compil*' OR LOWER(s.name) GLOB '*dex2oat*'")

    @Suppress("MaxLineLength", "ktlint:standard:max-line-length")
    fun classLoadingQuery(processId: Int?): TraceQuery<StartupPerfettoSlice> = runtimeSliceQuery(processId, "s.name GLOB 'L*;'")

    fun classVerificationQuery(processId: Int?): TraceQuery<StartupPerfettoSlice> =
        runtimeSliceQuery(
            processId,
            "LOWER(s.name) GLOB '*verifyclass*' OR LOWER(s.name) GLOB '*classlinker*' " +
                "OR LOWER(s.name) GLOB '*initializeclass*' OR LOWER(s.name) GLOB '*<clinit>*'",
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
            "SELECT state.ts, state.dur, COALESCE(state.blocked_function, state.state) AS name, " +
                "thread.name AS thread_name FROM thread_state AS state " +
                "JOIN thread ON thread.utid = state.utid JOIN process AS p USING (upid) " +
                "WHERE thread.is_main_thread = 1 AND state.state IN ('S', 'D', 'S+') " +
                processPredicate(processId) + " ORDER BY state.ts",
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
            StartupPerfettoEvidenceSlices(
                scheduling = schedulingQuery(null).map(TraceQueryResult.parse(schedulingCsv)),
                binder = binderQuery(null).map(TraceQueryResult.parse(binderCsv)),
                mainThread = mainThreadQuery(null).map(TraceQueryResult.parse(mainThreadCsv)),
                frames = frameQuery(null).map(TraceQueryResult.parse(frameCsv)),
            ),
            clockMapping,
        )

    fun map(
        evidence: StartupPerfettoEvidenceSlices,
        clockMapping: ClockMapping? = null,
        milestones: List<StartupMilestone> = emptyList(),
    ): StartupPerfettoRootCauseEvidence {
        val timestamps = evidence.allSlices()
        val error = clockMapping?.errorBoundNanos
        val correlated = clockMapping?.isAcceptableFor(timestamps) == true
        return StartupPerfettoRootCauseEvidence(
            schedulingSlices = evidence.scheduling,
            binderSlices = evidence.binder,
            mainThreadSlices = evidence.mainThread,
            frameSlices = evidence.frames,
            wakingSlices = evidence.waking,
            runQueueSlices = evidence.runQueue,
            gcSlices = evidence.gc,
            jitSlices = evidence.jit,
            classLoadingSlices = evidence.classLoading,
            classVerificationSlices = evidence.classVerification,
            phaseAttributions =
                if (correlated) {
                    attributePhases(evidence, milestones, clockMapping)
                } else {
                    emptyList()
                },
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

    private fun wakingTargetFilter(processId: Int?): String {
        if (processId == null) return "WHERE target.utid IS NOT NULL"
        require(processId > 0) { "startup process id must be positive" }
        return "WHERE target.upid = (SELECT upid FROM process WHERE pid = $processId ORDER BY start_ts DESC LIMIT 1)"
    }

    private fun attributePhases(
        evidence: StartupPerfettoEvidenceSlices,
        milestones: List<StartupMilestone>,
        mapping: ClockMapping?,
    ): List<StartupPerfettoPhaseAttribution> =
        milestones.zipWithNext().mapNotNull { (start, end) ->
            val startNs = start.elapsedRealtimeNs ?: return@mapNotNull null
            val endNs = end.elapsedRealtimeNs ?: return@mapNotNull null
            if (endNs <= startNs) return@mapNotNull null
            StartupPerfettoPhaseAttribution(
                phaseName = "${start.kind.name} → ${end.kind.name}",
                startNs = startNs,
                endNs = endNs,
                schedulingNs = overlapNanos(evidence.scheduling, startNs, endNs, mapping),
                binderNs = overlapNanos(evidence.binder, startNs, endNs, mapping),
                mainThreadBlockedNs = overlapNanos(evidence.mainThread, startNs, endNs, mapping),
                frameNs = overlapNanos(evidence.frames, startNs, endNs, mapping),
                wakingCount = evidence.waking.count { inWindow(it, startNs, endNs, mapping) },
                runQueueSamples = evidence.runQueue.count { inWindow(it, startNs, endNs, mapping) },
                gcNs = overlapNanos(evidence.gc, startNs, endNs, mapping),
                jitNs = overlapNanos(evidence.jit, startNs, endNs, mapping),
                classLoadingNs = overlapNanos(evidence.classLoading, startNs, endNs, mapping),
                classVerificationNs = overlapNanos(evidence.classVerification, startNs, endNs, mapping),
            )
        }

    private fun overlapNanos(
        slices: List<StartupPerfettoSlice>,
        startNs: Long,
        endNs: Long,
        mapping: ClockMapping?,
    ): Long =
        slices.sumOf { slice ->
            val mappedStart = mapTimestamp(slice.timestampNs, mapping)
            val mappedEnd = mappedStart + slice.durationNs.coerceAtLeast(0L)
            (minOf(mappedEnd, endNs) - maxOf(mappedStart, startNs)).coerceAtLeast(0L)
        }

    private fun inWindow(
        slice: StartupPerfettoSlice,
        startNs: Long,
        endNs: Long,
        mapping: ClockMapping?,
    ): Boolean {
        val timestamp = mapTimestamp(slice.timestampNs, mapping)
        return timestamp in startNs until endNs
    }

    private fun mapTimestamp(
        timestamp: Long,
        mapping: ClockMapping?,
    ): Long = mapping?.let { timestamp - it.sourceReferenceNanos + it.targetReferenceNanos } ?: timestamp

    private fun runtimeSliceQuery(
        processId: Int?,
        predicate: String,
    ): TraceQuery<StartupPerfettoSlice> =
        sliceQuery(
            "SELECT s.ts, s.dur, s.name, t.name AS thread_name FROM slice AS s " +
                "JOIN thread_track AS tt ON tt.id = s.track_id " +
                "JOIN thread AS t ON t.utid = tt.utid JOIN process AS p USING (upid) " +
                "WHERE ($predicate) " + processPredicate(processId) + " ORDER BY s.ts",
        )

    private fun processFilterForUpid(processId: Int?): String {
        if (processId == null) return "WHERE upid IS NOT NULL"
        require(processId > 0) { "startup process id must be positive" }
        return "WHERE upid = (SELECT upid FROM process WHERE pid = $processId ORDER BY start_ts DESC LIMIT 1)"
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
        val SCHED_WAKING = CapabilityId("startup.sched_waking")
        val RUN_QUEUE = CapabilityId("startup.run_queue")
        val GC = CapabilityId("startup.art.gc")
        val JIT = CapabilityId("startup.art.jit")
        val CLASS_LOADING = CapabilityId("startup.art.class_loading")
        val CLASS_VERIFICATION = CapabilityId("startup.art.class_verification")
        val ALL: Set<CapabilityId> =
            setOf(
                SCHEDULING,
                BINDER,
                MAIN_THREAD,
                FRAME,
                SCHED_WAKING,
                RUN_QUEUE,
                GC,
                JIT,
                CLASS_LOADING,
                CLASS_VERIFICATION,
            )
    }
}
