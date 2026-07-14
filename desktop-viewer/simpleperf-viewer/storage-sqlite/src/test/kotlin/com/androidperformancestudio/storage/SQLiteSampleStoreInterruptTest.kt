package com.androidperformancestudio.storage

import org.sqlite.ProgressHandler
import java.nio.file.Files
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SQLiteSampleStoreInterruptTest {
    @Test
    fun `interrupt aborts an active native SQLite query`() {
        val database = Files.createTempDirectory("aps-sqlite-interrupt-").resolve("profile.sqlite")
        SQLiteSampleStore.open(database).use { store ->
            val queryEntered = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            ProgressHandler.setHandler(
                store.connection,
                1,
                object : ProgressHandler() {
                    override fun progress(): Int {
                        queryEntered.countDown()
                        return 0
                    }
                },
            )
            val queryThread =
                thread(name = "sqlite-interrupt-query") {
                    runCatching {
                        store.connection.createStatement().use { statement ->
                            statement.executeQuery(LONG_RUNNING_QUERY).use { result -> result.next() }
                        }
                    }.exceptionOrNull()?.let(failure::set)
                }

            assertTrue(queryEntered.await(1, SECONDS), "native SQLite query did not start")
            store.interrupt()
            queryThread.join(2_000)

            assertFalse(queryThread.isAlive, "interrupted native SQLite query did not terminate")
            assertIs<SQLException>(failure.get())
        }
    }

    private companion object {
        const val LONG_RUNNING_QUERY =
            "WITH RECURSIVE values_under_test(value) AS (" +
                "VALUES(0) UNION ALL SELECT value + 1 FROM values_under_test WHERE value < 1000000000" +
                ") SELECT SUM(value) FROM values_under_test"
    }
}
