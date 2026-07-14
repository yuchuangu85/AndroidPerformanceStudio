package com.androidperformancestudio.fixtures

import com.androidperformancestudio.model.ProfileSample
import com.androidperformancestudio.storage.SQLiteSampleStore
import com.androidperformancestudio.visualization.FlameGraphNode
import com.androidperformancestudio.visualization.FlameGraphProjector
import com.androidperformancestudio.visualization.TimeViewport
import com.androidperformancestudio.visualization.TimelineDensityIndex
import com.androidperformancestudio.visualization.WeightViewport
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
private const val FRAME_COUNT = 240

fun main(args: Array<String>) {
    require(args.size == 1) { "Expected the report directory as the only argument" }
    val reportDirectory = Path.of(args.single()).createDirectories()
    val platform = platformId()
    val database = Files.createTempFile("aps-million-record-", ".sqlite")
    val heapSampler = PeakHeapSampler()
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

    val flameNodes = syntheticFlameNodes(FLAME_NODE_COUNT)
    val flameFrames =
        frameDurations {
            val iteration = it
            val duration = 100_000L + iteration % 10 * 50_000L
            val start = (iteration * 7_919L) % (RECORD_COUNT - duration)
            FlameGraphProjector.project(
                nodes = flameNodes,
                viewport = WeightViewport(start, start + duration),
                widthPixels = 1_200,
                rowHeightPixels = 18f,
                minimumNodeWidthPixels = 1f,
            )
        }

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
    database.deleteIfExists()
    database.resolveSibling(database.fileName.toString() + "-shm").deleteIfExists()
    database.resolveSibling(database.fileName.toString() + "-wal").deleteIfExists()
    println("P0 performance report: $reportFile")
    println(report)
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

private fun syntheticFlameNodes(count: Int): List<FlameGraphNode> =
    List(count) { index ->
        val depth = index % 64
        val start = (index * 9_973L) % (RECORD_COUNT - 2_000L)
        val width = 10L + (index * 37L) % 2_000L
        FlameGraphNode(
            label = "node_$index",
            depth = depth,
            startWeight = start,
            endWeight = start + width,
        )
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
        samplerThread?.join()
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
