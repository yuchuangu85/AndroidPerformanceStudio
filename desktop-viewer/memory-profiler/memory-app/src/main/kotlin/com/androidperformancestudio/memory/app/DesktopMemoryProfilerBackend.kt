package com.androidperformancestudio.memory.app

import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.memory.memory_app.generated.resources.Res
import com.androidperformancestudio.memory.memory_app.generated.resources.*

import com.androidperformancestudio.adb.AdbDeviceRefresher
import com.androidperformancestudio.adb.AdbDeviceState
import com.androidperformancestudio.adb.AdbTargetCatalog
import com.androidperformancestudio.adb.SystemAdbLocator
import com.androidperformancestudio.memory.presentation.MemoryDeviceOption
import com.androidperformancestudio.memory.presentation.MemoryProcessOption
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.SystemHostPlatformDetector
import dev.agentperf.memory.analysis.MemoryDeepAnalyzer
import dev.agentperf.memory.capture.MemoryCaptureRequest
import dev.agentperf.memory.capture.MemoryHeapDumpCaptureSession
import dev.agentperf.memory.export.MemoryExportAdapters
import dev.agentperf.memory.hprof.HprofParseException
import dev.agentperf.memory.hprof.HprofParser
import dev.agentperf.memory.model.HeapDump
import dev.agentperf.memory.model.HeapHistogram
import dev.agentperf.memory.model.HeapSummary
import dev.agentperf.memory.storage.MemorySessionMetadata
import dev.agentperf.memory.storage.SqliteMemorySessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Suppress("TooManyFunctions")
internal class DesktopMemoryProfilerBackend(
    private val dataRoot: Path = defaultDataRoot(),
    private val adbLocator: () -> Path? = ::locateSystemAdb,
    private val captureSessionFactory: (Path) -> MemoryHeapDumpCaptureSession = ::MemoryHeapDumpCaptureSession,
    private val language: UiLanguage = UiLanguage.ENGLISH,
) : MemoryProfilerBackend {
    private val parser = HprofParser()
    private val analyzer = MemoryDeepAnalyzer()
    private val exports = MemoryExportAdapters()

    override suspend fun listDevices(): MemoryBackendResult<List<MemoryDeviceOption>> {
        val adb = adbLocator() ?: return missingAdb()
        return when (val result = AdbDeviceRefresher(adb).refresh()) {
            is StudioResult.Failure ->
                result.toBackendFailure(localizedStringResource(Res.string.unable_to_list_android_devices, language))
            is StudioResult.Success ->
                MemoryBackendResult.Success(
                    result.value.map { device ->
                        MemoryDeviceOption(
                            serial = device.serial,
                            name = device.model?.replace('_', ' ') ?: device.serial,
                            online = device.state == AdbDeviceState.ONLINE,
                        )
                    },
                )
        }
    }

    override suspend fun listProcesses(serial: String): MemoryBackendResult<List<MemoryProcessOption>> {
        val adb = adbLocator() ?: return missingAdb()
        return when (val result = AdbTargetCatalog(adb).refresh(serial)) {
            is StudioResult.Failure ->
                result.toBackendFailure(localizedStringResource(Res.string.unable_to_list_device_processes, language))
            is StudioResult.Success -> {
                val debuggablePackages =
                    result.value.packages
                        .filter { it.debuggable }
                        .mapTo(hashSetOf()) { it.packageName }
                val processes =
                    result.value.processes
                        .mapNotNull { process ->
                            val packageName =
                                debuggablePackages.firstOrNull { candidate ->
                                    process.name == candidate || process.name.startsWith("$candidate:")
                                } ?: return@mapNotNull null
                            MemoryProcessOption(pid = process.pid, name = process.name, packageName = packageName)
                        }.sortedWith(compareBy<MemoryProcessOption> { it.name }.thenBy { it.pid })
                MemoryBackendResult.Success(processes)
            }
        }
    }

    override suspend fun capture(
        serial: String,
        process: MemoryProcessOption,
    ): MemoryBackendResult<LoadedHeap> {
        val adb = adbLocator() ?: return missingAdb()
        val sessionId = sessionId()
        val captureSession = captureSessionFactory(adb)
        return when (
            val result =
                captureSession.capture(
                    MemoryCaptureRequest(
                        sessionId = sessionId,
                        sessionRoot = dataRoot.resolve("sessions"),
                        serial = serial,
                        pid = process.pid,
                        packageName = process.packageName,
                    ),
                )
        ) {
            is StudioResult.Failure -> result.toBackendFailure(localizedStringResource(Res.string.heap_dump_failed, language))
            is StudioResult.Success -> {
                val capture = result.value
                val warning =
                    capture.warnings
                        .filterNot { it.code == "DEVICE_CLEANUP_FAILED" }
                        .joinToString(separator = "\n", transform = { it.message })
                        .ifBlank { null }
                val cleanupWarning =
                    capture.warnings
                        .firstOrNull { it.code == "DEVICE_CLEANUP_FAILED" }
                        ?.message
                val identity =
                    CapturedSessionIdentity(
                        serial = serial,
                        packageName = process.packageName,
                        sessionId = sessionId,
                        pid = process.pid,
                    )
                val convertedFile = capture.convertedHprofFile
                if (convertedFile == null) {
                    loadHeap(
                        HeapLoadRequest(
                            file = capture.rawHprofFile,
                            rawFile = capture.rawHprofFile,
                            warning = listOfNotNull(
                                warning,
                                localizedStringResource(Res.string.hprof_conv_unavailable, language),
                            ).joinToString("\n"),
                            cleanupWarning = cleanupWarning,
                            sessionMetadata = identity,
                        ),
                    )
                } else {
                    loadHeap(
                        HeapLoadRequest(
                            file = convertedFile,
                            rawFile = capture.rawHprofFile,
                            convertedFile = convertedFile,
                            warning = warning,
                            cleanupWarning = cleanupWarning,
                            sessionMetadata = identity,
                        ),
                    )
                }
            }
        }
    }

    @Suppress("ktlint:standard:function-expression-body")
    override suspend fun importHprof(file: Path): MemoryBackendResult<LoadedHeap> =
        importHprof(file) {}

    override suspend fun importHprof(
        file: Path,
        onProgress: (Int) -> Unit,
    ): MemoryBackendResult<LoadedHeap> {
        return withContext(Dispatchers.IO) {
            if (!Files.isRegularFile(file)) {
                MemoryBackendResult.Failure(
                    localizedStringResource(Res.string.hprof_file_not_found, language),
                    localizedStringResource(Res.string.hprof_file_not_readable, language, file.fileName),
                )
            } else {
                loadHeap(HeapLoadRequest(file = file, rawFile = file), onProgress)
            }
        }
    }

    override fun exportRaw(
        heapDump: HeapDump,
        output: Path,
    ) {
        exports.copyRawHprof(heapDump, output)
    }

    override fun exportConverted(
        heapDump: HeapDump,
        output: Path,
    ) {
        exports.copyConvertedHprof(heapDump, output)
    }

    override fun exportHistogram(
        histogram: dev.agentperf.memory.model.HeapHistogram,
        output: Path,
    ) {
        exports.exportClassHistogramCsv(histogram, output)
    }

    private fun loadHeap(
        request: HeapLoadRequest,
        onProgress: (Int) -> Unit = {},
    ): MemoryBackendResult<LoadedHeap> =
        try {
            val parsedFile =
                parser.parse(request.file, onProgress).copy(
                    rawHprofFile = request.rawFile,
                    convertedHprofFile = request.convertedFile,
                )
            val deepAnalysis = analyzer.analyze(parsedFile)
            val histogram = deepAnalysis.histogram
            val parserWarning =
                parsedFile.warnings
                    .joinToString(separator = "\n", transform = { it.message })
                    .ifBlank { null }
            val emptyHeapWarning =
                if (histogram.summary.objectCount == 0) {
                    localizedStringResource(Res.string.no_heap_objects_parsed, language, request.file.fileName)
                } else {
                    null
                }
            val capturedAt = Instant.now()
            val parsed =
                parsedFile.copy(
                    id = request.sessionMetadata?.sessionId ?: request.file.fileName.toString(),
                    packageName = request.sessionMetadata?.packageName.orEmpty(),
                    pid = request.sessionMetadata?.pid ?: 0,
                    capturedAt = capturedAt,
                    heapSummary = histogram.summary,
                    topClasses = histogram.classes,
                    leakSuspects = deepAnalysis.leakSuspects,
                    objectRetainedSizes = deepAnalysis.dominatorTree.retainedSizes,
                    bitmapInstances = deepAnalysis.bitmapInstances,
                )
            request.sessionMetadata?.let { metadata ->
                persistSession(metadata, capturedAt, request.rawFile, request.convertedFile, histogram.summary)
            }
            MemoryBackendResult.Success(
                LoadedHeap(
                    heapDump = parsed,
                    histogram = histogram,
                    warning =
                        listOfNotNull(request.warning, parserWarning, emptyHeapWarning)
                            .joinToString("\n")
                            .ifBlank { null },
                    cleanupWarning = request.cleanupWarning,
                ),
            )
        } catch (exception: HprofParseException) {
            analysisFailure(exception)
        } catch (exception: IOException) {
            analysisFailure(exception)
        } catch (exception: IllegalArgumentException) {
            analysisFailure(exception)
        } catch (exception: SQLException) {
            analysisFailure(exception)
        }

    private fun persistSession(
        metadata: CapturedSessionIdentity,
        capturedAt: Instant,
        rawFile: Path,
        convertedFile: Path?,
        summary: HeapSummary,
    ) {
        SqliteMemorySessionStore.open(dataRoot.resolve("memory-sessions.db")).use { store ->
            store.upsert(
                MemorySessionMetadata(
                    sessionId = metadata.sessionId,
                    packageName = metadata.packageName,
                    deviceSerial = metadata.serial,
                    capturedAt = capturedAt,
                    rawHprofFile = rawFile,
                    convertedHprofFile = convertedFile,
                    classCount = summary.classCount,
                    objectCount = summary.objectCount,
                    shallowSizeBytes = summary.shallowSize,
                ),
            )
        }
    }

    private fun analysisFailure(exception: Exception): MemoryBackendResult.Failure =
        MemoryBackendResult.Failure(
            title = localizedStringResource(Res.string.unable_to_analyze_hprof, language),
            detail = exception.message ?: exception::class.simpleName.orEmpty(),
        )

    private fun StudioResult.Failure.toBackendFailure(title: String): MemoryBackendResult.Failure =
        MemoryBackendResult.Failure(title = title, detail = error.message)

    private fun missingAdb(): MemoryBackendResult.Failure =
        MemoryBackendResult.Failure(
            title = localizedStringResource(Res.string.android_sdk_platform_tools_not_found, language),
            detail = localizedStringResource(Res.string.install_sdk_platform_tools, language),
        )

    private fun sessionId(): String = SESSION_ID_FORMAT.format(Instant.now())

    private data class CapturedSessionIdentity(
        val serial: String,
        val packageName: String,
        val sessionId: String,
        val pid: Int,
    )

    private data class HeapLoadRequest(
        val file: Path,
        val rawFile: Path,
        val convertedFile: Path? = null,
        val warning: String? = null,
        val cleanupWarning: String? = null,
        val sessionMetadata: CapturedSessionIdentity? = null,
    )

    companion object {
        private const val MEBIBYTE = 1024L * 1024L
        private val SESSION_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)

        private fun defaultDataRoot(): Path =
            Path.of(
                System.getProperty("user.home"),
                ".android-performance-studio",
                "memory-profiler",
            )

        private fun locateSystemAdb(): Path? {
            val platform = (SystemHostPlatformDetector().detect() as? StudioResult.Success)?.value ?: return null
            return (SystemAdbLocator(platform).locate() as? StudioResult.Success)?.value?.executable
        }
    }
}
