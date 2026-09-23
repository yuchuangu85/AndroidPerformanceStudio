package com.androidperformancestudio.desktop.dashboard

import com.androidperformancestudio.platform.toolchain.RecentPathStore
import com.androidperformancestudio.perfetto.app.PerfettoRecentTraceReader
import com.androidperformancestudio.memory.app.MemoryRecentSessionReader
import com.androidperformancestudio.model.StudioResult
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

internal enum class StudioFeature {
    LAYOUT,
    CPU,
    TRACE,
    MEMORY,
}

internal data class StudioRecentItem(
    val id: String,
    val feature: StudioFeature,
    val title: String,
    val artifactPath: Path,
    val packageName: String? = null,
    val capturedAt: Instant? = null,
)

internal interface StudioOverviewSource {
    suspend fun recentItems(limit: Int): List<StudioRecentItem>
}

internal data class WorkspaceOverviewSnapshot(
    val items: List<StudioRecentItem>,
    val unavailableSourceCount: Int,
    val sourceCount: Int,
)

/** A read-only projection. Failure in one feature index must not block the dashboard. */
internal class WorkspaceOverviewRepository(
    private val sources: List<StudioOverviewSource>,
) {
    suspend fun recentItems(limit: Int): List<StudioRecentItem> = loadRecent(limit).items

    suspend fun loadRecent(limit: Int): WorkspaceOverviewSnapshot {
        if (limit <= 0) return WorkspaceOverviewSnapshot(emptyList(), 0, sources.size)
        var unavailableSourceCount = 0
        val lists = sources.map { source ->
            try {
                source.recentItems(limit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                unavailableSourceCount++
                emptyList()
            }
        }
        // Each feature store has newest-first order but no compatible capture timestamp. Interleave
        // the lists instead of inventing a cross-feature chronology from file modification times.
        val items = (0 until limit)
            .asSequence()
            .flatMap { index -> lists.asSequence().mapNotNull { it.getOrNull(index) } }
            .distinctBy(StudioRecentItem::id)
            .take(limit)
            .toList()
        return WorkspaceOverviewSnapshot(items, unavailableSourceCount, sources.size)
    }

    internal companion object {
        fun desktop(): WorkspaceOverviewRepository =
            WorkspaceOverviewRepository(
                listOf(
                    RecentPathOverviewSource(
                        feature = StudioFeature.LAYOUT,
                        store = RecentPathStore.desktop("recent-layout-inspector-archives.txt"),
                    ),
                    RecentPathOverviewSource(
                        feature = StudioFeature.CPU,
                        store = RecentPathStore.desktop("recent-simpleperf-sessions.txt"),
                    ),
                    PerfettoOverviewSource(PerfettoRecentTraceReader()),
                    MemoryOverviewSource(MemoryRecentSessionReader()),
                ),
            )
    }
}

internal class RecentPathOverviewSource(
    private val feature: StudioFeature,
    private val store: RecentPathStore,
) : StudioOverviewSource {
    init {
        require(feature == StudioFeature.LAYOUT || feature == StudioFeature.CPU)
    }

    override suspend fun recentItems(limit: Int): List<StudioRecentItem> =
        withContext(Dispatchers.IO) {
            store.loadResult().getOrThrow()
                .asSequence()
                .filter { path ->
                    when (feature) {
                        StudioFeature.LAYOUT -> Files.isRegularFile(path)
                        StudioFeature.CPU -> Files.isDirectory(path)
                        StudioFeature.TRACE -> false
                        StudioFeature.MEMORY -> false
                    }
                }
                .take(limit.coerceAtLeast(0))
                .map { path ->
                    StudioRecentItem(
                        id = "${feature.name}:$path",
                        feature = feature,
                        title = path.fileName?.toString() ?: path.toString(),
                        artifactPath = path,
                    )
                }.toList()
        }
}

internal class PerfettoOverviewSource(
    private val reader: PerfettoRecentTraceReader,
) : StudioOverviewSource {
    override suspend fun recentItems(limit: Int): List<StudioRecentItem> =
        withContext(Dispatchers.IO) {
            val traces = when (val result = reader.listRecentResult(limit)) {
                is StudioResult.Success -> result.value
                is StudioResult.Failure -> throw IllegalStateException(result.error.message, result.error.cause)
            }
            traces.map { trace ->
                StudioRecentItem(
                    id = "TRACE:${trace.sessionId}",
                    feature = StudioFeature.TRACE,
                    title = trace.traceFile.fileName?.toString() ?: trace.traceFile.toString(),
                    artifactPath = trace.traceFile,
                    packageName = trace.packageName,
                    capturedAt = trace.capturedAt,
                )
            }
        }
}

internal class MemoryOverviewSource(
    private val reader: MemoryRecentSessionReader,
) : StudioOverviewSource {
    override suspend fun recentItems(limit: Int): List<StudioRecentItem> =
        withContext(Dispatchers.IO) {
            reader.listRecent(limit).map { session ->
                StudioRecentItem(
                    id = "MEMORY:${session.sessionId}",
                    feature = StudioFeature.MEMORY,
                    title = session.hprofFile.fileName?.toString() ?: session.hprofFile.toString(),
                    artifactPath = session.hprofFile,
                    packageName = session.packageName,
                    capturedAt = session.capturedAt,
                )
            }
        }
}
