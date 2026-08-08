package com.androidperformancestudio.session.model

import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.Serializable

/** The profiler a captured segment came from / should be opened in. */
@Serializable
enum class SessionSegmentKind {
    HPROF,
    NATIVE_HEAP,
    JAVA_HEAP,
    METHOD_TRACE,
    SIMPLEPERF,
    PERFETTO,
    NETWORK,
    BATTERY,
    FRAME,
    STARTUP,
}

/** One captured/imported profiler artifact grouped under a [ProfilerSession]. */
@Serializable
data class SessionSegment(
    val id: String,
    val kind: SessionSegmentKind,
    val label: String,
    /** Artifact path stored as a string for JSON serialization. */
    val artifactPath: String,
    val capturedAtEpochMillis: Long,
    val durationMillis: Long? = null,
    val summary: String? = null,
    val sourceProfiler: String = "",
) {
    val artifact: Path
        get() = Path.of(artifactPath)

    val capturedAt: Instant
        get() = Instant.ofEpochMilli(capturedAtEpochMillis)
}

/**
 * A named container grouping profiler artifacts on a timeline. This is the "unified Session" of
 * ADR-0003 — each [segments] entry is one feature's capture/import, shown as a timeline segment.
 */
@Serializable
data class ProfilerSession(
    val id: String,
    val name: String,
    val packageName: String? = null,
    val deviceSerial: String? = null,
    val createdAtEpochMillis: Long,
    val segments: List<SessionSegment> = emptyList(),
) {
    val createdAt: Instant
        get() = Instant.ofEpochMilli(createdAtEpochMillis)

    fun withSegment(segment: SessionSegment): ProfilerSession =
        copy(segments = segments + segment)

    fun withoutSegment(segmentId: String): ProfilerSession =
        copy(segments = segments.filterNot { it.id == segmentId })
}
