package com.androidperformancestudio.perfetto.storage

import com.androidperformancestudio.contracts.CaptureArtifactJson
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.perfetto.model.PerfettoCaptureConfig
import com.androidperformancestudio.perfetto.model.PerfettoProbe
import com.androidperformancestudio.perfetto.model.PerfettoTraceTemplate
import com.androidperformancestudio.perfetto.model.TraceSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant

/** Durable, version-tolerant index for locally captured traces. */
class TraceSessionStore(
    private val storageDir: Path = defaultStorageDir(),
) {
    private val indexFile: Path = storageDir.resolve("sessions.json")
    private val lock = Any()
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    init {
        Files.createDirectories(storageDir)
        if (!Files.exists(indexFile)) Files.writeString(indexFile, "[]")
    }

    fun save(session: TraceSession): StudioResult<Unit> =
        synchronized(lock) {
            execute("SESSION_SAVE_FAILED") {
                val sessions = readSessions().filterNot { it.id == session.id }
                writeSessions(listOf(session) + sessions)
            }
        }

    fun listRecent(limit: Int = DEFAULT_LIMIT): StudioResult<List<TraceSession>> =
        synchronized(lock) {
            execute("SESSION_LIST_FAILED") { readSessions().take(limit.coerceAtLeast(0)) }
        }

    fun delete(sessionId: String): StudioResult<Unit> =
        synchronized(lock) {
            execute("SESSION_DELETE_FAILED") {
                writeSessions(readSessions().filterNot { it.id == sessionId })
            }
        }

    private fun readSessions(): List<TraceSession> {
        val content = Files.readString(indexFile).trim()
        if (content.isBlank()) return emptyList()
        val root = json.parseToJsonElement(content) as? JsonArray ?: return emptyList()
        return root.mapNotNull(::parseSession)
    }

    private fun writeSessions(sessions: List<TraceSession>) {
        val root = buildJsonArray { sessions.forEach { add(sessionToJson(it)) } }
        val temporary = Files.createTempFile(storageDir, "sessions", ".json.tmp")
        try {
            Files.writeString(temporary, json.encodeToString(JsonArray.serializer(), root))
            try {
                Files.move(temporary, indexFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, indexFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun sessionToJson(session: TraceSession): JsonObject =
        buildJsonObject {
            put("id", session.id)
            put("traceFile", session.traceFile.toString())
            put("template", session.captureConfig.template.name)
            session.captureConfig.targetPackage?.let { put("targetPackage", it) }
            put("deviceModel", session.deviceModel)
            put("androidSdk", session.androidSdk)
            put("capturedAt", session.capturedAt.toString())
            put("durationNanos", session.durationNanos)
            put("fileSizeBytes", session.fileSizeBytes)
            put("durationSeconds", session.captureConfig.durationSeconds)
            put("bufferSizeKb", session.captureConfig.bufferSizeKb)
            put(
                "additionalCategories",
                buildJsonArray {
                    session.captureConfig.additionalCategories.forEach { add(JsonPrimitive(it)) }
                },
            )
            session.captureConfig.customConfigText?.let { put("customConfigText", it) }
            session.captureConfig.enabledProbes?.let { probes ->
                put("enabledProbes", buildJsonArray { probes.forEach { add(JsonPrimitive(it.name)) } })
            }
            session.notes?.let { put("notes", it) }
            put("isProtected", session.isProtected)
            session.artifact?.let { artifact -> put("artifact", json.parseToJsonElement(CaptureArtifactJson.encode(artifact))) }
        }

    private fun parseSession(element: kotlinx.serialization.json.JsonElement): TraceSession? {
        val objectValue = element as? JsonObject ?: return null
        return runCatching {
            val template =
                objectValue
                    .string("template")
                    ?.let { value -> PerfettoTraceTemplate.entries.firstOrNull { it.name == value } }
                    ?: PerfettoTraceTemplate.CUSTOM
            TraceSession(
                id = objectValue.string("id") ?: return null,
                traceFile = Path.of(objectValue.string("traceFile") ?: return null),
                captureConfig =
                    PerfettoCaptureConfig(
                        template = template,
                        targetPackage = objectValue.string("targetPackage"),
                        durationSeconds = objectValue.int("durationSeconds") ?: 10,
                        bufferSizeKb = objectValue.int("bufferSizeKb") ?: 32768,
                        additionalCategories = objectValue.stringArray("additionalCategories"),
                        customConfigText = objectValue.string("customConfigText"),
                        enabledProbes =
                            objectValue["enabledProbes"]?.let {
                                objectValue
                                    .stringArray("enabledProbes")
                                    .mapNotNull { name -> PerfettoProbe.entries.firstOrNull { probe -> probe.name == name } }
                                    .toSet()
                            },
                    ),
                deviceSerial = "",
                deviceModel = objectValue.string("deviceModel").orEmpty(),
                androidSdk = objectValue.int("androidSdk") ?: 0,
                capturedAt = Instant.parse(objectValue.string("capturedAt") ?: return null),
                durationNanos = objectValue.long("durationNanos") ?: 0,
                fileSizeBytes = objectValue.long("fileSizeBytes") ?: 0,
                notes = objectValue.string("notes"),
                isProtected = objectValue.boolean("isProtected") ?: false,
                artifact = objectValue["artifact"]?.let { CaptureArtifactJson.decode(it.toString()) },
            )
        }.getOrNull()
    }

    private fun <T> execute(
        code: String,
        block: () -> T,
    ): StudioResult<T> =
        try {
            StudioResult.Success(block())
        } catch (exception: java.io.IOException) {
            failure(code, exception)
        } catch (exception: kotlinx.serialization.SerializationException) {
            failure(code, exception)
        } catch (exception: IllegalArgumentException) {
            failure(code, exception)
        }

    private fun <T> failure(
        code: String,
        exception: Exception,
    ): StudioResult<T> =
        StudioResult.Failure(
            StudioError(
                category = ErrorCategory.IO,
                code = code,
                message = "Failed to access Perfetto session index: ${exception.message}",
                cause = exception,
            ),
        )

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

    private fun JsonObject.stringArray(key: String): List<String> =
        (this[key] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()

    companion object {
        private const val DEFAULT_LIMIT = 20

        fun defaultStorageDir(): Path =
            Paths.get(
                System.getProperty("user.home"),
                ".android-performance-studio",
                "perfetto-sessions",
            )
    }
}
