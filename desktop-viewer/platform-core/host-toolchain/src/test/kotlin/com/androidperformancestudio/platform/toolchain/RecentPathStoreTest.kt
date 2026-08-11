package com.androidperformancestudio.platform.toolchain

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecentPathStoreTest {
    @Test
    fun `paths are normalized de-duplicated bounded and persisted`() {
        val root = createTempDirectory("recent-paths")
        try {
            val storage = root.resolve("state/recent.txt")
            val store = RecentPathStore(storage, maximumEntries = 3)
            val first = root.resolve("one")
            val second = root.resolve("two")
            val third = root.resolve("three")
            val fourth = root.resolve("four")

            store.record(first)
            store.record(second)
            store.record(third)
            store.record(first)
            val result = store.record(fourth)

            assertEquals(listOf(fourth, first, third).map { it.toAbsolutePath().normalize() }, result)
            assertEquals(result, RecentPathStore(storage, maximumEntries = 3).load())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `clear removes persisted paths`() {
        val root = createTempDirectory("recent-paths-clear")
        try {
            val storage = root.resolve("recent.txt")
            val store = RecentPathStore(storage)
            store.record(root.resolve("entry"))

            store.clear()

            assertTrue(store.load().isEmpty())
            assertTrue(Files.notExists(storage))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid persisted paths do not hide valid paths`() {
        val root = createTempDirectory("recent-paths-invalid")
        try {
            val storage = root.resolve("recent.txt")
            val valid = root.resolve("valid").toAbsolutePath().normalize()
            Files.writeString(storage, "\u0000\n$valid\n", StandardCharsets.UTF_8)

            assertEquals(listOf(valid), RecentPathStore(storage).load())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
