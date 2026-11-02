package com.androidperformancestudio.storage

import com.androidperformancestudio.model.ProfileSample
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SQLiteInitialThreadSelectionTest {
    @Test
    fun `empty stacks do not win initial thread activity scoring`() =
        withStore { store ->
            store.connection.createStatement().use {
                it.execute("UPDATE sample SET leaf_callsite_id=NULL WHERE thread_id=200")
            }
            val report = store.projectCore(ProfileProjectionRequest(selectInitialThread = true))
            assertEquals(setOf(100), report.query.threadIds)
        }

    @Test
    fun `automatic opening chooses the sample active thread rather than the first track or largest event weight`() =
        withStore { store ->
            val report = store.projectCore(ProfileProjectionRequest(selectInitialThread = true))
            assertEquals(setOf(200), report.query.threadIds)
            assertEquals(2, report.overview.sampleCount)
            assertEquals(3, report.sessionOverview.sampleCount)
            assertEquals(2_000_000, report.flameGraph.totalWeight)
        }

    @Test
    fun `explicit thread selection wins over initial thread discovery`() =
        withStore { store ->
            val report =
                store.projectCore(
                    ProfileProjectionRequest(
                        query = ProfileQuery(threadIds = setOf(100)),
                        selectInitialThread = true,
                    ),
                )
            assertEquals(setOf(100), report.query.threadIds)
            assertEquals(1, report.overview.sampleCount)
        }

    @Test
    fun `ordinary aggregate queries remain aggregate`() =
        withStore { store ->
            val report = store.projectCore(ProfileProjectionRequest())
            assertEquals(emptySet(), report.query.threadIds)
            assertEquals(3, report.overview.sampleCount)
        }

    private fun withStore(block: (SQLiteSampleStore) -> Unit) {
        val path = Files.createTempDirectory("aps-thread-projection-")
        try {
            SQLiteSampleStore.open(path.resolve("profile.sqlite")).use { store ->
                store.importSamples(
                    sequenceOf(
                        ProfileSample(0, 100, 100, "cpu-clock", "loopOnce", 9_000_000),
                        ProfileSample(10, 100, 200, "cpu-clock", "loopOnce", 1_000_000),
                        ProfileSample(20, 100, 200, "cpu-clock", "loopOnce", 1_000_000),
                    ),
                )
                block(store)
            }
        } finally {
            path.toFile().deleteRecursively()
        }
    }
}
