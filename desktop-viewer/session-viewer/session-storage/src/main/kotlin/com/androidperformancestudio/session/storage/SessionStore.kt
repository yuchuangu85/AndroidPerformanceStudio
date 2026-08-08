package com.androidperformancestudio.session.storage

import com.androidperformancestudio.session.model.ProfilerSession
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists [ProfilerSession]s as a JSON array at a single file (default
 * `~/.android-performance-studio/sessions/sessions.json`). Writes are atomic: serialize to a
 * temp file then move over the target, mirroring the perfetto `TraceSessionStore` approach.
 */
class SessionStore(
    private val file: Path,
) {
    fun save(session: ProfilerSession) {
        val sessions = listRecentInternal().filterNot { it.id == session.id } + session
        write(sessions)
    }

    fun delete(sessionId: String) {
        val sessions = listRecentInternal().filterNot { it.id == sessionId }
        write(sessions)
    }

    fun find(sessionId: String): ProfilerSession? = listRecentInternal().firstOrNull { it.id == sessionId }

    fun listRecent(): List<ProfilerSession> =
        listRecentInternal().sortedWith(compareByDescending<ProfilerSession> { it.createdAtEpochMillis }.thenBy { it.id })

    private fun listRecentInternal(): List<ProfilerSession> {
        if (!Files.isRegularFile(file)) return emptyList()
        return try {
            val payload = json.decodeFromString<SessionList>(Files.readString(file))
            payload.sessions
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun write(sessions: List<ProfilerSession>) {
        file.parent?.let(Files::createDirectories)
        val payload = json.encodeToString<SessionList>(SessionList(sessions))
        val temp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temp, payload)
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Exception) {
            Files.deleteIfExists(temp)
            throw failure
        }
    }

    companion object {
        fun defaultFile(): Path =
            Path.of(
                System.getProperty("user.home"),
                ".android-performance-studio",
                "sessions",
                "sessions.json",
            )

        private val json = Json { prettyPrint = true }
    }
}

@Serializable
private data class SessionList(
    val sessions: List<ProfilerSession>,
)
