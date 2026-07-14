package com.androidperformancestudio.application

import com.android.tools.profiler.proto.SimpleperfReport
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.parser.HostSimpleperf
import com.androidperformancestudio.parser.HostSimpleperfSource
import com.androidperformancestudio.parser.SimpleperfConversionResult
import com.androidperformancestudio.toolchain.ProcessCancellationSignal
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OfflineProfileImporterTest {
    @Test
    fun `imports existing protobuf without locating host simpleperf and retains raw trace`() =
        runTest {
            val root = Files.createTempDirectory("aps-offline-proto-")
            val input = root.resolve("input.perf.trace").apply { writeBytes(profileTrace()) }
            var locatorCalled = false
            val importer =
                OfflineProfileImporter(
                    hostSimpleperfResolver = {
                        locatorCalled = true
                        error("host simpleperf must not be used for protobuf imports")
                    },
                    perfDataConverter = { _, _, _ -> error("converter must not be used for protobuf imports") },
                )

            val result =
                importer.import(
                    OfflineImportRequest(
                        sessionId = "protobuf-session",
                        sessionRoot = root.resolve("sessions"),
                        input = input,
                        format = OfflineProfileFormat.SIMPLEPERF_PROTOBUF,
                    ),
                )

            val imported = assertIs<StudioResult.Success<OfflineImportResult>>(result).value
            assertFalse(locatorCalled)
            assertEquals(1L, imported.profileImport.importedSamples)
            assertEquals(1L, imported.quality.sampleCount)
            assertEquals(4L, imported.readSummary.recordCount)
            assertTrue(imported.database.exists())
            assertContentEquals(input.readBytes(), imported.protobufTrace.readBytes())
            assertTrue(imported.sessionDirectory.resolve("import.properties").exists())
        }

    @Test
    fun `copies perf data symbols and mapping before converting and importing`() =
        runTest {
            val root = Files.createTempDirectory("aps-offline-perf-")
            val input = root.resolve("source.perf.data").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val mapping = root.resolve("mapping-source.txt").apply { writeText("mapping") }
            val symbols = root.resolve("symbols-source").createDirectories()
            symbols
                .resolve("arm64-v8a")
                .createDirectories()
                .resolve("libdemo.so")
                .writeBytes(byteArrayOf(9, 8, 7))
            val executable = root.resolve("simpleperf").apply { writeText("tool") }
            val host = HostSimpleperf(executable, "simpleperf 1.0", "a".repeat(64), HostSimpleperfSource.CONFIGURED)
            var convertedPerfData: Path? = null
            var convertedSymbols: Path? = null
            var convertedMapping: Path? = null
            val importer =
                OfflineProfileImporter(
                    hostSimpleperfResolver = { StudioResult.Success(host) },
                    perfDataConverter = { actualHost, request, _ ->
                        assertEquals(host, actualHost)
                        convertedPerfData = request.perfData
                        convertedSymbols = request.symbolDirectory
                        convertedMapping = request.proguardMapping
                        request.protobufTrace.writeBytes(profileTrace())
                        StudioResult.Success(SimpleperfConversionResult(request.protobufTrace, "converted", ""))
                    },
                )

            val result =
                importer.import(
                    OfflineImportRequest(
                        sessionId = "perf-session",
                        sessionRoot = root.resolve("sessions"),
                        input = input,
                        format = OfflineProfileFormat.PERF_DATA,
                        symbolDirectory = symbols,
                        proguardMapping = mapping,
                    ),
                    ProcessCancellationSignal(),
                )

            val imported = assertIs<StudioResult.Success<OfflineImportResult>>(result).value
            assertEquals(imported.perfData, convertedPerfData)
            assertEquals(imported.sessionDirectory.resolve("symbols"), convertedSymbols)
            assertEquals(imported.sessionDirectory.resolve("mapping.txt"), convertedMapping)
            assertContentEquals(input.readBytes(), imported.perfData?.readBytes())
            assertContentEquals(mapping.readBytes(), convertedMapping?.readBytes())
            assertTrue(convertedSymbols?.resolve("arm64-v8a/libdemo.so")?.exists() == true)
            assertEquals("simpleperf 1.0", imported.hostSimpleperf?.version)
            assertEquals(1L, imported.profileImport.importedSamples)
        }

    @Test
    fun `converts and indexes a completed capture inside its existing evidence directory`() =
        runTest {
            val session = Files.createTempDirectory("aps-captured-session-")
            val perfData = session.resolve("perf.data").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            session.resolve("record.stderr.log").writeText("retained warning")
            val host =
                HostSimpleperf(
                    session.resolve("simpleperf").apply { writeText("tool") },
                    "simpleperf 1.0",
                    "a".repeat(64),
                    HostSimpleperfSource.CONFIGURED,
                )
            val importer =
                OfflineProfileImporter(
                    hostSimpleperfResolver = { StudioResult.Success(host) },
                    perfDataConverter = { _, request, _ ->
                        assertEquals(perfData, request.perfData)
                        request.protobufTrace.writeBytes(profileTrace())
                        StudioResult.Success(SimpleperfConversionResult(request.protobufTrace, "converted", ""))
                    },
                )

            val result = importer.importCapturedSession(session)

            val imported = assertIs<StudioResult.Success<OfflineImportResult>>(result).value
            assertEquals(session, imported.sessionDirectory)
            assertEquals(perfData, imported.perfData)
            assertEquals(1L, imported.profileImport.importedSamples)
            assertEquals("retained warning", session.resolve("record.stderr.log").toFile().readText())
            assertTrue(session.resolve("profile.sqlite").exists())
            assertTrue(session.resolve("simpleperf.protobuf").exists())
        }

    private fun profileTrace(): ByteArray {
        val records =
            listOf(
                SimpleperfReport.Record
                    .newBuilder()
                    .setMetaInfo(SimpleperfReport.MetaInfo.newBuilder().addEventType("cpu-cycles"))
                    .build(),
                SimpleperfReport.Record
                    .newBuilder()
                    .setFile(
                        SimpleperfReport.File
                            .newBuilder()
                            .setId(7)
                            .setPath("/system/lib64/libui.so")
                            .addSymbol("renderFrame"),
                    ).build(),
                SimpleperfReport.Record
                    .newBuilder()
                    .setThread(
                        SimpleperfReport.Thread
                            .newBuilder()
                            .setProcessId(100)
                            .setThreadId(101)
                            .setThreadName("RenderThread"),
                    ).build(),
                SimpleperfReport.Record
                    .newBuilder()
                    .setSample(
                        SimpleperfReport.Sample
                            .newBuilder()
                            .setTime(42)
                            .setThreadId(101)
                            .setEventTypeId(0)
                            .setEventCount(5)
                            .addCallchain(
                                SimpleperfReport.Sample.CallChainEntry
                                    .newBuilder()
                                    .setFileId(7)
                                    .setSymbolId(0)
                                    .setVaddrInFile(0x20),
                            ),
                    ).build(),
            )
        return ByteArrayOutputStream()
            .apply {
                write("SIMPLEPERF".encodeToByteArray())
                writeLittleEndian16(1)
                records.forEach { record ->
                    val bytes = record.toByteArray()
                    writeLittleEndian32(bytes.size)
                    write(bytes)
                }
                writeLittleEndian32(0)
            }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLittleEndian16(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
    }

    private fun ByteArrayOutputStream.writeLittleEndian32(value: Int) {
        repeat(Int.SIZE_BYTES) { shift -> write(value ushr (shift * Byte.SIZE_BITS) and 0xff) }
    }
}
