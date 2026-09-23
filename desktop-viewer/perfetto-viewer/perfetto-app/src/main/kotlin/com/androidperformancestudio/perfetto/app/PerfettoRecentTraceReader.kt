package com.androidperformancestudio.perfetto.app

import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.perfetto.model.TraceSession
import com.androidperformancestudio.perfetto.storage.TraceSessionStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Read-only summary for a captured trace; the desktop dashboard never owns Perfetto sessions. */
data class PerfettoRecentTrace(
    val sessionId: String,
    val traceFile: Path,
    val capturedAt: Instant,
    val packageName: String?,
)

class PerfettoRecentTraceReader(
    private val storageDir: Path = TraceSessionStore.defaultStorageDir(),
) {
    fun listRecent(limit: Int): List<PerfettoRecentTrace> =
        when (val result = listRecentResult(limit)) {
            is StudioResult.Failure -> emptyList()
            is StudioResult.Success -> result.value
        }

    /** Strict read for dashboard availability reporting; never creates or repairs the index. */
    fun listRecentResult(limit: Int): StudioResult<List<PerfettoRecentTrace>> {
        if (limit <= 0 || Files.notExists(storageDir.resolve("sessions.json"))) {
            return StudioResult.Success(emptyList())
        }
        // Scan past deleted files so a stale newest entry does not hide an older valid trace.
        val result =
            TraceSessionStore(storageDir, initializeStorage = false)
                .listRecent(limit.coerceAtLeast(MIN_RECENT_SCAN))
        return when (result) {
            is StudioResult.Failure -> result
            is StudioResult.Success ->
                StudioResult.Success(
                    result.value
                        .asSequence()
                        .filter { Files.isRegularFile(it.traceFile) }
                        .map { session ->
                            PerfettoRecentTrace(
                                sessionId = session.id,
                                traceFile = session.traceFile,
                                capturedAt = session.capturedAt,
                                packageName = session.captureConfig.targetPackage,
                            )
                        }.take(limit)
                        .toList(),
                )
        }
    }
}

internal fun storedArtifactForTrace(
    sessions: List<TraceSession>,
    traceFile: Path,
): CaptureArtifact? = sessions.firstOrNull { it.traceFile.toAbsolutePath().normalize() == traceFile.toAbsolutePath().normalize() }?.artifact

private const val MIN_RECENT_SCAN = 100
