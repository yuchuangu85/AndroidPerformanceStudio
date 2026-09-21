@file:Suppress("MaxLineLength", "ktlint:standard:max-line-length")

package com.androidperformancestudio.profileanalysis

import com.androidperformancestudio.model.ProfileCategory
import com.androidperformancestudio.model.ProfileClockDomain
import com.androidperformancestudio.model.ProfileCounterFact
import com.androidperformancestudio.model.ProfileProcessKey
import com.androidperformancestudio.model.ProfileSliceFact
import com.androidperformancestudio.model.ProfileSourceFact
import com.androidperformancestudio.model.ProfileSourceId
import com.androidperformancestudio.model.ProfileSourceKind
import com.androidperformancestudio.model.ProfileThreadKey
import com.androidperformancestudio.model.ProfileTimePoint
import com.androidperformancestudio.platform.perfetto.TraceColumn
import com.androidperformancestudio.platform.perfetto.TraceQuery
import com.androidperformancestudio.platform.perfetto.TraceQueryResult
import com.androidperformancestudio.platform.perfetto.TraceQuerySchema

data class SimpleperfPerfettoEvidence(
    val source: ProfileSourceFact,
    val scheduling: List<ProfileSliceFact>,
    val binder: List<ProfileSliceFact>,
    val frameTimeline: List<ProfileSliceFact>,
    val frequencyCounters: List<ProfileCounterFact>,
)

class SimpleperfPerfettoTraceAdapter(
    private val sourceId: ProfileSourceId = ProfileSourceId("perfetto"),
) {
    private val ts = TraceColumn.long("ts")
    private val dur = TraceColumn.long("dur")
    private val name = TraceColumn.string("name")
    private val processName = TraceColumn.string("process_name")
    private val threadName = TraceColumn.string("thread_name")
    private val pid = TraceColumn.long("pid")
    private val tid = TraceColumn.long("tid")
    private val unit = TraceColumn.string("unit")
    private val value = TraceColumn.double("value")

    val schedulingQuery: TraceQuery<PerfettoSliceRow> =
        sliceQuery(
            """
            SELECT sched.ts, sched.dur, 'Running' AS name,
                   process.name AS process_name, thread.name AS thread_name,
                   process.pid, thread.tid
            FROM sched_slice AS sched
            JOIN thread ON thread.utid = sched.utid
            LEFT JOIN process ON process.upid = thread.upid
            WHERE sched.dur > 0
            ORDER BY sched.ts
            """.trimIndent(),
        )

    val binderQuery: TraceQuery<PerfettoSliceRow> =
        sliceQuery(
            """
            INCLUDE PERFETTO MODULE android.binder;

            SELECT client_ts AS ts, client_dur AS dur,
                   COALESCE(interface || '.' || method_name, 'binder') AS name,
                   client_process AS process_name, client_thread AS thread_name,
                   client_pid AS pid, client_tid AS tid
            FROM android_binder_txns
            WHERE client_dur > 0
            ORDER BY client_ts
            """.trimIndent(),
        )

    val frameTimelineQuery: TraceQuery<PerfettoSliceRow> =
        sliceQuery(
            """
            SELECT frame.ts, frame.dur,
                   'FrameTimeline:' || COALESCE(frame.jank_type, 'unknown') AS name,
                   process.name AS process_name, NULL AS thread_name,
                   process.pid AS pid, NULL AS tid
            FROM actual_frame_timeline_slice AS frame
            LEFT JOIN process USING (upid)
            WHERE frame.dur > 0
            ORDER BY frame.ts
            """.trimIndent(),
        )

    val frequencyQuery: TraceQuery<PerfettoCounterRow> =
        TraceQuery(
            """
            SELECT counter.ts,
                   track.name AS name,
                   CASE WHEN LOWER(track.name) GLOB '*gpu*' THEN 'Hz' ELSE 'kHz' END AS unit,
                   counter.value
            FROM counter
            JOIN counter_track AS track ON track.id = counter.track_id
            WHERE LOWER(track.name) GLOB '*freq*'
            ORDER BY counter.ts
            """.trimIndent(),
            TraceQuerySchema.v57_2(ts, name, unit, value),
        ) { row -> PerfettoCounterRow(requireNotNull(row[ts]), row[name].orEmpty(), row[unit].orEmpty(), row[value] ?: 0.0) }

    fun mapFixture(
        schedulingCsv: String,
        binderCsv: String,
        frameCsv: String,
        counterCsv: String,
    ): SimpleperfPerfettoEvidence =
        map(
            schedulingQuery.map(TraceQueryResult.parse(schedulingCsv)),
            binderQuery.map(TraceQueryResult.parse(binderCsv)),
            frameTimelineQuery.map(TraceQueryResult.parse(frameCsv)),
            frequencyQuery.map(TraceQueryResult.parse(counterCsv)),
        )

    fun map(
        scheduling: List<PerfettoSliceRow>,
        binder: List<PerfettoSliceRow>,
        frames: List<PerfettoSliceRow>,
        counters: List<PerfettoCounterRow>,
    ): SimpleperfPerfettoEvidence {
        val clock = ProfileClockDomain("perfetto.trace_time")
        val timestamps = scheduling + binder + frames
        val first = (timestamps.map(PerfettoSliceRow::ts) + counters.map(PerfettoCounterRow::ts)).minOrNull()
        val last = (timestamps.map { it.ts + it.dur } + counters.map(PerfettoCounterRow::ts)).maxOrNull()
        return SimpleperfPerfettoEvidence(
            source = ProfileSourceFact(sourceId, ProfileSourceKind.PERFETTO, clock, first, last),
            scheduling = scheduling.map { it.toFact(clock, ProfileCategory("Scheduling")) },
            binder = binder.map { it.toFact(clock, ProfileCategory("IPC", "Binder")) },
            frameTimeline = frames.map { it.toFact(clock, ProfileCategory("Graphics", "FrameTimeline")) },
            frequencyCounters =
                counters.map { row ->
                    ProfileCounterFact(sourceId, ProfileTimePoint(clock, row.ts), row.name, row.unit, row.value)
                },
        )
    }

    private fun sliceQuery(sql: String): TraceQuery<PerfettoSliceRow> =
        TraceQuery(sql, TraceQuerySchema.v57_2(ts, dur, name, processName, threadName, pid, tid)) { row ->
            PerfettoSliceRow(
                ts = requireNotNull(row[ts]),
                dur = row[dur] ?: 0,
                name = row[name].orEmpty(),
                processName = row[processName],
                threadName = row[threadName],
                pid = row[pid]?.toInt(),
                tid = row[tid]?.toInt(),
            )
        }

    private fun PerfettoSliceRow.toFact(
        clock: ProfileClockDomain,
        category: ProfileCategory,
    ): ProfileSliceFact {
        val process = pid?.let { ProfileProcessKey(sourceId, it) }
        val thread = if (process != null && tid != null) ProfileThreadKey(sourceId, process, tid) else null
        return ProfileSliceFact(
            sourceId = sourceId,
            thread = thread,
            start = ProfileTimePoint(clock, ts),
            end = ProfileTimePoint(clock, ts + dur.coerceAtLeast(0)),
            name = name,
            category = category,
        )
    }
}

data class PerfettoSliceRow(
    val ts: Long,
    val dur: Long,
    val name: String,
    val processName: String?,
    val threadName: String?,
    val pid: Int?,
    val tid: Int?,
)

data class PerfettoCounterRow(
    val ts: Long,
    val name: String,
    val unit: String,
    val value: Double,
)
