package dev.agentperf.memory.storage

import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqliteMemorySessionStoreTest {
    @Test
    fun `stores and loads memory session metadata with raw and converted paths`() {
        val database = createTempDirectory("memory-db").resolve("sessions.db")
        val metadata =
            MemorySessionMetadata(
                sessionId = "session-1",
                packageName = "com.example.debug",
                deviceSerial = "device-1",
                capturedAt = Instant.ofEpochMilli(1234L),
                rawHprofFile = Path("raw.hprof"),
                convertedHprofFile = Path("converted.hprof"),
                classCount = 3,
                objectCount = 10,
                shallowSizeBytes = 2048L,
            )

        SqliteMemorySessionStore.open(database).use { store ->
            store.upsert(metadata)
            assertEquals(metadata, store.find("session-1"))
        }

        SqliteMemorySessionStore.open(database).use { reopened ->
            assertEquals(metadata, reopened.find("session-1"))
        }
    }

    @Test
    fun `indexes sessions by newest capture first`() {
        val database = createTempDirectory("memory-db").resolve("sessions.db")
        SqliteMemorySessionStore.open(database).use { store ->
            store.upsert(metadata("old", 1L))
            store.upsert(metadata("new", 3L))
            store.upsert(metadata("middle", 2L))

            assertEquals(listOf("new", "middle"), store.listRecent(limit = 2).map { it.sessionId })
        }
    }

    @Test
    fun `upsert replaces existing session metadata`() {
        val database = createTempDirectory("memory-db").resolve("sessions.db")
        SqliteMemorySessionStore.open(database).use { store ->
            store.upsert(metadata("same", 1L, converted = Path("old.hprof")))
            store.upsert(metadata("same", 2L, converted = null))

            val loaded = store.find("same")
            assertEquals(Instant.ofEpochMilli(2L), loaded?.capturedAt)
            assertNull(loaded?.convertedHprofFile)
            assertEquals(1, store.listRecent().size)
        }
    }

    private fun metadata(
        id: String,
        capturedAtMillis: Long,
        converted: java.nio.file.Path? = Path("$id.converted.hprof"),
    ): MemorySessionMetadata =
        MemorySessionMetadata(
            sessionId = id,
            packageName = "com.example.$id",
            deviceSerial = "device-1",
            capturedAt = Instant.ofEpochMilli(capturedAtMillis),
            rawHprofFile = Path("$id.raw.hprof"),
            convertedHprofFile = converted,
        )
}
