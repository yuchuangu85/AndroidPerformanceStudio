package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.capture.AndroidSdkHprofConvLocator
import com.androidperformancestudio.memory.capture.MemoryHeapDumpCaptureSession
import com.androidperformancestudio.memory.model.NativeHeapEvidenceSource
import com.androidperformancestudio.memory.presentation.MemoryProcessOption
import com.androidperformancestudio.platform.adb.AdbBinaryResult
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbCommandFailedException
import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.platform.adb.AdbTextResult
import com.androidperformancestudio.platform.toolchain.HostCancellationSignal
import com.androidperformancestudio.platform.toolchain.HostCapturedText
import com.androidperformancestudio.platform.toolchain.HostCommandOutput
import com.androidperformancestudio.platform.toolchain.HostCommandResult
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class DesktopMemoryProfilerBackendTest {
    @Test
    fun `native import uses visible partial wire fallback only when the tool is unavailable`() =
        kotlinx.coroutines.test.runTest {
            val root = createTempDirectory("native-fallback")
            val trace = root.resolve("native.pb").also { Files.write(it, byteArrayOf()) }
            val backend =
                DesktopMemoryProfilerBackend(
                    dataRoot = root,
                    nativeHeapArtifactAnalyzer =
                        NativeHeapArtifactAnalyzer { _, _ ->
                            NativeHeapProcessingResult.Unavailable("TRACE_PROCESSOR_NOT_FOUND: install the pinned tool")
                        },
                )

            val loaded = assertIs<MemoryBackendResult.Success<LoadedNativeHeap>>(backend.importNativeHeap(trace)).value

            assertEquals(NativeHeapEvidenceSource.WIRE_FALLBACK, loaded.trace.evidenceSource)
            assertEquals(
                "PARTIAL",
                loaded.trace.artifact
                    ?.completeness
                    ?.name,
            )
            assertContains(loaded.trace.fallbackReason.orEmpty(), "TRACE_PROCESSOR_NOT_FOUND")
            assertTrue(
                root
                    .resolve("capture-artifacts")
                    .toFile()
                    .listFiles()
                    .orEmpty()
                    .isNotEmpty(),
            )
        }

    @Test
    fun `native import does not wire-fallback query failures`() =
        kotlinx.coroutines.test.runTest {
            val root = createTempDirectory("native-query-failure")
            val trace = root.resolve("native.pb").also { Files.writeString(it, "valid-enough") }
            val backend =
                DesktopMemoryProfilerBackend(
                    dataRoot = root,
                    nativeHeapArtifactAnalyzer =
                        NativeHeapArtifactAnalyzer { _, _ ->
                            NativeHeapProcessingResult.Failure("TRACE_QUERY_FAILED: schema changed")
                        },
                )

            val failure = assertIs<MemoryBackendResult.Failure>(backend.importNativeHeap(trace))

            assertContains(failure.detail, "TRACE_QUERY_FAILED")
            assertTrue(!root.resolve("capture-artifacts").toFile().exists())
        }

    @Test
    fun `native import reports corrupt bytes when fallback parser cannot validate them`() =
        kotlinx.coroutines.test.runTest {
            val root = createTempDirectory("native-corrupt")
            val trace = root.resolve("native.pb").also { Files.writeString(it, "not protobuf") }
            val backend =
                DesktopMemoryProfilerBackend(
                    dataRoot = root,
                    nativeHeapArtifactAnalyzer =
                        NativeHeapArtifactAnalyzer { _, _ ->
                            NativeHeapProcessingResult.Unavailable("TRACE_PROCESSOR_NOT_FOUND")
                        },
                )

            val failure = assertIs<MemoryBackendResult.Failure>(backend.importNativeHeap(trace))

            assertContains(failure.detail, "corrupt")
        }

    @Test
    fun `standard hprof import parses and builds histogram end to end`() =
        kotlinx.coroutines.test.runTest {
            val root = createTempDirectory("memory-backend")
            val hprof = root.resolve("minimal-standard.hprof")
            Files.write(hprof, minimalStandardHprof())
            val backend = DesktopMemoryProfilerBackend(root)
            val progress = mutableListOf<Int>()

            val result = backend.importHprof(hprof, progress::add)

            val loaded = assertIs<MemoryBackendResult.Success<LoadedHeap>>(result).value
            assertEquals(hprof, loaded.heapDump.rawHprofFile)
            assertNull(loaded.heapDump.convertedHprofFile)
            assertTrue(loaded.heapDump.id.startsWith("import-"))
            assertEquals(2, loaded.histogram.summary.objectCount)
            val sampleClass = loaded.histogram.classes.single { it.className == "com.example.Sample" }
            assertEquals(
                "com.example.Sample",
                sampleClass.className,
            )
            assertEquals(
                24L,
                sampleClass.shallowSize,
            )
            assertEquals(
                24L,
                sampleClass.retainedSize,
            )
            assertEquals(0, progress.first())
            assertEquals(100, progress.last())
        }

    @Test
    fun `large hprof import is handed to parser instead of phase one size gate`() =
        kotlinx.coroutines.test.runTest {
            val root = createTempDirectory("memory-oversized-import")
            val hprof = root.resolve("oversized.hprof")
            RandomAccessFile(hprof.toFile(), "rw").use { file ->
                file.setLength(257L * 1024L * 1024L)
            }
            val backend = DesktopMemoryProfilerBackend(root)

            val result = backend.importHprof(hprof)

            val failure = assertIs<MemoryBackendResult.Failure>(result)
            assertEquals("Unable to analyze HPROF", failure.title)
            assertContains(failure.detail, "Unsupported HPROF")
        }

    @Test
    fun `import exposes parser warnings instead of silently showing an empty histogram`() =
        kotlinx.coroutines.test.runTest {
            val root = createTempDirectory("memory-warning-import")
            val hprof = root.resolve("unsupported-record.hprof")
            Files.write(hprof, hprofWithUnknownHeapRecord())
            val backend = DesktopMemoryProfilerBackend(root)

            val result = backend.importHprof(hprof)

            val loaded = assertIs<MemoryBackendResult.Success<LoadedHeap>>(result).value
            assertEquals(0, loaded.histogram.summary.objectCount)
            assertContains(loaded.warning.orEmpty(), "Unknown heap dump sub-record")
            assertContains(loaded.warning.orEmpty(), "No heap objects were parsed")
        }

    @Test
    fun `fake capture writes converted hprof that backend parses into histogram`() =
        kotlinx.coroutines.test.runTest {
            val root = createTempDirectory("memory-capture-backend")
            val sdk = createSdkWithHprofConv()
            val runner = FileWritingCaptureRunner(convertedBytes = minimalStandardHprof())
            val backend = captureBackend(root, sdk, runner)

            val result = backend.capture("device-1", sampleProcess())

            val loaded = assertIs<MemoryBackendResult.Success<LoadedHeap>>(result).value
            assertEquals(listOf("dumpheap", "pull", "getprop", "hprof-conv", "rm"), runner.commandKinds)
            assertContentEquals(RAW_ANDROID_HPROF, Files.readAllBytes(loaded.heapDump.rawHprofFile))
            assertTrue(loaded.heapDump.convertedHprofFile?.exists() == true)
            assertEquals("JAVA PROFILE 1.0.2", loaded.heapDump.format)
            assertEquals(2, loaded.histogram.summary.objectCount)
            val classStats = loaded.histogram.classes.single { it.className == "com.example.Sample" }
            assertEquals("com.example.Sample", classStats.className)
            assertEquals(24L, classStats.shallowSize)
            assertNull(loaded.warning)
        }

    @Test
    fun `missing converter parses raw Android hprof directly`() =
        kotlinx.coroutines.test.runTest {
            val root = createTempDirectory("memory-capture-no-converter")
            val emptySdk = createTempDirectory("empty-sdk")
            val rawAndroidHprof = minimalStandardHprof()
            val runner = FileWritingCaptureRunner(convertedBytes = null, rawBytes = rawAndroidHprof)
            val backend = captureBackend(root, emptySdk, runner)

            val result = backend.capture("device-1", sampleProcess())

            val loaded = assertIs<MemoryBackendResult.Success<LoadedHeap>>(result).value
            assertEquals(listOf("dumpheap", "pull", "getprop", "rm"), runner.commandKinds)
            assertContentEquals(rawAndroidHprof, Files.readAllBytes(loaded.heapDump.rawHprofFile))
            assertNull(loaded.heapDump.convertedHprofFile)
            assertEquals("JAVA PROFILE 1.0.2", loaded.heapDump.format)
            assertEquals(2, loaded.histogram.summary.objectCount)
            val sampleClass = loaded.histogram.classes.single { it.className == "com.example.Sample" }
            assertEquals("com.example.Sample", sampleClass.className)
            assertContains(loaded.warning.orEmpty(), "Install SDK Platform Tools")
            assertContains(loaded.warning.orEmpty(), "parsed the Android HPROF directly")
        }

    private fun captureBackend(
        root: Path,
        sdkRoot: Path,
        runner: FileWritingCaptureRunner,
    ): DesktopMemoryProfilerBackend =
        DesktopMemoryProfilerBackend(
            dataRoot = root,
            adbLocator = { Path.of("adb") },
            captureSessionFactory = { adb ->
                MemoryHeapDumpCaptureSession(
                    adbClient = CaptureRunnerAdbClient(runner::run),
                    hprofConvLocator =
                        AndroidSdkHprofConvLocator(
                            environment = mapOf("ANDROID_HOME" to sdkRoot.toString()),
                            defaultSdkRoot = null,
                        ),
                    hostProcessRunner = runner::run,
                )
            },
        )

    private fun sampleProcess(): MemoryProcessOption =
        MemoryProcessOption(pid = 42, name = "com.example.debug", packageName = "com.example.debug")

    private fun createSdkWithHprofConv(): Path {
        val sdk = createTempDirectory("sdk")
        val executableName =
            if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
                "hprof-conv.exe"
            } else {
                "hprof-conv"
            }
        val converter = sdk.resolve("platform-tools").resolve(executableName)
        Files.createDirectories(converter.parent)
        Files.writeString(converter, "fake converter")
        converter.toFile().setExecutable(true)
        return sdk
    }

    private class FileWritingCaptureRunner(
        private val convertedBytes: ByteArray?,
        private val rawBytes: ByteArray = RAW_ANDROID_HPROF,
    ) {
        val commandKinds = mutableListOf<String>()

        suspend fun run(
            request: HostProcessRequest,
            signal: HostCancellationSignal,
        ): HostCommandResult {
            check(!signal.isCancelled)
            val kind = request.commandKind()
            commandKinds += kind
            when (kind) {
                "pull" -> Files.write(Path.of(request.arguments.last()), rawBytes)
                "hprof-conv" -> Files.write(Path.of(request.arguments.last()), checkNotNull(convertedBytes))
            }
            return HostCommandResult.Completed(
                HostCommandOutput(
                    pid = 1L,
                    command = request.command,
                    exitCode = 0,
                    stdout = HostCapturedText("", truncated = false),
                    stderr = HostCapturedText("", truncated = false),
                    startedAt = Instant.EPOCH,
                    finishedAt = Instant.EPOCH,
                ),
            )
        }

        private fun HostProcessRequest.commandKind(): String =
            when {
                arguments.contains("dumpheap") -> "dumpheap"
                arguments.contains("pull") -> "pull"
                arguments.contains("getprop") -> "getprop"
                arguments.contains("rm") -> "rm"
                executable.fileName.toString().startsWith("hprof-conv") -> "hprof-conv"
                else -> executable.fileName.toString()
            }
    }

    private fun minimalStandardHprof(): ByteArray =
        ByteArrayOutputStream()
            .also { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.write("JAVA PROFILE 1.0.2".encodeToByteArray())
                    output.writeByte(0)
                    output.writeInt(Int.SIZE_BYTES)
                    output.writeLong(0L)
                    output.writeRecord(
                        STRING_IN_UTF8,
                        byteArray {
                            writeInt(1)
                            write("com.example.Sample".encodeToByteArray())
                        },
                    )
                    output.writeRecord(
                        LOAD_CLASS,
                        byteArray {
                            writeInt(1)
                            writeInt(2)
                            writeInt(0)
                            writeInt(1)
                        },
                    )
                    output.writeRecord(
                        HEAP_DUMP,
                        byteArray {
                            writeByte(CLASS_DUMP)
                            writeInt(2)
                            writeInt(0)
                            repeat(CLASS_DUMP_ID_FIELDS) { writeInt(0) }
                            writeInt(24)
                            writeShort(0)
                            writeShort(0)
                            writeShort(0)
                            writeByte(INSTANCE_DUMP)
                            writeInt(3)
                            writeInt(0)
                            writeInt(2)
                            writeInt(0)
                        },
                    )
                }
            }.toByteArray()

    private fun hprofWithUnknownHeapRecord(): ByteArray =
        ByteArrayOutputStream()
            .also { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.write("JAVA PROFILE 1.0.3".encodeToByteArray())
                    output.writeByte(0)
                    output.writeInt(Int.SIZE_BYTES)
                    output.writeLong(0L)
                    output.writeRecord(HEAP_DUMP, byteArrayOf(0x7d))
                }
            }.toByteArray()

    private fun DataOutputStream.writeRecord(
        tag: Int,
        payload: ByteArray,
    ) {
        writeByte(tag)
        writeInt(0)
        writeInt(payload.size)
        write(payload)
    }

    private fun byteArray(block: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { it.block() } }.toByteArray()

    companion object {
        private const val STRING_IN_UTF8 = 0x01
        private const val LOAD_CLASS = 0x02
        private const val HEAP_DUMP = 0x0c
        private const val CLASS_DUMP = 0x20
        private const val INSTANCE_DUMP = 0x21
        private const val CLASS_DUMP_ID_FIELDS = 6
        private val RAW_ANDROID_HPROF = "ANDROID PROFILE 1.0.3\u0000invalid-standard-data".encodeToByteArray()
    }
}

private class CaptureRunnerAdbClient(
    private val invocation: suspend (HostProcessRequest, HostCancellationSignal) -> HostCommandResult,
) : AdbClient {
    override suspend fun listDevices(): List<AdbDevice> = emptyList()

    override suspend fun shell(
        serial: String,
        arguments: List<String>,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult = execute(serial, listOf("shell") + arguments, timeout)

    override suspend fun execOut(
        serial: String,
        arguments: List<String>,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbBinaryResult = error("Not used")

    override suspend fun push(
        serial: String,
        localPath: Path,
        remotePath: String,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult = error("Not used")

    override suspend fun pull(
        serial: String,
        remotePath: String,
        localPath: Path,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult = execute(serial, listOf("pull", remotePath, localPath.toString()), timeout)

    override suspend fun forward(
        serial: String,
        local: String,
        remote: String,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult = error("Not used")

    override suspend fun removeForward(
        serial: String,
        local: String,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult = error("Not used")

    override suspend fun bugreport(
        serial: String,
        outputPath: Path,
        timeout: Duration,
        maxOutputBytesPerStream: Int,
        isCancellationRequested: () -> Boolean,
    ): AdbTextResult = error("Not used")

    private suspend fun execute(
        serial: String,
        arguments: List<String>,
        timeout: Duration,
    ): AdbTextResult {
        val request =
            HostProcessRequest(
                executable = Path.of("adb"),
                arguments = listOf("-s", serial) + arguments,
                timeout = timeout,
            )
        return when (val result = invocation(request, HostCancellationSignal())) {
            is HostCommandResult.Completed ->
                AdbTextResult(
                    exitCode = result.output.exitCode ?: 0,
                    stdout = result.output.stdout.text,
                    stderr = result.output.stderr.text,
                    duration = Duration.ZERO,
                )
            is HostCommandResult.Failed ->
                throw AdbCommandFailedException(
                    request.command,
                    result.output?.exitCode ?: 1,
                    result.output
                        ?.stderr
                        ?.text
                        .orEmpty()
                        .ifBlank { result.error.message },
                )
        }
    }
}
