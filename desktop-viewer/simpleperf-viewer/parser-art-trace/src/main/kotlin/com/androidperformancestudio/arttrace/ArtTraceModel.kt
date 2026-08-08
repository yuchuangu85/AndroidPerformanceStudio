package com.androidperformancestudio.arttrace

/**
 * Parsed model of an ART method-trace (`.trace`) file — the binary output of `am profile
 * start/stop` (and `Debug.startMethodTracing`).
 *
 * The parser supports the two on-disk layouts produced by ART (`art/runtime/trace.cc`):
 * - streaming **versions 4/5** (single/dual clock) — the default on modern Android
 * - classic **versions 2/3** — legacy fixed-size-record layout
 *
 * Records are normalized here into a single [ArtTraceEvent] stream: per-thread, time-ordered
 * method enter/exit/unroll events whose [ArtTraceEvent.timeNanos] is a monotonic time in
 * nanoseconds relative to the trace start. Consuming code (call-stack replay, flame chart) needs
 * no knowledge of the underlying version.
 */

/** The two clock sources ART can record: a single stream, or wall + thread-CPU. */
enum class ArtClockSource { SINGLE, DUAL }

/** Method enter/exit/unroll actions, stored in the low 2 bits of the record method word. */
enum class ArtTraceAction { ENTER, EXIT, UNROLL }

data class ArtTraceHeader(
    val version: Int,
    /** Monotonic host clock at trace start, nanoseconds (v2/v3 converted from µs). */
    val startTimeNanos: Long,
    val clockSource: ArtClockSource,
)

/** A method that appeared in the trace, keyed by the record-side method id. */
data class ArtMethod(
    val methodId: Long,
    val className: String,
    val methodName: String,
    val signature: String,
    val sourceFile: String,
)

/** A traced thread, keyed by the (0-based) thread id used in records. */
data class ArtThread(
    val threadId: Int,
    val name: String,
)

/** One method enter/exit/unroll record, normalized to a monotonic nanosecond timeline. */
data class ArtTraceEvent(
    val threadId: Int,
    val methodId: Long,
    val action: ArtTraceAction,
    /** Monotonic time since trace start, nanoseconds. */
    val timeNanos: Long,
    /** Per-thread CPU time since trace start, nanoseconds, when the trace is dual-clock. */
    val cpuNanos: Long? = null,
)

/**
 * Fully parsed trace. [events] appear in file order (each thread's own events are
 * chronological); [methods] and [threads] key the ids used in [events].
 */
data class ArtTraceAnalysis(
    val header: ArtTraceHeader,
    val methods: Map<Long, ArtMethod>,
    val threads: Map<Int, ArtThread>,
    val events: List<ArtTraceEvent>,
    val startTimeNanos: Long,
    val endTimeNanos: Long,
    val warnings: List<String>,
)

sealed interface ArtTraceParseResult {
    data class Success(val analysis: ArtTraceAnalysis) : ArtTraceParseResult
    data class Failure(val message: String) : ArtTraceParseResult
}
