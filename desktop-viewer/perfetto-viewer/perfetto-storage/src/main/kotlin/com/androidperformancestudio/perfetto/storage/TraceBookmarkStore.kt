package com.androidperformancestudio.perfetto.storage

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/** A timeline position the user pinned for one trace artifact. */
data class TraceBookmark(
    val id: String,
    val artifactId: String,
    val label: String,
    val timestampNanos: Long,
    val createdAt: Instant,
    val sourceQueryId: String? = null,
)

/** Durable bookmark index stored next to the Perfetto session index. */
class TraceBookmarkStore(
    private val storageDir: Path = TraceSessionStore.defaultStorageDir(),
) {
    private val indexFile: Path = storageDir.resolve("bookmarks.json")
    private val lock = Any()
    private val json = Json { prettyPrint = true }

    fun list(artifactId: String): StudioResult<List<TraceBookmark>> =
        synchronized(lock) {
            execute("BOOKMARK_LIST_FAILED") {
                readBookmarks().filter { it.artifactId == artifactId }.sortedBy(TraceBookmark::timestampNanos)
            }
        }

    fun add(bookmark: TraceBookmark): StudioResult<Unit> =
        synchronized(lock) {
            execute("BOOKMARK_SAVE_FAILED") {
                require(bookmark.label.isNotBlank()) { "bookmark label must not be blank" }
                require(bookmark.timestampNanos >= 0) { "bookmark timestamp must not be negative" }
                writeBookmarks(readBookmarks().filterNot { it.id == bookmark.id } + bookmark)
            }
        }

    fun delete(bookmarkId: String): StudioResult<Unit> =
        synchronized(lock) {
            execute("BOOKMARK_DELETE_FAILED") { writeBookmarks(readBookmarks().filterNot { it.id == bookmarkId }) }
        }

    private fun readBookmarks(): List<TraceBookmark> {
        val content = if (Files.isRegularFile(indexFile)) Files.readString(indexFile).trim() else ""
        val root = content.takeIf(String::isNotBlank)?.let { json.parseToJsonElement(it) as? JsonArray }
        return root?.mapNotNull { element -> (element as? JsonObject)?.let(::parseBookmark) }.orEmpty()
    }

    private fun parseBookmark(objectValue: JsonObject): TraceBookmark? =
        runCatching {
            TraceBookmark(
                id = objectValue.string("id") ?: return null,
                artifactId = objectValue.string("artifactId") ?: return null,
                label = objectValue.string("label") ?: return null,
                timestampNanos = objectValue["timestampNanos"]?.jsonPrimitive?.longOrNull ?: return null,
                createdAt = Instant.parse(objectValue.string("createdAt") ?: return null),
                sourceQueryId = objectValue.string("sourceQueryId"),
            )
        }.getOrNull()

    private fun writeBookmarks(bookmarks: List<TraceBookmark>) {
        Files.createDirectories(storageDir)
        val root =
            buildJsonArray {
                bookmarks.forEach { bookmark ->
                    add(
                        buildJsonObject {
                            put("id", bookmark.id)
                            put("artifactId", bookmark.artifactId)
                            put("label", bookmark.label)
                            put("timestampNanos", bookmark.timestampNanos)
                            put("createdAt", bookmark.createdAt.toString())
                            bookmark.sourceQueryId?.let { put("sourceQueryId", it) }
                        },
                    )
                }
            }
        val temporary = Files.createTempFile(storageDir, "bookmarks", ".json.tmp")
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
                message = "Failed to access trace bookmarks: ${exception.message}",
                cause = exception,
            ),
        )

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
