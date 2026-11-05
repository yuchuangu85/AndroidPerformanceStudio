package com.androidperformancestudio.memory.app

import com.androidperformancestudio.adb.AdbTargetSnapshot
import com.androidperformancestudio.adb.AndroidPackage
import com.androidperformancestudio.adb.AndroidProcess
import com.androidperformancestudio.memory.capture.AndroidSdkHprofConvLocator
import com.androidperformancestudio.memory.capture.MemoryHeapDumpCaptureSession
import com.androidperformancestudio.memory.memory_app.generated.resources.Res
import com.androidperformancestudio.memory.memory_app.generated.resources.hprof_file_not_found
import com.androidperformancestudio.memory.memory_app.generated.resources.hprof_file_not_readable
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapHistogram
import com.androidperformancestudio.memory.model.LeakCanaryReport
import com.androidperformancestudio.memory.model.LeakCanaryStatus
import com.androidperformancestudio.memory.model.NativeHeapEvidenceSource
import com.androidperformancestudio.memory.presentation.MemoryProcessOption
import com.androidperformancestudio.memory.storage.MemorySessionMetadata
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
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

@Suppress("LargeClass")
class DesktopMemoryProfilerBackendTest {


    @Test
    fun `application process selection does not require debug or profileable flags`() {
        val snapshot =
            AdbTargetSnapshot(
                packages =
                    listOf(
                        AndroidPackage("com.example.debug", debuggable = true),
                        AndroidPackage("com.example.profileable", profileableByShell = true),
                        AndroidPackage("com.example.hidden"),
                        AndroidPackage("com.android.systemui"),
                    ),
                processes =
                    listOf(
                        AndroidProcess(10, 1, "u0_a10", "com.example.debug"),
                        AndroidProcess(11, 1, "u0_a11", "com.example.profileable:worker"),
                        AndroidProcess(12, 1, "u0_a12", "com.example.hidden"),
                        AndroidProcess(2821, 1, "u0_a100", "com.android.systemui"),
                        AndroidProcess(13, 1, "system", "surfaceflinger"),
                        AndroidProcess(14, 1, "u0_a14", "com.example.hidden.other"),
                    ),
            )

        val processes = snapshot.heapDumpableProcesses()

        assertEquals(
            listOf(
                MemoryProcessOption(2821, "com.android.systemui", "com.android.systemui"),
                MemoryProcessOption(10, "com.example.debug", "com.example.debug"),
                MemoryProcessOption(12, "com.example.hidden", "com.example.hidden"),
                MemoryProcessOption(11, "com.example.profileable:worker", "com.example.profileable"),
            ),
            processes,
        )
    }

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

    private fun standardHprofForClasses(names: List<String>): ByteArray =
        ByteArrayOutputStream()
            .also { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.write("JAVA PROFILE 1.0.2".encodeToByteArray())
                    output.writeByte(0)
                    output.writeInt(Int.SIZE_BYTES)
                    output.writeLong(0L)
                    names.forEachIndexed { index, name ->
                        output.writeRecord(
                            STRING_IN_UTF8,
                            byteArray {
                                writeInt(1 + index * 3)
                                write(name.encodeToByteArray())
                            },
                        )
                        output.writeRecord(
                            LOAD_CLASS,
                            byteArray {
                                writeInt(index + 1)
                                writeInt(2 + index * 3)
                                writeInt(0)
                                writeInt(1 + index * 3)
                            },
                        )
                    }
                    output.writeRecord(
                        HEAP_DUMP,
                        byteArray {
                            names.indices.forEach { index ->
                                writeByte(CLASS_DUMP)
                                writeInt(2 + index * 3)
                                writeInt(0)
                                repeat(CLASS_DUMP_ID_FIELDS) { writeInt(0) }
                                writeInt(24)
                                writeShort(0)
                                writeShort(0)
                                writeShort(0)
                                writeByte(INSTANCE_DUMP)
                                writeInt(3 + index * 3)
                                writeInt(0)
                                writeInt(2 + index * 3)
                                writeInt(0)
                            }
                        },
                    )
                }
            }.toByteArray()

    private fun hprofWithoutHeapDump(): ByteArray =
        ByteArrayOutputStream()
            .also { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.write("JAVA PROFILE 1.0.3".encodeToByteArray())
                    output.writeByte(0)
                    output.writeInt(Int.SIZE_BYTES)
                    output.writeLong(0L)
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
