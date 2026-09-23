package com.androidperformancestudio.desktop

import com.androidperformancestudio.desktop.dashboard.RecentPathOverviewSource
import com.androidperformancestudio.desktop.dashboard.StudioFeature
import com.androidperformancestudio.desktop.dashboard.StudioOverviewSource
import com.androidperformancestudio.desktop.dashboard.StudioRecentItem
import com.androidperformancestudio.desktop.dashboard.WorkspaceOverviewRepository
import com.androidperformancestudio.platform.toolchain.RecentPathStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class WorkspaceOverviewRepositoryTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `recent path source exposes only existing artifacts and never records during read`() = runBlocking {
        val index = directory.resolve("recent.txt")
        val store = RecentPathStore(index)
        val existing = Files.writeString(directory.resolve("capture.apinspect"), "archive")
        val missing = directory.resolve("deleted.apinspect")
        store.record(existing)
        store.record(missing)
        val before = Files.readString(index)

        val items = RecentPathOverviewSource(StudioFeature.LAYOUT, store).recentItems(5)

        assertEquals(listOf(existing), items.map(StudioRecentItem::artifactPath))
        assertEquals("capture.apinspect", items.single().title)
        assertEquals(before, Files.readString(index))
    }

    @Test
    fun `CPU recent source retains session directories rather than treating them as missing files`() = runBlocking {
        val store = RecentPathStore(directory.resolve("cpu-recent.txt"))
        val session = Files.createDirectory(directory.resolve("session.simpleperf"))
        store.record(session)

        val items = RecentPathOverviewSource(StudioFeature.CPU, store).recentItems(5)

        assertEquals(listOf(session), items.map(StudioRecentItem::artifactPath))
    }

    @Test
    fun `overview isolates failed sources and interleaves independent recent indexes`() = runBlocking {
        fun item(feature: StudioFeature, name: String): StudioRecentItem =
            StudioRecentItem("${feature.name}:$name", feature, name, Path.of(name))
        val cpu = object : StudioOverviewSource {
            override suspend fun recentItems(limit: Int) = listOf(item(StudioFeature.CPU, "cpu-1"), item(StudioFeature.CPU, "cpu-2"))
        }
        val broken = object : StudioOverviewSource {
            override suspend fun recentItems(limit: Int): List<StudioRecentItem> = error("corrupt index")
        }
        val layout = object : StudioOverviewSource {
            override suspend fun recentItems(limit: Int) = listOf(item(StudioFeature.LAYOUT, "layout-1"))
        }

        val repository = WorkspaceOverviewRepository(listOf(cpu, broken, layout))

        assertEquals(listOf("cpu-1", "layout-1", "cpu-2"), repository.recentItems(3).map(StudioRecentItem::title))
        val snapshot = repository.loadRecent(3)
        assertEquals(1, snapshot.unavailableSourceCount)
        assertEquals(3, snapshot.sourceCount)
        assertTrue(repository.recentItems(0).isEmpty())
    }

    @Test
    fun `overview reports unavailable indexes instead of treating them as empty`() = runBlocking {
        val corruptIndex = Files.createDirectory(directory.resolve("recent.txt"))
        val repository = WorkspaceOverviewRepository(
            listOf(RecentPathOverviewSource(StudioFeature.LAYOUT, RecentPathStore(corruptIndex))),
        )

        val snapshot = repository.loadRecent(6)

        assertTrue(snapshot.items.isEmpty())
        assertEquals(1, snapshot.unavailableSourceCount)
        assertEquals(1, snapshot.sourceCount)
    }
}
