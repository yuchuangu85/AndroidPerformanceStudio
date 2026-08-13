package com.androidperformancestudio.winscope

import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbBinaryResult
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.platform.adb.AdbTextResult
import com.androidperformancestudio.platform.toolchain.HostProcessBinaryResult
import com.androidperformancestudio.platform.toolchain.HostProcessLaunchRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRunner
import com.androidperformancestudio.platform.toolchain.HostProcessTextResult
import com.androidperformancestudio.platform.toolchain.RunningHostProcess
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
import kotlin.test.assertNull
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
    fun `adb root waits for the device before capabilities are queried again`() =
        runBlocking {
            val runner = RecordingHostProcessRunner()
            val detector = WinscopeCapabilityDetector(processRunner = runner)

            assertIs<StudioResult.Success<Unit>>(detector.restartAsRoot(Path.of("adb"), "device-15"))

            assertEquals(
                listOf(
                    listOf("-s", "device-15", "root"),
                    listOf("-s", "device-15", "wait-for-device"),
                ),
                runner.requests.map(HostProcessRequest::arguments),
            )
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
            val controller =
                WinscopeCaptureController({ adb }, storage) {
                    StudioResult.Success(capabilities.availableSources - WinscopeSource.SCREEN_RECORDING)
                }
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
    fun `empty screen recording is excluded from the captured session`() =
        runBlocking {
            val adb = FakeAdb(emptyRecording = true)
            val storage = Files.createTempDirectory("winscope-empty-recording")
            val capabilities =
                assertIs<StudioResult.Success<com.androidperformancestudio.winscope.model.WinscopeCapabilities>>(
                    WinscopeCapabilityDetector(adbFactory = { adb }).detect(Path.of("adb"), "device-15"),
                ).value
            val controller =
                WinscopeCaptureController({ adb }, storage) {
                    StudioResult.Success(capabilities.availableSources - WinscopeSource.SCREEN_RECORDING)
                }
            val config =
                WinscopeCaptureConfig(
                    durationSeconds = 1,
                    requestedSources = WinscopeCaptureConfig().requestedSources + WinscopeSource.SCREEN_RECORDING,
                )

            assertIs<StudioResult.Success<Unit>>(controller.start(Path.of("adb"), capabilities, config))
            val session = assertIs<StudioResult.Success<WinscopeSession>>(controller.stop()).value

            assertNull(session.recordingFile)
            assertTrue(!session.sensitive)
        }

    @Test
    fun `capture rejects video only results instead of claiming Winscope evidence`() =
        runBlocking {
            val adb = FakeAdb()
            val storage = Files.createTempDirectory("winscope-video-only")
            val capabilities =
                assertIs<StudioResult.Success<com.androidperformancestudio.winscope.model.WinscopeCapabilities>>(
                    WinscopeCapabilityDetector(adbFactory = { adb }).detect(Path.of("adb"), "device-15"),
                ).value
            val controller = WinscopeCaptureController({ adb }, storage) { StudioResult.Success(emptySet()) }
            val config =
                WinscopeCaptureConfig(
                    durationSeconds = 1,
                    requestedSources = WinscopeCaptureConfig().requestedSources + WinscopeSource.SCREEN_RECORDING,
                )

            assertIs<StudioResult.Success<Unit>>(controller.start(Path.of("adb"), capabilities, config))
            val failure = assertIs<StudioResult.Failure>(controller.stop())

            assertEquals("WINSCOPE_CORE_EVIDENCE_MISSING", failure.error.code)
            assertContains(failure.error.message, "only the screen recording contains usable evidence")
            assertContains(failure.error.message, "raw Perfetto trace")
            assertTrue(
                storage
                    .toFile()
                    .listFiles()
                    .orEmpty()
                    .any { it.extension == "mp4" && it.length() > 0 },
            )
        }

    @Test
    fun `legacy WindowManager capture fills devices without the Perfetto source`() =
        runBlocking {
            val adb = FakeAdb(windowManagerPerfettoAvailable = false, rootActive = true)
            val storage = Files.createTempDirectory("winscope-legacy-window-manager")
            val capabilities =
                assertIs<StudioResult.Success<com.androidperformancestudio.winscope.model.WinscopeCapabilities>>(
                    WinscopeCapabilityDetector(adbFactory = { adb }).detect(Path.of("adb"), "device-15"),
                ).value
            val controller = WinscopeCaptureController({ adb }, storage) { StudioResult.Success(setOf(WinscopeSource.WINDOW_MANAGER)) }

            assertIs<StudioResult.Success<Unit>>(
                controller.start(Path.of("adb"), capabilities, WinscopeCaptureConfig(durationSeconds = 1)),
            )
            val session = assertIs<StudioResult.Success<WinscopeSession>>(controller.stop()).value

            assertContains(session.availableSources, WinscopeSource.WINDOW_MANAGER)
            assertTrue(session.limitations.none { it.message == "WindowManager was requested but unavailable" })
            assertTrue(
                adb.shellCommands.any {
                    it ==
                        listOf(
                            "rm",
                            "-f",
                            "/data/misc/wmtrace/wm_trace.winscope",
                            "/data/misc/wmtrace/wm_trace.pb",
                        )
                },
            )
            findTraceProcessor()?.let { binary ->
                System.setProperty("androidperformancestudio.traceProcessorPath", binary.toString())
                val analyzer = assertIs<StudioResult.Success<WinscopeAnalyzer>>(WinscopeAnalyzer.open(session)).value
                try {
                    assertContains(
                        assertIs<StudioResult.Success<Set<WinscopeSource>>>(analyzer.probeSources()).value,
                        WinscopeSource.WINDOW_MANAGER,
                    )
                } finally {
                    analyzer.close()
                    System.clearProperty("androidperformancestudio.traceProcessorPath")
                }
            }
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

    @Test
    fun `empty trace does not report Winscope sources`() =
        runBlocking {
            val binary = checkNotNull(findTraceProcessor())
            val trace = Files.createTempFile("empty-winscope", ".perfetto-trace")
            Files.write(trace, byteArrayOf(0x0a, 0x00))
            System.setProperty("androidperformancestudio.traceProcessorPath", binary.toString())
            val analyzer =
                assertIs<StudioResult.Success<WinscopeAnalyzer>>(
                    WinscopeAnalyzer.open(WinscopeSession("empty", trace, capturedAt = Instant.EPOCH)),
                ).value
            try {
                assertEquals(emptySet(), assertIs<StudioResult.Success<Set<WinscopeSource>>>(analyzer.probeSources()).value)
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

    private class FakeAdb(
        private val emptyRecording: Boolean = false,
        private val windowManagerPerfettoAvailable: Boolean = true,
        private val rootActive: Boolean = false,
    ) : AdbClient {
        val shellCommands = mutableListOf<List<String>>()

        override suspend fun listDevices(): List<AdbDevice> = emptyList()

        override suspend fun shell(
            serial: String,
            arguments: List<String>,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult {
            shellCommands += arguments
            val command = arguments.joinToString(" ")
            val output =
                when {
                    command.contains("getprop ro.build.version.sdk") -> "35\nuserdebug\nPixel Fixture\n${if (rootActive) 0 else 2000}\n"
                    command == "perfetto --query" ->
                        "DATA SOURCES REGISTERED:\nNAME\n===\n" +
                            WinscopeSource.entries
                                .filter { windowManagerPerfettoAvailable || it != WinscopeSource.WINDOW_MANAGER }
                                .mapNotNull(WinscopeSource::perfettoName)
                                .joinToString("\n") { "$it 0" } +
                            "\nTRACING SESSIONS:\n"
                    command == "dumpsys SurfaceFlinger --display-id" ->
                        "Display 1 (HWC display 0): displayName=\"Internal\"\n" +
                            "Display 2 (HWC display 1): displayName=\"External\"\n"
                    command.contains("--background-wait") -> "123\n"
                    command.contains("kill -0") -> "1\n"
                    command.contains("stat -c") -> "4\n"
                    command.contains("ls -1t /data/misc/wmtrace") -> "/data/misc/wmtrace/wm_trace.winscope\n"
                    command.contains("ls -1t") -> "/data/misc/perfetto-traces/aps-winscope-old.perfetto-trace\n"
                    command.contains("screenrecord") -> "124\n"
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
            Files.write(
                localPath,
                when {
                    emptyRecording && remotePath.endsWith(".mp4") -> byteArrayOf()
                    remotePath.endsWith(".winscope") -> LEGACY_WINDOW_MANAGER_FIXTURE
                    remotePath.endsWith(".perfetto-trace") -> byteArrayOf(0x0a, 0x00)
                    else -> byteArrayOf(1, 2, 3, 4)
                },
            )
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

    private class RecordingHostProcessRunner : HostProcessRunner {
        val requests = mutableListOf<HostProcessRequest>()

        override suspend fun executeText(request: HostProcessRequest): HostProcessTextResult {
            requests += request
            return HostProcessTextResult(-1, 0, "", "", Duration.ZERO, false, false)
        }

        override suspend fun executeBinary(request: HostProcessRequest): HostProcessBinaryResult = error("not used")

        override fun launch(request: HostProcessLaunchRequest): RunningHostProcess = error("not used")
    }

    companion object {
        private val LEGACY_WINDOW_MANAGER_FIXTURE =
            byteArrayOf(
                0x09,
                0x57,
                0x49,
                0x4e,
                0x54,
                0x52,
                0x41,
                0x43,
                0x45,
                0x12,
                0x09,
                0x09,
                0x7b,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
            )
    }
}
