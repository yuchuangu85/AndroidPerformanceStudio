package com.androidperformancestudio.desktop

import com.androidperformancestudio.application.OfflineImportRequest
import com.androidperformancestudio.application.OfflineImportResult
import com.androidperformancestudio.application.OfflineProfileFormat
import com.androidperformancestudio.application.OfflineProfileImporter
import com.androidperformancestudio.export.GeckoProfileExportService
import com.androidperformancestudio.export.ReportExportService
import com.androidperformancestudio.export.SessionPackageService
import com.androidperformancestudio.model.NormalizedProfileRecord
import com.androidperformancestudio.model.NormalizedSample
import com.androidperformancestudio.model.ProfileExecutionType
import com.androidperformancestudio.model.ProfileFile
import com.androidperformancestudio.model.ProfileFrame
import com.androidperformancestudio.model.ProfileMetadata
import com.androidperformancestudio.model.ProfileThread
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.storage.SQLiteSampleStore
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CrossPlatformGoldenE2eTest {
    @Test
    fun `imports analyzes exports and reopens one golden session in paths with spaces and unicode`() {
        val root = Files.createTempDirectory("aps-cross-platform-")
        val session = root.resolve("含 空格").resolve("golden session").createDirectories()
        val database = session.resolve("profile.sqlite")

        SQLiteSampleStore.open(database).use { store -> store.importRecords(goldenRecords()) }
        session.resolve("simpleperf.protobuf").writeText("SIMPLEPERF-golden")
        session.resolve("session.properties").writeText("status=COMPLETED\n")

        val topFunctions = SQLiteSampleStore.open(database).use { it.topFunctions(limit = 10) }
        val callTree =
            SQLiteSampleStore.open(database).use {
                it.callTree(direction = CallStackDirection.FORWARD)
            }
        val exports = root.resolve("report exports").createDirectories()
        ReportExportService().run {
            exportJson(topFunctions, callTree, exports.resolve("report.json"))
            exportTopFunctionsCsv(topFunctions, exports.resolve("top functions.csv"))
            exportCallTreeCsv(callTree, exports.resolve("call tree.csv"))
        }

        val archive = root.resolve("golden.apsession.zip")
        SessionPackageService().export(session, archive)
        val imported = SessionPackageService().import(archive, root.resolve("reopened sessions"))

        SQLiteSampleStore.open(imported.sessionDirectory.resolve("profile.sqlite")).use { reopened ->
            assertEquals(2L, reopened.sampleCount())
            assertEquals(
                listOf("renderFrame", "runLoop"),
                reopened.topFunctions(limit = 10).map { it.symbolName },
            )
        }
        assertTrue(exports.resolve("report.json").exists())
        assertTrue(exports.resolve("top functions.csv").exists())
        assertTrue(exports.resolve("call tree.csv").exists())
    }

    @Test
    fun `exports Gecko json gzip that can be imported with equivalent samples and stacks`() =
        runTest {
            val root = Files.createTempDirectory("aps-gecko-roundtrip-")
            val sourceSession = root.resolve("source").createDirectories()
            SQLiteSampleStore.open(sourceSession.resolve("profile.sqlite")).use { store ->
                store.importRecords(goldenRecords())
            }
            val exported = root.resolve("perf_data.json.gz")
            GeckoProfileExportService().export(sourceSession, exported)
            val importer =
                OfflineProfileImporter(
                    hostSimpleperfResolver = { error("Gecko import must not locate simpleperf") },
                    perfDataConverter = { _, _, _ -> error("Gecko import must not run a converter") },
                )

            val imported =
                importer.import(
                    OfflineImportRequest(
                        sessionId = "reimported",
                        sessionRoot = root.resolve("imports"),
                        input = exported,
                        format = OfflineProfileFormat.GECKO_PROFILE_JSON_GZIP,
                    ),
                )

            val result = assertIs<StudioResult.Success<OfflineImportResult>>(imported)
            val importedSession = result.value.sessionDirectory
            SQLiteSampleStore.openReadOnly(importedSession.resolve("profile.sqlite")).use { store ->
                assertEquals(2L, store.sampleCount())
                assertEquals(listOf("RenderThread"), store.threads().map { it.name })
                assertEquals(
                    listOf("renderFrame", "runLoop"),
                    store.topFunctions(limit = 10).map { it.symbolName },
                )
                assertEquals(2, store.callTree(direction = CallStackDirection.FORWARD).size)
            }
        }
}

private fun goldenRecords(): Sequence<NormalizedProfileRecord> =
    sequenceOf(
        NormalizedProfileRecord.Metadata(
            ProfileMetadata(
                eventTypes = listOf("cpu-cycles"),
                appPackageName = "com.example.golden",
                appType = "profileable",
                androidSdkVersion = "34",
                androidBuildType = "user",
                traceOffCpu = false,
            ),
        ),
        NormalizedProfileRecord.File(
            ProfileFile(
                id = 1,
                path = "/data/app/lib/arm64/libapp.so",
                symbols = listOf("runLoop", "renderFrame"),
                mangledSymbols = emptyList(),
            ),
        ),
        NormalizedProfileRecord.Thread(ProfileThread(100, 101, "RenderThread")),
        goldenSample(timestampNanos = 1_000, eventCount = 6),
        goldenSample(timestampNanos = 2_000, eventCount = 4),
    )

private fun goldenSample(
    timestampNanos: Long,
    eventCount: Long,
): NormalizedProfileRecord.Sample =
    NormalizedProfileRecord.Sample(
        NormalizedSample(
            timestampNanos = timestampNanos,
            processId = 100,
            threadId = 101,
            threadName = "RenderThread",
            eventType = "cpu-cycles",
            eventCount = eventCount,
            frames =
                listOf(
                    goldenFrame(symbolId = 1, symbolName = "renderFrame", address = 0x20),
                    goldenFrame(symbolId = 0, symbolName = "runLoop", address = 0x10),
                ),
            unwindError = null,
        ),
    )

private fun goldenFrame(
    symbolId: Int,
    symbolName: String,
    address: Long,
): ProfileFrame =
    ProfileFrame(
        virtualAddress = address,
        fileId = 1,
        symbolId = symbolId,
        filePath = "/data/app/lib/arm64/libapp.so",
        symbolName = symbolName,
        executionType = ProfileExecutionType.NATIVE,
    )
