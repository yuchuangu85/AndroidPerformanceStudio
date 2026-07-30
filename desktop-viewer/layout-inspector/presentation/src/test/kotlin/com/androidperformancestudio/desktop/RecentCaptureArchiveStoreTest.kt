package com.androidperformancestudio.desktop

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecentCaptureArchiveStoreTest {
    @Test
    fun `recent archives are de-duplicated ordered bounded and persisted`() {
        val root = Files.createTempDirectory("recent-layout-archives")
        try {
            val storage = root.resolve("state/recent.txt")
            val store = RecentCaptureArchiveStore(storage, maximumEntries = 3)
            val first = root.resolve("one.apinspect")
            val second = root.resolve("two.apinspect")
            val third = root.resolve("three.apinspect")
            val fourth = root.resolve("four.apinspect")

            store.record(first)
            store.record(second)
            store.record(third)
            store.record(first)
            val result = store.record(fourth)

            assertEquals(
                listOf(fourth, first, third).map { it.toAbsolutePath().normalize() },
                result,
            )
            assertEquals(
                result,
                RecentCaptureArchiveStore(storage, maximumEntries = 3).load(),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `clear removes persisted recent archives`() {
        val root = Files.createTempDirectory("recent-layout-archives-clear")
        try {
            val storage = root.resolve("recent.txt")
            val store = RecentCaptureArchiveStore(storage)
            store.record(root.resolve("capture.apinspect"))

            store.clear()

            assertTrue(store.load().isEmpty())
            assertTrue(Files.notExists(storage))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid persisted paths do not hide valid recent archives`() {
        val root = Files.createTempDirectory("recent-layout-archives-invalid")
        try {
            val storage = root.resolve("recent.txt")
            val valid = root.resolve("valid.apinspect").toAbsolutePath().normalize()
            Files.writeString(storage, "\u0000\n$valid\n", StandardCharsets.UTF_8)

            assertEquals(listOf(valid), RecentCaptureArchiveStore(storage).load())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
