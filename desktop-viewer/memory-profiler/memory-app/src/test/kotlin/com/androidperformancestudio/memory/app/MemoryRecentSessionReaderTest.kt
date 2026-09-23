package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.storage.MemorySessionMetadata
import com.androidperformancestudio.memory.storage.SqliteMemorySessionStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

class MemoryRecentSessionReaderTest {
    @TempDir lateinit var root: Path

    @Test
    fun `missing index returns empty without creating storage`() {
        val dataRoot = root.resolve("missing")

        assertEquals(emptyList(), MemoryRecentSessionReader(dataRoot).listRecent(6))
        assertFalse(Files.exists(dataRoot))
    }

    @Test
    fun `reader projects only sessions with existing heap files`() {
        val database = root.resolve("memory-sessions.db")
        val converted = Files.writeString(root.resolve("converted.hprof"), "heap")
        val raw = Files.writeString(root.resolve("raw.hprof"), "heap")
        SqliteMemorySessionStore.open(database).use { store ->
            store.upsert(session("missing", root.resolve("gone.hprof"), 3))
            store.upsert(session("converted", raw, 2, converted))
            store.upsert(session("raw", raw, 1))
        }
        val before = Files.readAllBytes(database)

        val items = MemoryRecentSessionReader(root).listRecent(2)

        assertEquals(listOf("converted", "raw"), items.map(MemoryRecentSession::sessionId))
        assertEquals(converted, items.first().hprofFile)
        assertEquals(raw, items.last().hprofFile)
        assertEquals("com.example.app", items.first().packageName)
        assertEquals(Instant.ofEpochMilli(2), items.first().capturedAt)
        assertEquals(true, before.contentEquals(Files.readAllBytes(database)))
    }

    @Test
    fun `malformed index fails rather than pretending there are no sessions`() {
        Files.writeString(root.resolve("memory-sessions.db"), "not a sqlite database")

        assertFails { MemoryRecentSessionReader(root).listRecent(6) }
    }

    private fun session(
        id: String,
        raw: Path,
        capturedAtMillis: Long,
        converted: Path? = null,
    ) = MemorySessionMetadata(
        sessionId = id,
        packageName = "com.example.app",
        deviceSerial = "",
        capturedAt = Instant.ofEpochMilli(capturedAtMillis),
        rawHprofFile = raw,
        convertedHprofFile = converted,
    )
}
