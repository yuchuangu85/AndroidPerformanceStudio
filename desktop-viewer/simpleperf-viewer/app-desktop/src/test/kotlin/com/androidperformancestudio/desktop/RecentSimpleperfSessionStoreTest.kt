package com.androidperformancestudio.desktop

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecentSimpleperfSessionStoreTest {
    @Test
    fun `recent sessions are de-duplicated ordered and persisted`() {
        val root = createTempDirectory("recent-simpleperf-test")
        try {
            val storage = root.resolve("state/recent.txt")
            val store = RecentSimpleperfSessionStore(storage, maximumEntries = 3)
            val first = root.resolve("session-one")
            val second = root.resolve("session-two")
            val third = root.resolve("session-three")
            val fourth = root.resolve("session-four")

            store.record(first)
            store.record(second)
            store.record(third)
            store.record(first)
            val result = store.record(fourth)

            assertEquals(
                listOf(fourth, first, third).map { it.toAbsolutePath().normalize() },
                result,
            )
            assertEquals(result, RecentSimpleperfSessionStore(storage, maximumEntries = 3).load())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `clear removes persisted recent sessions`() {
        val root = createTempDirectory("recent-simpleperf-clear-test")
        try {
            val storage = root.resolve("recent.txt")
            val store = RecentSimpleperfSessionStore(storage)
            store.record(root.resolve("session"))

            store.clear()

            assertTrue(store.load().isEmpty())
            assertTrue(Files.notExists(storage))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid persisted paths do not hide valid recent sessions`() {
        val root = createTempDirectory("recent-simpleperf-invalid-test")
        try {
            val storage = root.resolve("recent.txt")
            val valid = root.resolve("valid-session").toAbsolutePath().normalize()
            Files.writeString(storage, "\u0000\n$valid\n", StandardCharsets.UTF_8)

            assertEquals(listOf(valid), RecentSimpleperfSessionStore(storage).load())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
