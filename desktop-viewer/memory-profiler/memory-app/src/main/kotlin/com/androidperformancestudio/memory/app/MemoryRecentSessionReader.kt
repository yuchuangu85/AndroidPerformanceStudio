package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.storage.SqliteMemorySessionStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Read-only memory-session projection for the desktop dashboard. */
data class MemoryRecentSession(
    val sessionId: String,
    val packageName: String,
    val capturedAt: Instant,
    val hprofFile: Path,
)

class MemoryRecentSessionReader(
    private val dataRoot: Path = defaultMemoryDataRoot(),
) {
    fun listRecent(limit: Int): List<MemoryRecentSession> {
        if (limit <= 0) return emptyList()
        val database = dataRoot.resolve("memory-sessions.db")
        if (Files.notExists(database)) return emptyList()
        return SqliteMemorySessionStore.openExistingReadOnly(database).use { store ->
            // Scan past stale files before applying the visible-item limit.
            store
                .listRecent(limit.coerceAtLeast(MIN_RECENT_SCAN))
                .asSequence()
                .mapNotNull { session ->
                    val artifact =
                        session.convertedHprofFile?.takeIf(Files::isRegularFile)
                            ?: session.rawHprofFile.takeIf(Files::isRegularFile)
                    artifact?.let {
                        MemoryRecentSession(
                            sessionId = session.sessionId,
                            packageName = session.packageName,
                            capturedAt = session.capturedAt,
                            hprofFile = it,
                        )
                    }
                }.take(limit)
                .toList()
        }
    }
}

private const val MIN_RECENT_SCAN = 100
