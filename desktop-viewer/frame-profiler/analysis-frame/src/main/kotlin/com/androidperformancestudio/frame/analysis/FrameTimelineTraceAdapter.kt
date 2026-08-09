package com.androidperformancestudio.frame.analysis

import com.androidperformancestudio.contracts.CapabilityId
import com.androidperformancestudio.frame.model.ExpectedDurationSource
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.platform.perfetto.TraceColumn
import com.androidperformancestudio.platform.perfetto.TraceQuery
import com.androidperformancestudio.platform.perfetto.TraceQueryResult
import com.androidperformancestudio.platform.perfetto.TraceQuerySchema

/** Maps Android 12+ Expected/Actual FrameTimeline rows into the existing Frame model. */
class FrameTimelineTraceAdapter {
    private val frameId = TraceColumn.long("frame_id")
    private val expectedTs = TraceColumn.long("expected_ts")
    private val expectedDur = TraceColumn.long("expected_dur")
    private val actualTs = TraceColumn.long("actual_ts")
    private val actualDur = TraceColumn.long("actual_dur")
    private val surfaceToken = TraceColumn.long("surface_token")
    private val layerName = TraceColumn.string("layer_name")
    private val jankType = TraceColumn.string("jank_type")
    private val processName = TraceColumn.string("process_name")
    private val surfaceFlingerJankType = TraceColumn.string("surface_flinger_jank_type")

    fun timelineQuery(processId: Int?): TraceQuery<FrameTimelineRow> =
        TraceQuery(
            sql =
                """
                SELECT e.display_frame_token AS frame_id,
                       e.ts AS expected_ts,
                       e.dur AS expected_dur,
                       a.ts AS actual_ts,
                       a.dur AS actual_dur,
                       COALESCE(a.surface_frame_token, e.surface_frame_token) AS surface_token,
                       COALESCE(a.layer_name, e.layer_name) AS layer_name,
                       a.jank_type AS jank_type,
                       p.name AS process_name,
                       (
                         SELECT sf.jank_type
                         FROM actual_frame_timeline_slice AS sf
                         JOIN process AS sfp ON sfp.upid = sf.upid
                         WHERE sf.display_frame_token = e.display_frame_token
                           AND lower(sfp.name) GLOB '*surfaceflinger*'
                         ORDER BY sf.id
                         LIMIT 1
                       ) AS surface_flinger_jank_type
                FROM expected_frame_timeline_slice AS e
                LEFT JOIN actual_frame_timeline_slice AS a
                  ON a.upid = e.upid
                 AND a.display_frame_token = e.display_frame_token
                 AND a.surface_frame_token IS e.surface_frame_token
                LEFT JOIN process AS p ON p.upid = e.upid
                WHERE e.display_frame_token IS NOT NULL
                  ${processPredicate(processId)}
                ORDER BY e.ts
                """.trimIndent(),
            schema =
                TraceQuerySchema.v57_2(
                    frameId,
                    expectedTs,
                    expectedDur,
                    actualTs,
                    actualDur,
                    surfaceToken,
                    layerName,
                    jankType,
                    processName,
                    surfaceFlingerJankType,
                ),
        ) { row ->
            FrameTimelineRow(
                frameId = requireNotNull(row[frameId]),
                expectedTs = row[expectedTs],
                expectedDur = row[expectedDur],
                actualTs = row[actualTs],
                actualDur = row[actualDur],
                surfaceToken = row[surfaceToken],
                layerName = row[layerName],
                jankType = row[jankType],
                processName = row[processName],
                surfaceFlingerJankType = row[surfaceFlingerJankType],
            )
        }

    fun mapFixture(csv: String): FrameTimelineResult = map(timelineQuery(null).map(TraceQueryResult.parse(csv)))

    fun map(rows: List<FrameTimelineRow>): FrameTimelineResult =
        FrameTimelineResult(
            frames =
                rows.map { row ->
                    val jank = row.jankType?.takeUnless { it.equals("On time", ignoreCase = true) }
                    FrameSample(
                        frameId = row.frameId,
                        sessionId = "perfetto",
                        source = FrameSource.PERFETTO,
                        activityName =
                            listOfNotNull(row.processName, row.layerName)
                                .joinToString(": ")
                                .ifBlank { null },
                        intendedVsyncNs = row.expectedTs,
                        actualVsyncNs = row.actualTs,
                        expectedDurationNs = row.expectedDur,
                        expectedDurationSource = ExpectedDurationSource.PLATFORM_DEADLINE,
                        frameTimelineVsyncId = row.frameId,
                        totalDurationNs = row.actualDur,
                        platformJank = jank != null,
                        platformJankRuleId = row.jankType,
                        states =
                            buildMap {
                                row.surfaceToken?.let { put("surfaceFrameToken", it.toString()) }
                                row.surfaceFlingerJankType?.let { put("surfaceFlingerJankType", it) }
                            },
                    )
                },
            capabilities =
                FRAME_TIMELINE_CAPABILITIES -
                    if (rows.any { it.surfaceToken != null && it.surfaceFlingerJankType != null }) {
                        emptySet()
                    } else {
                        setOf(SURFACE_CORRELATION)
                    },
        )

    private fun processPredicate(processId: Int?): String {
        if (processId == null) return ""
        require(processId > 0) { "frame process id must be positive" }
        return "AND e.upid = (SELECT upid FROM process WHERE pid = $processId ORDER BY start_ts DESC LIMIT 1)"
    }

    companion object {
        val EXPECTED_TIMELINE = CapabilityId("frame.expected_timeline")
        val ACTUAL_TIMELINE = CapabilityId("frame.actual_timeline")
        val SURFACE_CORRELATION = CapabilityId("frame.surface_correlation")
        val JANK_CLASSIFICATION = CapabilityId("frame.jank_classification")
        val FRAME_TIMELINE_CAPABILITIES: Set<CapabilityId> =
            setOf(EXPECTED_TIMELINE, ACTUAL_TIMELINE, SURFACE_CORRELATION, JANK_CLASSIFICATION)
    }
}

data class FrameTimelineRow(
    val frameId: Long,
    val expectedTs: Long?,
    val expectedDur: Long?,
    val actualTs: Long?,
    val actualDur: Long?,
    val surfaceToken: Long?,
    val layerName: String?,
    val jankType: String?,
    val processName: String?,
    val surfaceFlingerJankType: String?,
)

data class FrameTimelineResult(
    val frames: List<FrameSample>,
    val capabilities: Set<CapabilityId>,
)
