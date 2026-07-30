package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.presentation.MemoryProcessOption
import com.androidperformancestudio.toolchain.CapturedProcessText
import com.androidperformancestudio.toolchain.ProcessCancellationSignal
import com.androidperformancestudio.toolchain.ProcessOutput
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import com.androidperformancestudio.memory.capture.AndroidSdkHprofConvLocator
import com.androidperformancestudio.memory.capture.MemoryHeapDumpCaptureSession
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

class DesktopMemoryProfilerBackendTest {
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
            assertEquals("minimal-standard.hprof", loaded.heapDump.id)
            assertEquals(1, loaded.histogram.summary.objectCount)
            assertEquals(
                "com.example.Sample",
                loaded.histogram.classes
                    .single()
                    .className,
            )
            assertEquals(
                24L,
                loaded.histogram.classes
                    .single()
                    .shallowSize,
            )
            assertEquals(
                24L,
                loaded.histogram.classes
                    .single()
                    .retainedSize,
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
            assertEquals(listOf("dumpheap", "pull", "hprof-conv", "rm"), runner.commandKinds)
            assertContentEquals(RAW_ANDROID_HPROF, Files.readAllBytes(loaded.heapDump.rawHprofFile))
            assertTrue(loaded.heapDump.convertedHprofFile?.exists() == true)
            assertEquals("JAVA PROFILE 1.0.2", loaded.heapDump.format)
            assertEquals(1, loaded.histogram.summary.objectCount)
            val classStats = loaded.histogram.classes.single()
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
            assertEquals(listOf("dumpheap", "pull", "rm"), runner.commandKinds)
            assertContentEquals(rawAndroidHprof, Files.readAllBytes(loaded.heapDump.rawHprofFile))
            assertNull(loaded.heapDump.convertedHprofFile)
            assertEquals("JAVA PROFILE 1.0.2", loaded.heapDump.format)
            assertEquals(1, loaded.histogram.summary.objectCount)
            assertEquals("com.example.Sample", loaded.histogram.classes.single().className)
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
                    adbExecutable = adb,
                    hprofConvLocator =
                        AndroidSdkHprofConvLocator(
                            environment = mapOf("ANDROID_HOME" to sdkRoot.toString()),
                            defaultSdkRoot = null,
                        ),
                    processRunner = runner::run,
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
            request: ProcessRequest,
            signal: ProcessCancellationSignal,
        ): ProcessRunResult {
            check(!signal.isCancelled)
            val kind = request.commandKind()
            commandKinds += kind
            when (kind) {
                "pull" -> Files.write(Path.of(request.arguments.last()), rawBytes)
                "hprof-conv" -> Files.write(Path.of(request.arguments.last()), checkNotNull(convertedBytes))
            }
            return ProcessRunResult.Completed(
                ProcessOutput(
                    pid = 1L,
                    command = request.command,
                    exitCode = 0,
                    stdout = CapturedProcessText("", truncated = false),
                    stderr = CapturedProcessText("", truncated = false),
                    startedAt = Instant.EPOCH,
                    finishedAt = Instant.EPOCH,
                ),
            )
        }

        private fun ProcessRequest.commandKind(): String =
            when {
                arguments.contains("dumpheap") -> "dumpheap"
                arguments.contains("pull") -> "pull"
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
