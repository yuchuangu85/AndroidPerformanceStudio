package com.androidperformancestudio.session.storage

import com.androidperformancestudio.session.model.ProfilerSession
import com.androidperformancestudio.session.model.SessionSegment
import com.androidperformancestudio.session.model.SessionSegmentKind
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionStoreTest {
    @Test
    fun `save find list delete round-trips sessions`() {
        val store = SessionStore(Files.createTempDirectory("sessions").resolve("sessions.json"))
        val session =
            ProfilerSession(
                id = "s1",
                name = "app-demo",
                packageName = "com.example",
                createdAtEpochMillis = 1_000L,
                segments =
                    listOf(
                        SessionSegment(
                            id = "seg1",
                            kind = SessionSegmentKind.NATIVE_HEAP,
                            label = "native.pb",
                            artifactPath = "/tmp/native.pb",
                            capturedAtEpochMillis = 2_000L,
                        ),
                    ),
            )

        store.save(session)
        assertEquals(session, store.find("s1"))
        assertEquals(1, store.listRecent().size)

        store.save(session.copy(name = "renamed"))
        assertEquals(1, store.listRecent().size)
        assertEquals("renamed", store.find("s1")?.name)

        store.delete("s1")
        assertEquals(0, store.listRecent().size)
        assertNull(store.find("s1"))
    }

    @Test
    fun `empty store returns an empty list`() {
        val store = SessionStore(Files.createTempDirectory("sessions").resolve("missing.json"))
        assertEquals(0, store.listRecent().size)
    }

    @Test
    fun `sessions are listed newest first`() {
        val store = SessionStore(Files.createTempDirectory("sessions").resolve("sessions.json"))
        store.save(ProfilerSession(id = "old", name = "old", createdAtEpochMillis = 1_000L))
        store.save(ProfilerSession(id = "new", name = "new", createdAtEpochMillis = 2_000L))
        assertEquals(listOf("new", "old"), store.listRecent().map { it.id })
    }
}
