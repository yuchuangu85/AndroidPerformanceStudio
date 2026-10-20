package com.androidperformancestudio.perfetto.storage

import com.androidperformancestudio.model.StudioResult
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TraceBookmarkStoreTest {
    @Test
    fun `bookmarks persist per artifact in timestamp order and survive reload`() {
        val directory = Files.createTempDirectory("trace-bookmarks")
        val store = TraceBookmarkStore(directory)
        store.add(bookmark("b2", "artifact-1", 2_000))
        store.add(bookmark("b1", "artifact-1", 1_000, sourceQueryId = "frame_jank"))
        store.add(bookmark("other", "artifact-2", 500))

        val reloaded = assertIs<StudioResult.Success<List<TraceBookmark>>>(TraceBookmarkStore(directory).list("artifact-1")).value
        assertEquals(listOf("b1", "b2"), reloaded.map(TraceBookmark::id))
        assertEquals("frame_jank", reloaded.first().sourceQueryId)

        store.delete("b1")
        assertEquals(listOf("b2"), assertIs<StudioResult.Success<List<TraceBookmark>>>(store.list("artifact-1")).value.map { it.id })
    }

    @Test
    fun `invalid bookmarks and corrupt entries fail without losing valid entries`() {
        val directory = Files.createTempDirectory("trace-bookmarks-invalid")
        val store = TraceBookmarkStore(directory)
        assertIs<StudioResult.Failure>(store.add(bookmark("blank", "artifact-1", 1).copy(label = " ")))
        assertIs<StudioResult.Failure>(store.add(bookmark("negative", "artifact-1", -1)))
        Files.writeString(
            directory.resolve("bookmarks.json"),
            """[{"id":"ok","artifactId":"a","label":"Frame","timestampNanos":5,"createdAt":"2026-10-15T00:00:00Z"},{"id":"bad"}]""",
        )
        assertEquals(listOf("ok"), assertIs<StudioResult.Success<List<TraceBookmark>>>(store.list("a")).value.map { it.id })
    }

    private fun bookmark(
        id: String,
        artifactId: String,
        timestamp: Long,
        sourceQueryId: String? = null,
    ) = TraceBookmark(id, artifactId, "Bookmark $id", timestamp, Instant.parse("2026-10-15T00:00:00Z"), sourceQueryId)
}
