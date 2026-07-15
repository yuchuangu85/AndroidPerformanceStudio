package com.androidperformancestudio.fixtures

import com.androidperformancestudio.export.ReportExportService
import com.androidperformancestudio.export.SessionPackageService
import com.androidperformancestudio.model.NormalizedProfileRecord
import com.androidperformancestudio.model.NormalizedSample
import com.androidperformancestudio.model.ProfileExecutionType
import com.androidperformancestudio.model.ProfileFile
import com.androidperformancestudio.model.ProfileFrame
import com.androidperformancestudio.model.ProfileMetadata
import com.androidperformancestudio.model.ProfileThread
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.storage.SQLiteSampleStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

fun main(args: Array<String>) {
    require(args.size == 1) { "Expected output .apsession.zip path" }
    val destination = Path.of(args.single()).toAbsolutePath().normalize()
    destination.parent.createDirectories()
    val staging = destination.parent.resolve(".golden-session-staging")
    staging.deleteRecursively()
    staging.createDirectories()
    try {
        generateDatabase(staging.resolve("profile.sqlite"))
        staging.resolve("session.properties").writeText(
            "status=COMPLETED\nsource=generated-golden\nandroid.sdk=34\nabi=arm64-v8a\n",
        )
        staging.resolve("README.txt").writeText(
            "Synthetic V0.1 demonstration profile. Regenerate with :test-fixtures:generateSampleSession.\n",
        )
        exportReports(staging)
        destination.deleteIfExists()
        SessionPackageService().export(staging, destination)
    } finally {
        staging.deleteRecursively()
    }
}

private fun generateDatabase(database: Path) {
    SQLiteSampleStore.open(database).use { store -> store.importRecords(sampleRecords()) }
}

private fun exportReports(session: Path) {
    SQLiteSampleStore.open(session.resolve("profile.sqlite")).use { store ->
        val top = store.topFunctions(limit = 100)
        val tree = store.callTree(direction = CallStackDirection.FORWARD)
        ReportExportService().run {
            exportJson(top, tree, session.resolve("report.json"))
            exportTopFunctionsCsv(top, session.resolve("top-functions.csv"))
            exportCallTreeCsv(tree, session.resolve("call-tree.csv"))
        }
    }
}

private fun sampleRecords(): Sequence<NormalizedProfileRecord> =
    sequenceOf(
        NormalizedProfileRecord.Metadata(
            ProfileMetadata(
                eventTypes = listOf("cpu-cycles"),
                appPackageName = "com.example.performance",
                appType = "profileable",
                androidSdkVersion = "34",
                androidBuildType = "user",
                traceOffCpu = false,
            ),
        ),
        NormalizedProfileRecord.File(
            ProfileFile(
                id = 1,
                path = "/data/app/lib/arm64/libperformance.so",
                symbols = listOf("mainLoop", "renderFrame", "decodeImage"),
                mangledSymbols = emptyList(),
            ),
        ),
        NormalizedProfileRecord.Thread(ProfileThread(1_000, 1_001, "main")),
        NormalizedProfileRecord.Thread(ProfileThread(1_000, 1_002, "image-worker")),
        sample(1_000_000, 1_001, "main", 50, listOf(frame(1, "renderFrame"), frame(0, "mainLoop"))),
        sample(2_000_000, 1_001, "main", 30, listOf(frame(1, "renderFrame"), frame(0, "mainLoop"))),
        sample(3_000_000, 1_002, "image-worker", 20, listOf(frame(2, "decodeImage"), frame(0, "mainLoop"))),
    )

private fun sample(
    timestampNanos: Long,
    threadId: Int,
    threadName: String,
    eventCount: Long,
    frames: List<ProfileFrame>,
): NormalizedProfileRecord.Sample =
    NormalizedProfileRecord.Sample(
        NormalizedSample(
            timestampNanos = timestampNanos,
            processId = 1_000,
            threadId = threadId,
            threadName = threadName,
            eventType = "cpu-cycles",
            eventCount = eventCount,
            frames = frames,
            unwindError = null,
        ),
    )

private fun frame(
    symbolId: Int,
    symbolName: String,
): ProfileFrame =
    ProfileFrame(
        virtualAddress = 0x100L + symbolId,
        fileId = 1,
        symbolId = symbolId,
        filePath = "/data/app/lib/arm64/libperformance.so",
        symbolName = symbolName,
        executionType = ProfileExecutionType.NATIVE,
    )

private fun Path.deleteRecursively() {
    if (!Files.exists(this)) return
    Files.walk(this).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
}
