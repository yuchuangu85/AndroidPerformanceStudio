package com.androidperformancestudio.frame.analysis

import com.androidperformancestudio.frame.model.ExpectedDurationSource
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.frame.model.JankType
import kotlin.test.Test
import kotlin.test.assertEquals

class FrameTimelineClassificationTest {
    @Test
    fun `maps every supported authoritative tag`() {
        val statuses =
            mapOf(
                "No Jank" to FrameTimelineStatus.GOOD,
                "Buffer Stuffing" to FrameTimelineStatus.HIGH_LATENCY,
                "SurfaceFlinger Stuffing" to FrameTimelineStatus.HIGH_LATENCY,
                "Self Jank" to FrameTimelineStatus.SELF_JANK,
                "Other Jank" to FrameTimelineStatus.OTHER_JANK,
                "Dropped Frame" to FrameTimelineStatus.DROPPED,
            )

        for ((tag, status) in statuses) {
            val frame = sample().copy(states = mapOf(FRAME_TIMELINE_JANK_TAG to tag))
            assertEquals(status, frame.frameTimelineStatus(), tag)
        }
    }

    @Test
    fun `authoritative tags win over conflicting presentation duration and jank signals`() {
        val statuses =
            mapOf(
                "No Jank" to FrameTimelineStatus.GOOD,
                "Buffer Stuffing" to FrameTimelineStatus.HIGH_LATENCY,
                "Self Jank" to FrameTimelineStatus.SELF_JANK,
                "Other Jank" to FrameTimelineStatus.OTHER_JANK,
                "Dropped Frame" to FrameTimelineStatus.DROPPED,
            )

        val cases = FrameSource.entries.flatMap { source -> listOf(5L, 20L).map { duration -> source to duration } }
        for ((source, duration) in cases) {
            for ((tag, status) in statuses) {
                val presentType = if (tag == "Dropped Frame") "On-time Present" else "Dropped Frame"
                val frame =
                    sample(source).copy(
                        totalDurationNs = duration,
                        platformJank = true,
                        platformJankRuleId = "Buffer Stuffing",
                        jankStatsJank = true,
                        platformJankTypes = setOf(JankType.DEADLINE_MISSED),
                        surfaceFlingerJankType = "SurfaceFlinger CPU Deadline Missed",
                        states =
                            mapOf(
                                FRAME_TIMELINE_JANK_TAG to tag,
                                FRAME_TIMELINE_PRESENT_TYPE to presentType,
                                "jank_severity_type" to "Full",
                            ),
                    )

                assertEquals(status, frame.frameTimelineStatus(), "$source / $tag / $duration")
            }
        }
    }

    @Test
    fun `unsupported present tags remain unknown without legacy fallback`() {
        val unsupported =
            listOf(
                "Unspecified",
                "Non-perceivable Jank",
                "Unknown Jank",
                "future tag",
                "",
                "no jank",
                " No Jank",
                "No Jank ",
            )

        for (tag in unsupported) {
            val frame =
                sample().copy(
                    platformJank = false,
                    platformJankRuleId = "None",
                    states = mapOf(FRAME_TIMELINE_JANK_TAG to tag, FRAME_TIMELINE_PRESENT_TYPE to "Dropped Frame"),
                )

            assertEquals(FrameTimelineStatus.UNKNOWN, frame.frameTimelineStatus(), tag)
        }
    }

    @Test
    fun `legacy Perfetto rules accept only exact neutral or stuffing values`() {
        val rules =
            mapOf(
                "None" to FrameTimelineStatus.GOOD,
                "On time" to FrameTimelineStatus.GOOD,
                "Buffer Stuffing" to FrameTimelineStatus.HIGH_LATENCY,
                "SurfaceFlinger Stuffing" to FrameTimelineStatus.HIGH_LATENCY,
                "App Deadline Missed" to FrameTimelineStatus.UNKNOWN,
                "SurfaceFlinger CPU Deadline Missed" to FrameTimelineStatus.UNKNOWN,
                "App Deadline Missed, Buffer Stuffing" to FrameTimelineStatus.UNKNOWN,
                "Buffer Stuffing, SurfaceFlinger Stuffing" to FrameTimelineStatus.UNKNOWN,
                "Dropped Frame" to FrameTimelineStatus.UNKNOWN,
                "On-time Present" to FrameTimelineStatus.UNKNOWN,
                "On Time" to FrameTimelineStatus.UNKNOWN,
                "None " to FrameTimelineStatus.UNKNOWN,
            )

        for ((rule, status) in rules) {
            val frame = sample().copy(totalDurationNs = 20L, platformJank = true, platformJankRuleId = rule)

            assertEquals(status, frame.frameTimelineStatus(), rule)
        }
    }

    @Test
    fun `Perfetto missing classification never falls back to duration or booleans`() {
        val frames =
            listOf(
                sample(),
                sample().copy(totalDurationNs = null),
                sample().copy(platformJank = false),
                sample().copy(jankStatsJank = false),
                sample().copy(states = mapOf(FRAME_TIMELINE_PRESENT_TYPE to "Dropped Frame")),
            )

        for (frame in frames) {
            assertEquals(FrameTimelineStatus.UNKNOWN, frame.frameTimelineStatus(), frame.toString())
        }
    }

    @Test
    fun `associated SurfaceFlinger jank does not attribute app jank`() {
        val frame = sample().copy(surfaceFlingerJankType = "SurfaceFlinger CPU Deadline Missed")

        assertEquals(FrameTimelineStatus.UNKNOWN, frame.frameTimelineStatus())
        assertEquals(
            FrameTimelineStatus.GOOD,
            frame.copy(states = mapOf(FRAME_TIMELINE_JANK_TAG to "No Jank")).frameTimelineStatus(),
        )
        assertEquals(FrameTimelineStatus.GOOD, frame.copy(source = FrameSource.GFXINFO).frameTimelineStatus())
    }

    @Test
    fun `lost samples never become dropped frames`() {
        for (source in FrameSource.entries) {
            val frame = sample(source).copy(totalDurationNs = null, droppedBeforeSample = 5L)

            assertEquals(FrameTimelineStatus.UNKNOWN, frame.frameTimelineStatus(), source.name)
            assertEquals(
                FrameTimelineStatus.GOOD,
                frame.copy(platformJankRuleId = "None", platformJank = false).frameTimelineStatus(),
            )
        }

        assertEquals(
            FrameTimelineStatus.DROPPED,
            sample().copy(states = mapOf(FRAME_TIMELINE_JANK_TAG to "Dropped Frame")).frameTimelineStatus(),
        )
    }

    @Test
    fun `explicit non-jank wins over duration but never a conflicting true signal`() {
        val signals =
            mapOf(
                (null to null) to FrameTimelineStatus.UNKNOWN,
                (false to null) to FrameTimelineStatus.GOOD,
                (null to false) to FrameTimelineStatus.GOOD,
                (false to false) to FrameTimelineStatus.GOOD,
                (true to null) to FrameTimelineStatus.UNKNOWN,
                (null to true) to FrameTimelineStatus.UNKNOWN,
                (true to false) to FrameTimelineStatus.UNKNOWN,
                (false to true) to FrameTimelineStatus.UNKNOWN,
                (true to true) to FrameTimelineStatus.UNKNOWN,
            )

        for (source in FrameSource.entries.filter { it != FrameSource.PERFETTO }) {
            for ((signal, status) in signals) {
                val frame =
                    sample(source).copy(
                        totalDurationNs = 20L,
                        platformJank = signal.first,
                        jankStatsJank = signal.second,
                    )

                assertEquals(status, frame.frameTimelineStatus(), "$source / $signal")
            }
        }
    }

    @Test
    fun `duration fallback requires an eligible frame and a known positive budget`() {
        val frames =
            listOf(
                sample() to FrameTimelineStatus.GOOD,
                sample().copy(totalDurationNs = 10L) to FrameTimelineStatus.GOOD,
                sample().copy(totalDurationNs = 11L) to FrameTimelineStatus.UNKNOWN,
                sample().copy(totalDurationNs = null, intendedVsyncNs = 10L, frameCompletedNs = 15L) to
                    FrameTimelineStatus.GOOD,
                sample().copy(totalDurationNs = null) to FrameTimelineStatus.UNKNOWN,
                sample().copy(totalDurationNs = -1L) to FrameTimelineStatus.UNKNOWN,
                sample().copy(expectedDurationNs = null) to FrameTimelineStatus.UNKNOWN,
                sample().copy(expectedDurationNs = 0L) to FrameTimelineStatus.UNKNOWN,
                sample().copy(expectedDurationNs = -1L) to FrameTimelineStatus.UNKNOWN,
                sample().copy(expectedDurationSource = ExpectedDurationSource.UNKNOWN) to FrameTimelineStatus.UNKNOWN,
                sample().copy(eligibleForJank = false) to FrameTimelineStatus.UNKNOWN,
            )

        for (source in FrameSource.entries.filter { it != FrameSource.PERFETTO }) {
            for ((frame, status) in frames) {
                assertEquals(status, frame.copy(source = source).frameTimelineStatus(), "$source / $frame")
            }
        }
    }

    @Test
    fun `unattributed jank reports stay unknown even within budget`() {
        val frames =
            listOf(
                sample().copy(platformJank = true),
                sample().copy(jankStatsJank = true),
                sample().copy(platformJankTypes = setOf(JankType.PLATFORM_REPORTED)),
                sample().copy(platformJankTypes = setOf(JankType.DEADLINE_MISSED)),
                sample().copy(jankStatsReasons = setOf("App Deadline Missed")),
            )

        for (source in FrameSource.entries.filter { it != FrameSource.PERFETTO }) {
            for (frame in frames) {
                assertEquals(
                    FrameTimelineStatus.UNKNOWN,
                    frame.copy(source = source).frameTimelineStatus(),
                    "$source / $frame",
                )
            }
        }
    }

    private fun sample(source: FrameSource = FrameSource.PERFETTO): FrameSample =
        FrameSample(
            frameId = 1L,
            sessionId = "session",
            source = source,
            totalDurationNs = 5L,
            expectedDurationNs = 10L,
            expectedDurationSource = ExpectedDurationSource.PLATFORM_DEADLINE,
        )
}
