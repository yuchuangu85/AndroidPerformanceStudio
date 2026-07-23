package com.androidperformancestudio.perfetto.storage

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.perfetto.model.PerfettoCaptureConfig
import com.androidperformancestudio.perfetto.model.PerfettoTraceTemplate
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.perfetto.model.TraceSession
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

class TraceSessionStore(
    private val storageDir: Path = defaultStorageDir(),
) {
    private val indexFile: Path = storageDir.resolve("sessions.json")

    init {
        Files.createDirectories(storageDir)
        if (!Files.exists(indexFile)) {
            Files.writeString(indexFile, "[]")
        }
    }

    fun save(session: TraceSession): StudioResult<Unit> = try {
        val sessions = readSessions().toMutableList()
        sessions.removeAll { it.id == session.id }
        sessions.add(0, session)
        writeSessions(sessions)
        StudioResult.Success(Unit)
    } catch (e: Exception) {
        StudioResult.Failure(StudioError(
            category = ErrorCategory.IO,
            code = "SESSION_SAVE_FAILED",
            message = "Failed to save session: ${e.message}",
            cause = e,
        ))
    }

    fun listRecent(limit: Int = 20): StudioResult<List<TraceSession>> = try {
        StudioResult.Success(readSessions().take(limit))
    } catch (e: Exception) {
        StudioResult.Failure(StudioError(
            category = ErrorCategory.IO,
            code = "SESSION_LIST_FAILED",
            message = "Failed to list sessions: ${e.message}",
            cause = e,
        ))
    }

    fun delete(sessionId: String): StudioResult<Unit> = try {
        val sessions = readSessions().toMutableList()
        sessions.removeAll { it.id == sessionId }
        writeSessions(sessions)
        StudioResult.Success(Unit)
    } catch (e: Exception) {
        StudioResult.Failure(StudioError(
            category = ErrorCategory.IO,
            code = "SESSION_DELETE_FAILED",
            message = "Failed to delete session: ${e.message}",
            cause = e,
        ))
    }

    private fun readSessions(): List<TraceSession> {
        val json = Files.readString(indexFile)
        if (json.isBlank() || json == "[]") return emptyList()
        val items = json.trim().removeSurrounding("[", "]")
            .split("},{")
            .map { it.trim().removeSurrounding("{", "}").removeSurrounding("\"", "\"") }
        return items.mapNotNull { parseSessionLine(it) }
    }

    private fun writeSessions(sessions: List<TraceSession>) {
        val json = sessions.joinToString(",\n  ", "[\n  ", "\n]") { s -> sessionToJson(s) }
        Files.writeString(indexFile, json)
    }

    private fun sessionToJson(s: TraceSession): String = buildString {
        append("{\"id\":\"${s.id}\"")
        append(",\"traceFile\":\"${s.traceFile}\"")
        append(",\"template\":\"${s.captureConfig.template.name}\"")
        append(",\"deviceSerial\":\"${s.deviceSerial}\"")
        append(",\"deviceModel\":\"${s.deviceModel}\"")
        append(",\"androidSdk\":${s.androidSdk}")
        append(",\"capturedAt\":\"${s.capturedAt}\"")
        append(",\"durationNanos\":${s.durationNanos}")
        append(",\"fileSizeBytes\":${s.fileSizeBytes}")
        append(",\"durationSeconds\":${s.captureConfig.durationSeconds}")
        append(",\"bufferSizeKb\":${s.captureConfig.bufferSizeKb}")
        append("}")
    }

    private fun parseSessionLine(line: String): TraceSession? = try {
        val id = extractValue(line, "id")
        val traceFile = extractValue(line, "traceFile")
        val template = extractValue(line, "template")
        val deviceSerial = extractValue(line, "deviceSerial")
        val deviceModel = extractValue(line, "deviceModel")
        val androidSdk = extractInt(line, "androidSdk")
        val capturedAt = extractValue(line, "capturedAt")
        val durationNanos = extractLong(line, "durationNanos")
        val fileSizeBytes = extractLong(line, "fileSizeBytes")
        val durationSeconds = extractInt(line, "durationSeconds")
        val bufferSizeKb = extractInt(line, "bufferSizeKb")
        val templateEnum = PerfettoTraceTemplate.entries.firstOrNull { it.name == template }
            ?: PerfettoTraceTemplate.CUSTOM
        TraceSession(
            id = id,
            traceFile = Path.of(traceFile),
            captureConfig = PerfettoCaptureConfig(
                template = templateEnum,
                durationSeconds = durationSeconds,
                bufferSizeKb = bufferSizeKb,
            ),
            deviceSerial = deviceSerial,
            deviceModel = deviceModel,
            androidSdk = androidSdk,
            capturedAt = Instant.parse(capturedAt),
            durationNanos = durationNanos,
            fileSizeBytes = fileSizeBytes,
        )
    } catch (e: Exception) { null }

    private fun extractValue(line: String, key: String): String {
        val pattern = "\"$key\":\""
        val start = line.indexOf(pattern)
        if (start < 0) return ""
        val valueStart = start + pattern.length
        val end = line.indexOf("\"", valueStart)
        return if (end > valueStart) line.substring(valueStart, end) else ""
    }

    private fun extractInt(line: String, key: String): Int {
        val pattern = "\"$key\":"
        val start = line.indexOf(pattern)
        if (start < 0) return 0
        val valueStart = start + pattern.length
        val end = line.indexOfFirst { it == ',' || it == '}' }.let { if (it < 0) line.length else it }
        return line.substring(valueStart, end).trim().toIntOrNull() ?: 0
    }

    private fun extractLong(line: String, key: String): Long {
        val pattern = "\"$key\":"
        val start = line.indexOf(pattern)
        if (start < 0) return 0
        val valueStart = start + pattern.length
        val end = line.indexOfFirst { it == ',' || it == '}' }.let { if (it < 0) line.length else it }
        return line.substring(valueStart, end).trim().toLongOrNull() ?: 0
    }

    companion object {
        fun defaultStorageDir(): Path = Paths.get(
            System.getProperty("user.home"),
            ".android-performance-studio", "perfetto-sessions",
        )
    }
}
