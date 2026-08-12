package com.androidperformancestudio.winscope

import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbBinaryResult
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.platform.adb.AdbTextResult
import com.androidperformancestudio.winscope.analysis.ReadOnlyTraceSql
import com.androidperformancestudio.winscope.analysis.WinscopeAnalyzer
import com.androidperformancestudio.winscope.capture.WinscopeCapabilityDetector
import com.androidperformancestudio.winscope.capture.WinscopeCaptureController
import com.androidperformancestudio.winscope.capture.WinscopeConfigBuilder
import com.androidperformancestudio.winscope.model.WinscopeCaptureConfig
import com.androidperformancestudio.winscope.model.WinscopeSession
import com.androidperformancestudio.winscope.model.WinscopeSource
import com.androidperformancestudio.winscope.storage.WinscopeSessionFiles
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

class WinscopeCoreTest {
    @Test
    fun `default and all capture configurations preserve privacy defaults`() {
        val defaults = WinscopeCaptureConfig()
        assertTrue(WinscopeSource.INPUT !in defaults.requestedSources)
        assertTrue(WinscopeSource.SCREEN_RECORDING !in defaults.requestedSources)
        assertTrue(defaults.requestedSources.containsAll(setOf(WinscopeSource.IME, WinscopeSource.VIEW_CAPTURE, WinscopeSource.PROTO_LOG)))
        assertContains(WinscopeConfigBuilder.build(defaults), "fill_policy: RING_BUFFER")
        assertContains(WinscopeConfigBuilder.build(defaults), "default_log_from_level: PROTOLOG_LEVEL_WARN")
        val all = defaults.copy(requestedSources = WinscopeCaptureConfig.ALL_SOURCES, protoLogEnableAll = true, protoLogStacktraces = true)
        assertTrue(all.containsSensitiveEvidence)
        assertContains(WinscopeConfigBuilder.build(all), "collect_stacktrace: true")
        assertContains(WinscopeConfigBuilder.build(all), "TRACE_MODE_TRACE_ALL")
    }

    @Test
    fun `read only sql rejects mutation and multiple statements`() {
        assertIs<StudioResult.Success<String>>(ReadOnlyTraceSql.validate("WITH x AS (SELECT 1) SELECT * FROM x"))
        assertIs<StudioResult.Failure>(ReadOnlyTraceSql.validate("DELETE FROM slice"))
        assertIs<StudioResult.Failure>(ReadOnlyTraceSql.validate("SELECT 1; SELECT 2"))
        assertIs<StudioResult.Failure>(ReadOnlyTraceSql.validate("WITH x AS (DELETE FROM slice RETURNING *) SELECT * FROM x"))
    }

    @Test
    fun `zip import rejects traversal and chooses largest trace with mp4 precedence`() {
        val root = Files.createTempDirectory("winscope-storage-test")
        val service = WinscopeSessionFiles(root)
        val zip = root.resolve("fixture-without-extension")
        ZipOutputStream(Files.newOutputStream(zip)).use { output ->
            fun entry(
                name: String,
                bytes: Int,
            ) {
                output.putNextEntry(ZipEntry(name))
                output.write(ByteArray(bytes) { if (it == 0) 10 else 1 })
                output.closeEntry()
            }
            entry("small.pftrace", 10)
            entry("nested/large.perfetto-trace", 20)
            entry("screen.png", 10)
            entry("screen.mp4", 10)
        }
        val session = assertIs<StudioResult.Success<WinscopeSession>>(service.import(zip)).value
        assertEquals("large.perfetto-trace", session.traceFile.fileName.toString())
        assertEquals("screen.mp4", session.recordingFile?.fileName?.toString())
        assertEquals(null, session.screenshotFile)
        assertTrue(session.managedFiles)

        val bad = root.resolve("bad.zip")
        ZipOutputStream(Files.newOutputStream(bad)).use { output ->
            output.putNextEntry(ZipEntry("../escape.pftrace"))
            output.write(byteArrayOf(1))
            output.closeEntry()
        }
        assertIs<StudioResult.Failure>(service.import(bad))
    }

    @Test
    fun `raw command export ignores extension and managed deletion preserves other sessions`() {
        val root = Files.createTempDirectory("winscope-storage-delete-test")
        val service = WinscopeSessionFiles(root)
        val commandExport = root.resolve("command-export")
        Files.write(commandExport, byteArrayOf(10, 1, 0))
        assertIs<StudioResult.Success<WinscopeSession>>(service.import(commandExport))

        val trace = root.resolve("managed.perfetto-trace")
        val recording = root.resolve("managed.mp4")
        val unrelated = root.resolve("unrelated.perfetto-trace")
        listOf(trace, recording, unrelated).forEach { Files.write(it, byteArrayOf(1)) }
        service.delete(
            WinscopeSession(
                id = "managed",
                traceFile = trace,
                recordingFile = recording,
                capturedAt = Instant.EPOCH,
                managedFiles = true,
            ),
        )
        assertTrue(Files.notExists(trace))
        assertTrue(Files.notExists(recording))
        assertTrue(Files.isRegularFile(unrelated))
    }

    @Test
    fun `parses perfetto query data source list and pids`() {
        val query =
            """
            DATA SOURCES REGISTERED:
            NAME
            ===
            android.windowmanager 0
            android.protolog 0
            TRACING SESSIONS:
            """.trimIndent()
        assertEquals(setOf("android.windowmanager", "android.protolog"), WinscopeCapabilityDetector.parseDataSources(query))
        assertEquals(42, WinscopeCaptureController.parsePid("Tracing started\n42\n"))
    }

    @Test
    fun `fake adb supports repeated live capture snapshot and recovery`() =
        runBlocking {
            val adb = FakeAdb()
            val storage = Files.createTempDirectory("winscope-fake-adb")
            val detector = WinscopeCapabilityDetector(adbFactory = { adb })
            val capabilities =
                assertIs<StudioResult.Success<com.androidperformancestudio.winscope.model.WinscopeCapabilities>>(
                    detector.detect(Path.of("adb"), "device-15"),
                ).value
            assertTrue(capabilities.liveCaptureSupported, capabilities.toString())
            assertEquals(2, capabilities.displays.size)
            val controller = WinscopeCaptureController({ adb }, storage)
            repeat(2) {
                assertIs<StudioResult.Success<Unit>>(
                    controller.start(Path.of("adb"), capabilities, WinscopeCaptureConfig(durationSeconds = 1)),
                )
                val session = assertIs<StudioResult.Success<WinscopeSession>>(controller.stop()).value
                assertTrue(Files.isRegularFile(session.traceFile))
            }
            val snapshot = assertIs<StudioResult.Success<WinscopeSession>>(controller.snapshot(Path.of("adb"), capabilities)).value
            assertTrue(Files.isRegularFile(requireNotNull(snapshot.screenshotFile)))
            val recoverable = assertIs<StudioResult.Success<List<String>>>(controller.recover(Path.of("adb"), capabilities)).value
            assertEquals(listOf("/data/misc/perfetto-traces/aps-winscope-old.perfetto-trace"), recoverable)
        }

    @Test
    fun `sanitized fixture opens through pinned v57 2 and exposes winscope sources`() =
        runBlocking {
            val fixture = Files.createTempFile("android15-sanitized", ".perfetto-trace")
            checkNotNull(javaClass.getResourceAsStream("/winscope/android15-sanitized.perfetto-trace")).use {
                Files.copy(it, fixture, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            val binary = findTraceProcessor() ?: return@runBlocking
            System.setProperty("androidperformancestudio.traceProcessorPath", binary.toString())
            val session = WinscopeSession("fixture", fixture, capturedAt = Instant.EPOCH)
            val analyzer = assertIs<StudioResult.Success<WinscopeAnalyzer>>(WinscopeAnalyzer.open(session)).value
            try {
                val probed = analyzer.probeSources()
                if (probed is StudioResult.Failure) error("${probed.error.code}: ${probed.error.message}")
                val sources = assertIs<StudioResult.Success<Set<WinscopeSource>>>(probed).value
                assertTrue(
                    sources.containsAll(
                        setOf(
                            WinscopeSource.WINDOW_MANAGER,
                            WinscopeSource.SURFACE_FLINGER,
                            WinscopeSource.TRANSACTIONS,
                            WinscopeSource.TRANSITIONS,
                            WinscopeSource.IME,
                            WinscopeSource.VIEW_CAPTURE,
                            WinscopeSource.PROTO_LOG,
                        ),
                    ),
                )
                val timeline =
                    assertIs<StudioResult.Success<com.androidperformancestudio.winscope.model.WinscopeTimeline>>(
                        analyzer.timeline(),
                    ).value
                assertTrue(timeline.bounds.endNanos >= timeline.bounds.startNanos)
                val wmTimestamp =
                    timeline.entries
                        .getValue(WinscopeSource.WINDOW_MANAGER)
                        .last()
                        .timestampNanos
                val state =
                    assertIs<StudioResult.Success<com.androidperformancestudio.winscope.model.WinscopeState>>(
                        analyzer.state(WinscopeSource.WINDOW_MANAGER, wmTimestamp),
                    ).value
                assertTrue(state.nodes.isNotEmpty())
            } finally {
                analyzer.close()
                System.clearProperty("androidperformancestudio.traceProcessorPath")
            }
        }

    private fun findTraceProcessor(): Path? {
        val name = if (System.getProperty("os.name").contains("Windows", true)) "trace_processor_shell.exe" else "trace_processor_shell"
        var root: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(6) {
            root?.resolve("build/perfetto-tools/$name")?.takeIf(Files::isExecutable)?.let { return it }
            root = root?.parent
        }
        return null
    }

    private class FakeAdb : AdbClient {
        override suspend fun listDevices(): List<AdbDevice> = emptyList()

        override suspend fun shell(
            serial: String,
            arguments: List<String>,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult {
            val command = arguments.joinToString(" ")
            val output =
                when {
                    command.contains("getprop ro.build.version.sdk") -> "35\nuserdebug\nPixel Fixture\n2000\n"
                    command == "perfetto --query" ->
                        "DATA SOURCES REGISTERED:\nNAME\n===\n" +
                            WinscopeSource.entries.mapNotNull(WinscopeSource::perfettoName).joinToString("\n") { "$it 0" } +
                            "\nTRACING SESSIONS:\n"
                    command == "dumpsys SurfaceFlinger --display-id" ->
                        "Display 1 (HWC display 0): displayName=\"Internal\"\n" +
                            "Display 2 (HWC display 1): displayName=\"External\"\n"
                    command.contains("--background-wait") -> "123\n"
                    command.contains("kill -0") -> "1\n"
                    command.contains("stat -c") -> "4\n"
                    command.contains("ls -1t") -> "/data/misc/perfetto-traces/aps-winscope-old.perfetto-trace\n"
                    command.startsWith("screenrecord") -> "124\n"
                    else -> ""
                }
            return AdbTextResult(0, output, "", Duration.ZERO)
        }

        override suspend fun execOut(
            serial: String,
            arguments: List<String>,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbBinaryResult = AdbBinaryResult(0, byteArrayOf(1, 2, 3), byteArrayOf(), Duration.ZERO)

        override suspend fun push(
            serial: String,
            localPath: Path,
            remotePath: String,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult = AdbTextResult(0, "", "", Duration.ZERO)

        override suspend fun pull(
            serial: String,
            remotePath: String,
            localPath: Path,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult {
            Files.write(localPath, byteArrayOf(1, 2, 3, 4))
            return AdbTextResult(0, "", "", Duration.ZERO)
        }

        override suspend fun forward(
            serial: String,
            local: String,
            remote: String,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult = AdbTextResult(0, "", "", Duration.ZERO)

        override suspend fun removeForward(
            serial: String,
            local: String,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult = AdbTextResult(0, "", "", Duration.ZERO)

        override suspend fun bugreport(
            serial: String,
            outputPath: Path,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult = AdbTextResult(0, "", "", Duration.ZERO)
    }
}
