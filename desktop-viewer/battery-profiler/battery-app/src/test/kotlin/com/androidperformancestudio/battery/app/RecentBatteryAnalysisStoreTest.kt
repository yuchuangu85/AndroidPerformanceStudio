package com.androidperformancestudio.battery.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecentBatteryAnalysisStoreTest {
    @Test
    fun `recent analyses are de-duplicated ordered bounded and persisted`() {
        val root = createTempDirectory("recent-battery-test")
        try {
            val storage = root.resolve("state/recent.txt")
            val store = RecentBatteryAnalysisStore(storage, maximumEntries = 3)
            val first = root.resolve("one.json")
            val second = root.resolve("two.json")
            val third = root.resolve("three.json")
            val fourth = root.resolve("four.json")

            store.record(first)
            store.record(second)
            store.record(third)
            store.record(first)
            val result = store.record(fourth)

            assertEquals(
                listOf(fourth, first, third).map { it.toAbsolutePath().normalize() },
                result,
            )
            assertEquals(result, RecentBatteryAnalysisStore(storage, maximumEntries = 3).load())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `clear removes persisted recent analyses`() {
        val root = createTempDirectory("recent-battery-clear-test")
        try {
            val storage = root.resolve("recent.txt")
            val store = RecentBatteryAnalysisStore(storage)
            store.record(root.resolve("report.json"))

            store.clear()

            assertTrue(store.load().isEmpty())
            assertTrue(Files.notExists(storage))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid persisted paths do not hide valid recent analyses`() {
        val root = createTempDirectory("recent-battery-invalid-test")
        try {
            val storage = root.resolve("recent.txt")
            val valid = root.resolve("valid.json").toAbsolutePath().normalize()
            Files.writeString(storage, "\u0000\n$valid\n", StandardCharsets.UTF_8)

            assertEquals(listOf(valid), RecentBatteryAnalysisStore(storage).load())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
