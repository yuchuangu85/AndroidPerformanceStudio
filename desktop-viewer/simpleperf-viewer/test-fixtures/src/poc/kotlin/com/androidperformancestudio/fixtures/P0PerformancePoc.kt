package com.androidperformancestudio.fixtures

import com.androidperformancestudio.application.ProfileProjectionLoader
import com.androidperformancestudio.application.ProfileWorkspaceController
import com.androidperformancestudio.application.ProfileWorkspaceLoadState
import com.androidperformancestudio.application.sqliteProjectionLoader
import com.androidperformancestudio.model.ProfileSample
import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameGraphRows
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.storage.ProfileQuery
import com.androidperformancestudio.storage.SQLiteSampleStore
import com.androidperformancestudio.visualization.FlameGraphLayout
import com.androidperformancestudio.visualization.FlameViewport
import com.androidperformancestudio.visualization.TimeViewport
import com.androidperformancestudio.visualization.TimelineDensityIndex
import com.androidperformancestudio.visualization.VisibleFlameLayout
import com.androidperformancestudio.visualization.VisibleFlameNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.io.path.writeText
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.system.measureNanoTime

private const val RECORD_COUNT = 1_000_000
private const val TIMELINE_BUCKET_COUNT = 100_000
private const val FLAME_NODE_COUNT = 100_000
private const val FLAME_ROW_COUNT = 64
private const val FLAME_VISIBLE_ROW_COUNT = 20
private const val FLAME_ROW_HEIGHT_PX = 18f
private const val FRAME_COUNT = 240
private const val FIRST_THREAD_ID = 20_000
private const val SECOND_THREAD_ID = 20_001
private const val PROJECTION_TIMEOUT_MILLIS = 120_000L
private const val PROJECTION_CLEANUP_TIMEOUT_MILLIS = 10_000L
private const val HEAP_SAMPLER_CLOSE_TIMEOUT_MILLIS = 5_000L

fun main(args: Array<String>) {
    require(args.size == 1) { "Expected the report directory as the only argument" }
    val reportDirectory = Path.of(args.single()).createDirectories()
    val platform = platformId()
    val sessionDirectory = Files.createTempDirectory("aps-million-record-")
    val database = sessionDirectory.resolve("profile.sqlite")
    val heapSampler = PeakHeapSampler()
    try {
        runP0(reportDirectory, platform, sessionDirectory, database, heapSampler)
    } finally {
        try {
            heapSampler.close()
        } finally {
            deleteRecursively(sessionDirectory)
        }
    }
}

private fun runP0(
    reportDirectory: Path,
    platform: String,
    sessionDirectory: Path,
    database: Path,
    heapSampler: PeakHeapSampler,
) {
    forceGarbageCollection()
    val heapBeforeBytes = usedHeapBytes()
    heapSampler.start()

    val importNanos: Long
    val countQueryNanos: Long
    val topQueryNanos: Long
    var databaseBytes: Long
    SQLiteSampleStore.open(database).use { store ->
        importNanos =
            measureNanoTime {
                val importResult = store.importSamples(syntheticSamples(RECORD_COUNT))
                check(importResult.importedSamples == RECORD_COUNT.toLong())
            }
        countQueryNanos = measureNanoTime { check(store.sampleCount() == RECORD_COUNT.toLong()) }
        topQueryNanos = measureNanoTime { check(store.topSymbols(20).size == 20) }
    }
    databaseBytes = database.fileSize()
    verifyLatestMillionSampleProjection(sessionDirectory)

    lateinit var timelineIndex: TimelineDensityIndex
    val timelineBuildNanos =
        measureNanoTime {
            timelineIndex =
                TimelineDensityIndex.build(
                    samples = syntheticSamples(RECORD_COUNT),
                    startNanos = 0,
                    endNanosExclusive = RECORD_COUNT.toLong(),
                    bucketCount = TIMELINE_BUCKET_COUNT,
                )
        }
    val timelineFrames =
        frameDurations {
            val iteration = it
            val duration = 50_000L + iteration % 8 * 25_000L
            val start = (iteration * 3_571L) % (RECORD_COUNT - duration)
            val frame = timelineIndex.project(TimeViewport(start, start + duration), widthPixels = 1_200)
            check(frame.columns.size == 1_200)
        }

    val flameSnapshot = syntheticFlameSnapshot(FLAME_NODE_COUNT)
    val flameFrames =
        frameDurations {
            val iteration = it
            FlameLayoutBlackhole.consume(
                FlameGraphLayout.layout(
                    snapshot = flameSnapshot,
                    viewport = flameViewport(iteration),
                ),
            )
        }
    verifyFlameLayoutBenchmarkResult(
        layout = FlameLayoutBlackhole.retainedResult(),
        viewport = flameViewport(FRAME_COUNT - 1),
    )

    heapSampler.close()
    val peakHeapBytes = heapSampler.peakBytes.coerceAtLeast(usedHeapBytes())
    val report =
        buildReport(
            platform = platform,
            importNanos = importNanos,
            countQueryNanos = countQueryNanos,
            topQueryNanos = topQueryNanos,
            databaseBytes = databaseBytes,
            heapBeforeBytes = heapBeforeBytes,
            peakHeapBytes = peakHeapBytes,
            timelineBuildNanos = timelineBuildNanos,
            timelineFrames = timelineFrames,
            flameFrames = flameFrames,
        )
    val reportFile = reportDirectory.resolve("p0-performance-$platform.json")
    reportFile.writeText(report)
    println("P0 performance report: $reportFile")
    println(report)
}

private fun verifyLatestMillionSampleProjection(sessionDirectory: Path) {
    val firstLoadEntered = CompletableDeferred<Int>()
    val firstLoadMayComplete = CompletableDeferred<Unit>()
    val productionLoader = sqliteProjectionLoader()
    val observedProductionLoader =
        ProfileProjectionLoader { session, query ->
            if (query.threadIds == setOf(FIRST_THREAD_ID)) {
                coroutineScope {
                    val delegate =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            productionLoader.load(session, query)
                        }
                    check(firstLoadEntered.complete(query.threadIds.single()))
                    firstLoadMayComplete.await()
                    delegate.await()
                }
            } else {
                productionLoader.load(session, query)
            }
        }
    val scopeJob = SupervisorJob()
    val scope = CoroutineScope(scopeJob + Dispatchers.Default)
    val controller = ProfileWorkspaceController(scope, observedProductionLoader)
    runBlocking {
        try {
            val terminalState =
                withTimeout(PROJECTION_TIMEOUT_MILLIS) {
                    controller.openSession(sessionDirectory)
                    check(!firstLoadEntered.isCompleted)
                    controller.updateQuery(ProfileQuery(threadIds = setOf(FIRST_THREAD_ID)))
                    check(firstLoadEntered.await() == FIRST_THREAD_ID)
                    controller.updateQuery(ProfileQuery(threadIds = setOf(SECOND_THREAD_ID)))
                    check(firstLoadMayComplete.complete(Unit))
                    controller.state.first { state ->
                        state.loadState is ProfileWorkspaceLoadState.Ready ||
                            state.loadState is ProfileWorkspaceLoadState.Failed
                    }
                }
            check(terminalState.snapshot?.query?.threadIds == setOf(SECOND_THREAD_ID))
            check(terminalState.loadState is ProfileWorkspaceLoadState.Ready)
        } finally {
            firstLoadMayComplete.complete(Unit)
            controller.close()
            withContext(NonCancellable) {
                withTimeout(PROJECTION_CLEANUP_TIMEOUT_MILLIS) {
                    scopeJob.cancelAndJoin()
                }
            }
        }
    }
}

private fun syntheticSamples(count: Int): Sequence<ProfileSample> =
    (0 until count).asSequence().map { index ->
        ProfileSample(
            timestampNanos = index.toLong(),
            processId = 10_000 + index % 3,
            threadId = 20_000 + index % 64,
            eventType = if (index % 8 == 0) "cpu-clock" else "cpu-cycles",
            symbolName = "symbol_${index % 256}",
            eventCount = 1L + index % 7,
        )
    }

private fun syntheticFlameSnapshot(count: Int): FlameGraphSnapshot {
    val starts = DoubleArray(count) { index -> ((index * 9_973L) % 1_000L) / 1_000.0 }
    val ends = DoubleArray(count) { index -> (starts[index] + 0.01).coerceAtMost(1.0) }
    val nodeIndexesByRow =
        List(FLAME_ROW_COUNT) { row ->
            IntArray((count + FLAME_ROW_COUNT - row - 1) / FLAME_ROW_COUNT) { position ->
                row + position * FLAME_ROW_COUNT
            }
        }
    return FlameGraphSnapshot(
        query = CallStackAnalysisQuery(),
        callNodes =
            CallNodeTable(
                ids = LongArray(count) { it.toLong() },
                parentIndexes = IntArray(count) { -1 },
                frameIds = LongArray(count),
                depths = IntArray(count) { it % FLAME_ROW_COUNT },
                inclusiveWeights = LongArray(count) { 1 },
                selfWeights = LongArray(count) { 1 },
                sampleCounts = LongArray(count) { 1 },
                threadCounts = IntArray(count) { 1 },
                categories = List(count) { null },
                framesById = emptyMap(),
            ),
        rows = FlameGraphRows(nodeIndexesByRow, starts, ends, startsAtBottom = true),
        totalWeight = count.toLong(),
        emptyReason = null,
        invalidTransforms = emptyList(),
    )
}

private fun flameViewport(iteration: Int): FlameViewport =
    FlameViewport(
        widthPx = 1_200,
        heightPx = 360,
        scrollRow = iteration % (FLAME_ROW_COUNT - FLAME_VISIBLE_ROW_COUNT),
        rowHeightPx = FLAME_ROW_HEIGHT_PX,
    )

private fun verifyFlameLayoutBenchmarkResult(
    layout: VisibleFlameLayout,
    viewport: FlameViewport,
) {
    validateFlameLayoutBenchmarkResult(layout, viewport)

    check(
        runCatching {
            validateFlameLayoutBenchmarkResult(
                VisibleFlameLayout(emptyList(), layout.materializedRowRange),
                viewport,
            )
        }.isFailure,
    ) { "Flame layout benchmark validation accepted an empty result" }

    val representativeNodeIndex = viewport.scrollRow
    val wrongGeometry =
        VisibleFlameLayout(
            layout.nodes.map { node ->
                if (node.nodeIndex == representativeNodeIndex) node.copy(width = node.width + 1f) else node
            },
            layout.materializedRowRange,
        )
    check(runCatching { validateFlameLayoutBenchmarkResult(wrongGeometry, viewport) }.isFailure) {
        "Flame layout benchmark validation accepted incorrect geometry"
    }
}

private fun validateFlameLayoutBenchmarkResult(
    layout: VisibleFlameLayout,
    viewport: FlameViewport,
) {
    val firstVisibleRow = viewport.scrollRow
    val expectedRange =
        IntRange(
            firstVisibleRow - viewport.overscanRows,
            firstVisibleRow + FLAME_VISIBLE_ROW_COUNT - 1 + viewport.overscanRows,
        )
    check(layout.materializedRowRange == expectedRange) {
        "Unexpected flame materialized rows: ${layout.materializedRowRange}, expected $expectedRange"
    }
    val expectedNodeCount = expectedRange.sumOf(::rowNodeCount)
    check(layout.nodes.size == expectedNodeCount) {
        "Unexpected flame node count: ${layout.nodes.size}, expected $expectedNodeCount"
    }

    val representativeNode = layout.nodes.single { node -> node.nodeIndex == firstVisibleRow }
    check(representativeNode == expectedSyntheticFlameNode(firstVisibleRow, viewport)) {
        "Unexpected representative flame node: $representativeNode"
    }
}

private fun rowNodeCount(row: Int): Int = (FLAME_NODE_COUNT - row + FLAME_ROW_COUNT - 1) / FLAME_ROW_COUNT

private fun expectedSyntheticFlameNode(
    nodeIndex: Int,
    viewport: FlameViewport,
): VisibleFlameNode {
    val normalizedStart = ((nodeIndex * 9_973L) % 1_000L) / 1_000.0
    val normalizedEnd = (normalizedStart + 0.01).coerceAtMost(1.0)
    val snappedStart = (normalizedStart * viewport.widthPx).roundToLong().toFloat()
    val snappedEnd = (normalizedEnd * viewport.widthPx).roundToLong().toFloat()
    return VisibleFlameNode(
        nodeIndex = nodeIndex,
        nodeId = FlameCallNodeId(nodeIndex.toLong()),
        x = snappedStart,
        y = viewport.heightPx - FLAME_ROW_HEIGHT_PX,
        width = snappedEnd - snappedStart,
        height = FLAME_ROW_HEIGHT_PX,
    )
}

private object FlameLayoutBlackhole {
    @Volatile
    private var result: VisibleFlameLayout? = null

    fun consume(layout: VisibleFlameLayout) {
        result = layout
    }

    fun retainedResult(): VisibleFlameLayout = checkNotNull(result) { "No flame layout result escaped the benchmark" }
}

private fun frameDurations(block: (Int) -> Unit): List<Long> {
    repeat(20, block)
    return List(FRAME_COUNT) { iteration -> measureNanoTime { block(iteration) } }
}

private class PeakHeapSampler : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val peak = AtomicLong(0)
    private var samplerThread: Thread? = null

    val peakBytes: Long
        get() = peak.get()

    fun start() {
        check(running.compareAndSet(false, true))
        samplerThread =
            thread(name = "p0-heap-sampler", isDaemon = true) {
                while (running.get()) {
                    peak.accumulateAndGet(usedHeapBytes(), ::maxOf)
                    Thread.sleep(5)
                }
            }
    }

    override fun close() {
        running.set(false)
        samplerThread?.let { thread ->
            thread.join(HEAP_SAMPLER_CLOSE_TIMEOUT_MILLIS)
            check(!thread.isAlive) { "P0 heap sampler did not stop" }
        }
    }
}

private fun buildReport(
    platform: String,
    importNanos: Long,
    countQueryNanos: Long,
    topQueryNanos: Long,
    databaseBytes: Long,
    heapBeforeBytes: Long,
    peakHeapBytes: Long,
    timelineBuildNanos: Long,
    timelineFrames: List<Long>,
    flameFrames: List<Long>,
): String =
    """
    {
      "schemaVersion": 1,
      "generatedAt": "${java.time.Instant.now()}",
      "platform": "$platform",
      "javaVersion": "${System.getProperty("java.version")}",
      "availableProcessors": ${Runtime.getRuntime().availableProcessors()},
      "heapLimitBytes": ${Runtime.getRuntime().maxMemory()},
      "recordCount": $RECORD_COUNT,
      "sqlite": {
        "batchSize": ${SQLiteSampleStore.DEFAULT_BATCH_SIZE},
        "importMilliseconds": ${milliseconds(importNanos)},
        "recordsPerSecond": ${(RECORD_COUNT / (importNanos / 1_000_000_000.0)).roundToLong()},
        "countQueryMilliseconds": ${milliseconds(countQueryNanos)},
        "top20QueryMilliseconds": ${milliseconds(topQueryNanos)},
        "databaseBytes": $databaseBytes
      },
      "memory": {
        "heapBeforeBytes": $heapBeforeBytes,
        "peakHeapBytes": $peakHeapBytes,
        "peakHeapDeltaBytes": ${(peakHeapBytes - heapBeforeBytes).coerceAtLeast(0)}
      },
      "timeline": {
        "sourceRecordCount": $RECORD_COUNT,
        "indexBucketCount": $TIMELINE_BUCKET_COUNT,
        "indexBuildMilliseconds": ${milliseconds(timelineBuildNanos)},
        "frameCount": $FRAME_COUNT,
        "frameP50Milliseconds": ${percentileMilliseconds(timelineFrames, 0.50)},
        "frameP95Milliseconds": ${percentileMilliseconds(timelineFrames, 0.95)},
        "frameMaxMilliseconds": ${milliseconds(timelineFrames.max())}
      },
      "flameGraph": {
        "nodeCount": $FLAME_NODE_COUNT,
        "frameCount": $FRAME_COUNT,
        "frameP50Milliseconds": ${percentileMilliseconds(flameFrames, 0.50)},
        "frameP95Milliseconds": ${percentileMilliseconds(flameFrames, 0.95)},
        "frameMaxMilliseconds": ${milliseconds(flameFrames.max())}
      }
    }
    """.trimIndent() + "\n"

private fun percentileMilliseconds(
    durations: List<Long>,
    percentile: Double,
): String {
    val sorted = durations.sorted()
    val index = (ceil(sorted.size * percentile).toInt() - 1).coerceIn(sorted.indices)
    return milliseconds(sorted[index])
}

private fun milliseconds(nanos: Long): String = String.format(Locale.US, "%.3f", nanos / 1_000_000.0)

private fun usedHeapBytes(): Long = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used

private fun forceGarbageCollection() {
    repeat(3) {
        System.gc()
        Thread.sleep(50)
    }
}

private fun deleteRecursively(directory: Path) {
    Files.walk(directory).use { paths ->
        paths
            .sorted(Comparator.reverseOrder())
            .forEach { path ->
                path.toFile().setWritable(true)
                path.deleteIfExists()
            }
    }
}

private fun platformId(): String {
    val os =
        when {
            System.getProperty("os.name").contains("mac", ignoreCase = true) -> "macos"
            System.getProperty("os.name").contains("win", ignoreCase = true) -> "windows"
            else -> "linux"
        }
    val arch =
        when (System.getProperty("os.arch").lowercase(Locale.US)) {
            "aarch64", "arm64" -> "arm64"
            else -> "x64"
        }
    return "$os-$arch"
}
