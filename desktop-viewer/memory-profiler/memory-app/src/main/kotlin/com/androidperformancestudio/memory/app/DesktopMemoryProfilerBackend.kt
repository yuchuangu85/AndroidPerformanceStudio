@file:Suppress("LongMethod", "MaxLineLength", "MagicNumber", "TooGenericExceptionCaught")

package com.androidperformancestudio.memory.app

import com.androidperformancestudio.adb.AdbDevicePropertiesReader
import com.androidperformancestudio.adb.AdbTargetSnapshot
import com.androidperformancestudio.adb.AndroidTargetListener
import com.androidperformancestudio.adb.AndroidTargetMonitor
import com.androidperformancestudio.adb.AndroidTargetMonitors
import com.androidperformancestudio.adb.AndroidTargetSubscription
import com.androidperformancestudio.adb.applicationProcesses
import com.androidperformancestudio.adb.defaultAdbExecutable
import com.androidperformancestudio.memory.analysis.BitmapDumpAnalysisRequest
import com.androidperformancestudio.memory.analysis.BitmapDumpAnalyzer
import com.androidperformancestudio.memory.analysis.HeapGraphToHeapDump
import com.androidperformancestudio.memory.analysis.JavaHeapParseResult
import com.androidperformancestudio.memory.analysis.JavaHeapTraceParser
import com.androidperformancestudio.memory.analysis.MemoryDeepAnalyzer
import com.androidperformancestudio.memory.analysis.MemoryHistogramAnalyzer
import com.androidperformancestudio.memory.analysis.NativeHeapTraceParser
import com.androidperformancestudio.memory.analysis.ProguardMapping
import com.androidperformancestudio.memory.analysis.ProguardMappingParser
import com.androidperformancestudio.memory.analysis.SharkLeakAnalyzer
import com.androidperformancestudio.memory.analysis.isLikelyObfuscatedClassName
import com.androidperformancestudio.memory.analysis.withDeobfuscation
import com.androidperformancestudio.memory.capture.AndroidProjectLeakCanaryInjector
import com.androidperformancestudio.memory.capture.BitmapCaptureRequest
import com.androidperformancestudio.memory.capture.BitmapHeapDumpCaptureSession
import com.androidperformancestudio.memory.capture.LeakCanaryAgentConnection
import com.androidperformancestudio.memory.capture.MemoryCaptureRequest
import com.androidperformancestudio.memory.capture.MemoryHeapDumpCaptureSession
import com.androidperformancestudio.memory.capture.NativeHeapCaptureRequest
import com.androidperformancestudio.memory.capture.NativeHeapCaptureSession
import com.androidperformancestudio.memory.capture.SampledArtAllocationCaptureRequest
import com.androidperformancestudio.memory.capture.SampledArtAllocationCaptureSession
import com.androidperformancestudio.memory.export.BitmapDumpExportAdapters
import com.androidperformancestudio.memory.export.MemoryExportAdapters
import com.androidperformancestudio.memory.hprof.BitmapDumpParseException
import com.androidperformancestudio.memory.hprof.BitmapDumpParser
import com.androidperformancestudio.memory.leak.protocol.LeakCanaryAgentResponse
import com.androidperformancestudio.memory.memory_app.generated.resources.Res
import com.androidperformancestudio.memory.memory_app.generated.resources.android_sdk_platform_tools_not_found
import com.androidperformancestudio.memory.memory_app.generated.resources.bitmap_dump_failed
import com.androidperformancestudio.memory.memory_app.generated.resources.heap_dump_failed
import com.androidperformancestudio.memory.memory_app.generated.resources.hprof_conv_unavailable
import com.androidperformancestudio.memory.memory_app.generated.resources.hprof_file_not_found
import com.androidperformancestudio.memory.memory_app.generated.resources.hprof_file_not_readable
import com.androidperformancestudio.memory.memory_app.generated.resources.install_sdk_platform_tools
import com.androidperformancestudio.memory.memory_app.generated.resources.java_heap_file_not_found
import com.androidperformancestudio.memory.memory_app.generated.resources.mapping_not_loaded_hint
import com.androidperformancestudio.memory.memory_app.generated.resources.native_heap_trace_file_not_found
import com.androidperformancestudio.memory.memory_app.generated.resources.no_heap_objects_parsed
import com.androidperformancestudio.memory.memory_app.generated.resources.unable_to_analyze_hprof
import com.androidperformancestudio.memory.memory_app.generated.resources.unable_to_capture_native_heap
import com.androidperformancestudio.memory.memory_app.generated.resources.unable_to_import_java_heap
import com.androidperformancestudio.memory.memory_app.generated.resources.unable_to_list_android_devices
import com.androidperformancestudio.memory.memory_app.generated.resources.unable_to_list_device_processes
import com.androidperformancestudio.memory.memory_app.generated.resources.unable_to_load_mapping
import com.androidperformancestudio.memory.model.BitmapDumpComparison
import com.androidperformancestudio.memory.model.BitmapDumpSession
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapHistogram
import com.androidperformancestudio.memory.model.HeapSummary
import com.androidperformancestudio.memory.model.LeakCanaryLiveEvent
import com.androidperformancestudio.memory.model.LeakCanaryLiveSession
import com.androidperformancestudio.memory.model.LeakCanaryLiveStatus
import com.androidperformancestudio.memory.model.LeakCanaryReport
import com.androidperformancestudio.memory.model.LeakCanaryStatus
import com.androidperformancestudio.memory.model.NativeHeapEvidenceSource
import com.androidperformancestudio.memory.model.NativeHeapTrace
import com.androidperformancestudio.memory.presentation.MemoryDeviceOption
import com.androidperformancestudio.memory.presentation.MemoryProcessOption
import com.androidperformancestudio.memory.presentation.MemorySampledArtOrigin
import com.androidperformancestudio.memory.presentation.MemorySampledArtTrace
import com.androidperformancestudio.memory.storage.MemorySessionMetadata
import com.androidperformancestudio.memory.storage.MemorySessionUiSettings
import com.androidperformancestudio.memory.storage.SqliteMemorySessionStore
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.SQLException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Suppress("LargeClass", "LongParameterList", "TooManyFunctions")
internal class DesktopMemoryProfilerBackend(
    private val dataRoot: Path = defaultMemoryDataRoot(),
    private val adbLocator: () -> Path? = ::defaultAdbExecutable,
    private val targetMonitorProvider: (Path) -> AndroidTargetMonitor = AndroidTargetMonitors::shared,
    private val captureSessionFactory: (Path) -> MemoryHeapDumpCaptureSession = ::MemoryHeapDumpCaptureSession,
    private val bitmapCaptureSessionFactory: (Path) -> BitmapHeapDumpCaptureSession = ::BitmapHeapDumpCaptureSession,
    @Volatile private var language: UiLanguage = UiLanguage.ENGLISH,
    private val nativeHeapArtifactAnalyzer: NativeHeapArtifactAnalyzer = PerfettoNativeHeapArtifactAnalyzer(),
    private val javaHeapArtifactAnalyzer: JavaHeapArtifactAnalyzer = PerfettoJavaHeapArtifactAnalyzer(),
    private val sampledArtArtifactAnalyzer: SampledArtArtifactAnalyzer = PerfettoSampledArtArtifactAnalyzer(),
    private val hprofClassMetricsAnalyzer: HprofClassMetricsAnalyzer = TraceProcessorHprofClassMetricsAnalyzer(),
    private val rustExecutable: () -> Path = RustHprofExecutableLocator::resolve,
    private val rustGraphStorage: String = System.getProperty("aps.hprof.rust.graphStorage", "memory"),
) : MemoryProfilerBackend {
    private val analyzer = MemoryDeepAnalyzer()
    private val exports = MemoryExportAdapters()
    private val bitmapParser = BitmapDumpParser()
    private val bitmapAnalyzer = BitmapDumpAnalyzer()
    private val bitmapExports = BitmapDumpExportAdapters()
    private val artifactFactory = MemoryCaptureArtifactFactory(dataRoot)
    private val artifactStore = MemoryArtifactStore(dataRoot.resolve("capture-artifacts"))

    private var mapping: ProguardMapping? = null
    private var mappingFile: Path? = null
    private var mappingDigest: String? = null
    private var lastLoadRequest: HeapLoadRequest? = null
    private var lastLoadedHeap: LoadedSnapshot? = null
    private val snapshotLoadMutex = Mutex()
    private var leakCanaryConnection: LeakCanaryAgentConnection? = null
    private var leakCanaryCursor: Long = 0L
    private var leakCanaryEvents: List<LeakCanaryLiveEvent> = emptyList()

    override fun changeLanguage(language: UiLanguage) {
        this.language = language
    }

    override fun registerTargetListener(listener: AndroidTargetListener): AndroidTargetSubscription {
        val monitor = adbLocator()?.let(targetMonitorProvider) ?: return AndroidTargetSubscription {}
        return monitor.register(listener)
    }

    override suspend fun listDevices(): MemoryBackendResult<List<MemoryDeviceOption>> {
        val adb = adbLocator() ?: return missingAdb()
        return when (val result = targetMonitorProvider(adb).refreshDevices()) {
            is StudioResult.Failure ->
                result.toBackendFailure(localizedStringResource(Res.string.unable_to_list_android_devices, language))
            is StudioResult.Success ->
                MemoryBackendResult.Success(
                    result.value.map { device ->
                        val apiLevel =
                            if (device.online) {
                                when (val properties = AdbDevicePropertiesReader(adb).read(device.serial)) {
                                    is StudioResult.Success -> properties.value.sdkInt
                                    is StudioResult.Failure -> null
                                }
                            } else {
                                null
                            }
                        MemoryDeviceOption(
                            serial = device.serial,
                            name = device.displayName,
                            online = device.online,
                            apiLevel = apiLevel,
                            supportsBitmapDump = apiLevel != null && apiLevel >= BitmapHeapDumpCaptureSession.MINIMUM_BITMAP_DUMP_API,
                        )
                    },
                )
        }
    }

    override suspend fun listProcesses(serial: String): MemoryBackendResult<List<MemoryProcessOption>> {
        val adb = adbLocator() ?: return missingAdb()
        return when (val result = targetMonitorProvider(adb).refreshTargets(serial)) {
            is StudioResult.Failure ->
                result.toBackendFailure(localizedStringResource(Res.string.unable_to_list_device_processes, language))
            is StudioResult.Success -> MemoryBackendResult.Success(result.value.heapDumpableProcesses())
        }
    }

    override suspend fun capture(
        serial: String,
        process: MemoryProcessOption,
    ): MemoryBackendResult<LoadedSnapshot> {
        val adb = adbLocator() ?: return missingAdb()
        val sessionId = sessionId()
        val captureSession = captureSessionFactory(adb)
        return snapshotIo {
            when (
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
                    val conversionWarning =
                        if (convertedFile == null) {
                            localizedStringResource(Res.string.hprof_conv_unavailable, language)
                        } else {
                            null
                        }
                    val effectiveWarning = listOfNotNull(warning, conversionWarning).joinToString("\n").ifBlank { null }
                    if (convertedFile == null) {
                        loadHeap(
                            HeapLoadRequest(
                                file = capture.rawHprofFile,
                                rawFile = capture.rawHprofFile,
                                warning = effectiveWarning,
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
                                warning = effectiveWarning,
                                cleanupWarning = cleanupWarning,
                                sessionMetadata = identity,
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun captureBitmaps(
        serial: String,
        process: MemoryProcessOption,
        onProgress: (Int) -> Unit,
    ): MemoryBackendResult<LoadedBitmapDump> =
        withContext(Dispatchers.IO) {
            val adb = adbLocator() ?: return@withContext missingAdb()
            val sessionId = sessionId()
            val captureSession = bitmapCaptureSessionFactory(adb)
            when (
                val result =
                    captureSession.capture(
                        BitmapCaptureRequest(
                            sessionId = sessionId,
                            sessionRoot = dataRoot.resolve("sessions"),
                            serial = serial,
                            pid = process.pid,
                            packageName = process.packageName,
                        ),
                        onProgress = { progress ->
                            onProgress(progress.percent * CAPTURE_PROGRESS_WEIGHT / PERCENT_COMPLETE)
                        },
                    )
            ) {
                is StudioResult.Failure ->
                    result.toBackendFailure(localizedStringResource(Res.string.bitmap_dump_failed, language))
                is StudioResult.Success -> {
                    try {
                        val capture = result.value
                        val imagesDirectory = capture.sessionDirectory.resolve("images")
                        val parsed =
                            bitmapParser.parse(capture.hprofFile, imagesDirectory) { parserProgress ->
                                onProgress(
                                    CAPTURE_PROGRESS_WEIGHT +
                                        parserProgress * PARSER_PROGRESS_WEIGHT / PERCENT_COMPLETE,
                                )
                            }
                        val capturedAt = Instant.now()
                        val session =
                            bitmapAnalyzer.analyze(
                                BitmapDumpAnalysisRequest(
                                    id = sessionId,
                                    packageName = process.packageName,
                                    pid = process.pid,
                                    deviceSerial = serial,
                                    sdkLevel = capture.sdkLevel,
                                    capturedAt = capturedAt,
                                    hprofFile = capture.hprofFile,
                                    imagesDirectory = imagesDirectory,
                                    parsed = parsed,
                                    memorySnapshot = capture.memorySnapshot,
                                ),
                            )
                        bitmapExports.writeSessionArtifacts(session)
                        onProgress(PERCENT_COMPLETE)
                        MemoryBackendResult.Success(
                            LoadedBitmapDump(
                                session = session,
                                warning =
                                    capture.warnings
                                        .filterNot { it.code == "DEVICE_CLEANUP_FAILED" }
                                        .joinToString("\n") { it.message }
                                        .ifBlank { null },
                                cleanupWarning =
                                    capture.warnings
                                        .firstOrNull { it.code == "DEVICE_CLEANUP_FAILED" }
                                        ?.message,
                            ),
                        )
                    } catch (exception: BitmapDumpParseException) {
                        bitmapFailure(exception)
                    } catch (exception: IOException) {
                        bitmapFailure(exception)
                    } catch (exception: IllegalArgumentException) {
                        bitmapFailure(exception)
                    }
                }
            }
        }

    @Suppress("ReturnCount")
    override suspend fun captureNativeHeap(
        serial: String,
        process: MemoryProcessOption,
    ): MemoryBackendResult<LoadedNativeHeap> {
        val adb = adbLocator() ?: return missingAdb()
        val sessionId = sessionId()
        val session = NativeHeapCaptureSession(adb)
        return when (
            val result =
                session.capture(
                    NativeHeapCaptureRequest(
                        sessionId = sessionId,
                        sessionRoot = dataRoot.resolve("sessions"),
                        serial = serial,
                        pid = process.pid,
                        packageName = process.packageName,
                    ),
                )
        ) {
            is StudioResult.Failure ->
                result.toBackendFailure(localizedStringResource(Res.string.unable_to_capture_native_heap, language))
            is StudioResult.Success -> {
                val capture = result.value
                val artifact =
                    artifactFactory.nativeCapture(
                        id = "native-$sessionId",
                        file = capture.traceFile,
                        rawSerial = serial,
                        processId = process.pid,
                        packageName = process.packageName,
                    )
                val processed = nativeHeapArtifactAnalyzer.analyze(capture.traceFile, artifact)
                val resolved = resolveNativeProcessing(processed, artifact, capture.traceFile)
                resolved.failureReason?.let { reason ->
                    return MemoryBackendResult.Failure(
                        localizedStringResource(Res.string.unable_to_capture_native_heap, language),
                        reason,
                    )
                }
                MemoryBackendResult.Success(
                    LoadedNativeHeap(
                        trace =
                            NativeHeapTrace(
                                traceFile = capture.traceFile.toString(),
                                fileName = capture.traceFile.fileName.toString(),
                                fileSizeBytes = Files.size(capture.traceFile),
                                deviceSdkApiLevel = capture.deviceSdkApiLevel,
                                artifact = resolved.artifact,
                                evidenceSource = resolved.source,
                                fallbackReason = resolved.fallbackReason,
                            ),
                        analysis = resolved.analysis,
                    ),
                )
            }
        }
    }

    override suspend fun captureSampledArtAllocations(
        serial: String,
        process: MemoryProcessOption,
    ): MemoryBackendResult<LoadedSampledArt> =
        withContext(Dispatchers.IO) {
            val adb = adbLocator() ?: return@withContext missingAdb()
            when (
                val result =
                    SampledArtAllocationCaptureSession(adb).capture(
                        SampledArtAllocationCaptureRequest(
                            sessionId = sessionId(),
                            sessionRoot = dataRoot.resolve("sessions"),
                            serial = serial,
                            pid = process.pid,
                            packageName = process.packageName,
                        ),
                    )
            ) {
                is StudioResult.Failure -> result.toBackendFailure("ART allocation sampling failed")
                is StudioResult.Success ->
                    MemoryBackendResult.Success(
                        LoadedSampledArt(
                            trace =
                                MemorySampledArtTrace(
                                    file = result.value.traceFile.toString(),
                                    fileSizeBytes = Files.size(result.value.traceFile),
                                    packageName = result.value.packageName,
                                    pid = result.value.pid,
                                    sdkLevel = result.value.sdkLevel,
                                    samplingIntervalBytes = result.value.samplingIntervalBytes,
                                    durationMs = result.value.durationMs,
                                    source = result.value.source,
                                    origin = MemorySampledArtOrigin.CAPTURE,
                                ),
                            processing = sampledArtArtifactAnalyzer.analyze(result.value.traceFile),
                        ),
                    )
            }
        }

    override suspend fun importSampledArtAllocations(file: Path): MemoryBackendResult<LoadedSampledArt> =
        withContext(Dispatchers.IO) {
            if (!Files.isRegularFile(file) || !Files.isReadable(file) || Files.size(file) == 0L) {
                return@withContext MemoryBackendResult.Failure(
                    title = "ART sample trace unavailable",
                    detail = "Select a readable, nonempty Perfetto trace: $file",
                )
            }
            MemoryBackendResult.Success(
                LoadedSampledArt(
                    trace =
                        MemorySampledArtTrace(
                            file = file.toAbsolutePath().normalize().toString(),
                            fileSizeBytes = Files.size(file),
                            source = "local Perfetto import; ART samples queried via Trace Processor",
                            origin = MemorySampledArtOrigin.IMPORT,
                        ),
                    processing = sampledArtArtifactAnalyzer.analyze(file),
                ),
            )
        }

    override fun exportSampledArtAllocations(
        trace: MemorySampledArtTrace,
        output: Path,
    ) {
        Files.copy(Path.of(trace.file), output, StandardCopyOption.REPLACE_EXISTING)
    }

    override suspend fun importNativeHeap(file: Path): MemoryBackendResult<LoadedNativeHeap> =
        withContext(Dispatchers.IO) {
            if (!Files.isRegularFile(file)) {
                MemoryBackendResult.Failure(
                    localizedStringResource(Res.string.native_heap_trace_file_not_found, language),
                    localizedStringResource(Res.string.hprof_file_not_readable, language, file.fileName),
                )
            } else {
                val artifact = artifactFactory.nativeImport("native-${java.util.UUID.randomUUID()}", file)
                val processed = nativeHeapArtifactAnalyzer.analyze(file, artifact)
                val resolved = resolveNativeProcessing(processed, artifact, file)
                resolved.failureReason?.let { reason ->
                    return@withContext MemoryBackendResult.Failure(
                        localizedStringResource(Res.string.unable_to_capture_native_heap, language),
                        reason,
                    )
                }
                MemoryBackendResult.Success(
                    LoadedNativeHeap(
                        trace =
                            NativeHeapTrace(
                                traceFile = file.toString(),
                                fileName = file.fileName.toString(),
                                fileSizeBytes = Files.size(file),
                                artifact = resolved.artifact,
                                evidenceSource = resolved.source,
                                fallbackReason = resolved.fallbackReason,
                            ),
                        analysis = resolved.analysis,
                    ),
                )
            }
        }

    override suspend fun importJavaHeap(file: Path): MemoryBackendResult<LoadedSnapshot> =
        withContext(Dispatchers.IO) {
            if (!Files.isRegularFile(file)) {
                MemoryBackendResult.Failure(
                    localizedStringResource(Res.string.java_heap_file_not_found, language),
                    localizedStringResource(Res.string.hprof_file_not_readable, language, file.fileName),
                )
            } else {
                val artifact = artifactFactory.javaImport("java-${java.util.UUID.randomUUID()}", file)
                when (val processed = javaHeapArtifactAnalyzer.analyze(file, artifact)) {
                    is JavaHeapProcessingResult.Success -> {
                        val finalArtifact = artifactFactory.javaProcessorResult(artifact, processed)
                        artifactStore.write(finalArtifact)
                        val heapDump = processed.heapDump.copy(artifact = finalArtifact)
                        if (heapDump.instances.isEmpty() && heapDump.objectArrays.isEmpty() && heapDump.primitiveArrays.isEmpty()) {
                            MemoryBackendResult.Failure(
                                localizedStringResource(Res.string.unable_to_import_java_heap, language),
                                "No Java objects were found in the heap graph.",
                            )
                        } else {
                            MemoryBackendResult.Success(
                                analyzeAndPackage(
                                    heapDump = heapDump,
                                    id = importedSessionId(file),
                                    packageName = "",
                                    pid = 0,
                                    capturedAt = Instant.now(),
                                    emptyWarningFileName = file.fileName.toString(),
                                    extraWarning = heapDump.warnings.joinToString("\n", transform = { it.message }),
                                ),
                            )
                        }
                    }
                    is JavaHeapProcessingResult.Unavailable ->
                        when (val result = JavaHeapTraceParser.parse(file)) {
                            is JavaHeapParseResult.Failure ->
                                MemoryBackendResult.Failure(
                                    localizedStringResource(Res.string.unable_to_import_java_heap, language),
                                    result.message,
                                )
                            is JavaHeapParseResult.Success -> {
                                val finalArtifact = artifactFactory.javaWireFallback(artifact, processed.reason)
                                artifactStore.write(finalArtifact)
                                val heapDump = HeapGraphToHeapDump.toHeapDump(result.heapGraph).copy(artifact = finalArtifact)
                                if (heapDump.instances.isEmpty() && heapDump.objectArrays.isEmpty() && heapDump.primitiveArrays.isEmpty()) {
                                    MemoryBackendResult.Failure(
                                        localizedStringResource(Res.string.unable_to_import_java_heap, language),
                                        "No Java objects were found in the heap graph.",
                                    )
                                } else {
                                    MemoryBackendResult.Success(
                                        analyzeAndPackage(
                                            heapDump = heapDump,
                                            id = importedSessionId(file),
                                            packageName = "",
                                            pid = result.heapGraph.pid,
                                            capturedAt = Instant.now(),
                                            emptyWarningFileName = file.fileName.toString(),
                                            extraWarning = heapDump.warnings.joinToString("\n", transform = { it.message }),
                                        ),
                                    )
                                }
                            }
                        }
                    is JavaHeapProcessingResult.Failure ->
                        MemoryBackendResult.Failure(
                            localizedStringResource(Res.string.unable_to_import_java_heap, language),
                            processed.reason,
                        )
                }
            }
        }

    override fun exportNativeHeap(
        trace: NativeHeapTrace,
        output: Path,
    ) {
        Files.copy(Path.of(trace.traceFile), output, StandardCopyOption.REPLACE_EXISTING)
    }

    @Suppress("ktlint:standard:function-expression-body")
    override suspend fun importHprof(file: Path): MemoryBackendResult<LoadedSnapshot> = importHprof(file) {}

    override suspend fun importHprof(
        file: Path,
        onProgress: (Int) -> Unit,
    ): MemoryBackendResult<LoadedSnapshot> =
        snapshotIo {
            if (!Files.isRegularFile(file)) {
                MemoryBackendResult.Failure(
                    localizedStringResource(Res.string.hprof_file_not_found, language),
                    localizedStringResource(Res.string.hprof_file_not_readable, language, file.fileName),
                )
            } else {
                loadHeap(
                    HeapLoadRequest(
                        file = file,
                        rawFile = file,
                        sessionMetadata = importedSessionIdentity(file),
                    ),
                    onProgress,
                )
            }
        }

    override suspend fun openLeakCanaryAgent(
        serial: String,
        process: MemoryProcessOption,
    ): MemoryBackendResult<LeakCanaryLiveSession> =
        withContext(Dispatchers.IO) {
            closeLeakCanaryAgent()
            val adb = adbLocator() ?: return@withContext missingAdb()
            val connection = LeakCanaryAgentConnection(DefaultAdbClient(adb), serial, process.packageName)
            runCatching {
                connection.open()
                val response = connection.startSession()
                leakCanaryConnection = connection
                leakCanaryCursor = response.latestSequence
                leakCanaryEvents = emptyList()
                MemoryBackendResult.Success(
                    response.toLiveSession(LeakCanaryLiveStatus.RUNNING),
                )
            }.getOrElse { error ->
                connection.close()
                MemoryBackendResult.Failure(
                    "LeakCanary Agent unavailable",
                    error.message ?: error::class.simpleName.orEmpty(),
                )
            }
        }

    override suspend fun pollLeakCanaryAgent(): MemoryBackendResult<LeakCanaryLiveSession> =
        withContext(Dispatchers.IO) {
            val connection =
                leakCanaryConnection
                    ?: return@withContext MemoryBackendResult.Failure(
                        "LeakCanary Agent unavailable",
                        "The LeakCanary Agent connection is not open.",
                    )
            runCatching {
                val response = connection.poll(leakCanaryCursor)
                leakCanaryCursor = response.latestSequence
                MemoryBackendResult.Success(response.toLiveSession(LeakCanaryLiveStatus.RUNNING))
            }.getOrElse { error ->
                MemoryBackendResult.Failure(
                    "LeakCanary Agent polling failed",
                    error.message ?: error::class.simpleName.orEmpty(),
                )
            }
        }

    override fun closeLeakCanaryAgent() {
        leakCanaryConnection?.close()
        leakCanaryConnection = null
        leakCanaryCursor = 0L
        leakCanaryEvents = emptyList()
    }

    override suspend fun injectLeakCanaryAgent(projectRoot: Path): MemoryBackendResult<String> =
        withContext(Dispatchers.IO) {
            val bundle =
                findLeakCanaryAgentBundle()
                    ?: return@withContext MemoryBackendResult.Failure(
                        "LeakCanary Agent bundle unavailable",
                        "Build :android-agent-leakcanary:bundleLeakCanaryAgent before injection.",
                    )
            runCatching {
                val result = AndroidProjectLeakCanaryInjector().inject(projectRoot, bundle.first, bundle.second)
                MemoryBackendResult.Success(result.message)
            }.getOrElse { error ->
                MemoryBackendResult.Failure(
                    "LeakCanary Agent injection failed",
                    error.message ?: error::class.simpleName.orEmpty(),
                )
            }
        }

    override suspend fun analyzeLeaks(): MemoryBackendResult<LeakCanaryReport> {
        val loaded =
            lastLoadedHeap
                ?: return MemoryBackendResult.Failure(
                    "Leak analysis unavailable",
                    "Load an HPROF snapshot before starting LeakCanary analysis.",
                )
        return analyzeLeaks(loaded) {}
    }

    override suspend fun analyzeLeaks(
        snapshot: LoadedSnapshot,
        onProgress: (Int) -> Unit,
    ): MemoryBackendResult<LeakCanaryReport> =
        withContext(Dispatchers.IO) {
            val hprofFile = snapshot.convertedHprofFile ?: snapshot.rawHprofFile
            if (hprofFile == null || !Files.isRegularFile(hprofFile)) {
                return@withContext MemoryBackendResult.Failure(
                    "Leak analysis unavailable",
                    "The loaded snapshot does not have a readable HPROF source.",
                )
            }
            val context = currentCoroutineContext()
            try {
                fun sourceUnchanged(): Boolean =
                    snapshot.analysisSourceDigest == null ||
                        digestFile(hprofFile) { context.ensureActive() } == snapshot.analysisSourceDigest

                if (!sourceUnchanged()) return@withContext changedHeapSourceFailure()
                val report =
                    SharkLeakAnalyzer { progress ->
                        context.ensureActive()
                        onProgress(progress)
                    }.analyze(
                        hprofFile = hprofFile,
                        initialClassCount = snapshot.source.metadata.classCount,
                        initialObjectCount = snapshot.source.metadata.instanceCount,
                    )
                context.ensureActive()
                if (!sourceUnchanged()) return@withContext changedHeapSourceFailure()
                MemoryBackendResult.Success(report)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                MemoryBackendResult.Failure(
                    "Leak analysis failed",
                    exception.message ?: exception::class.simpleName.orEmpty(),
                )
            }
        }

    private fun changedHeapSourceFailure(): MemoryBackendResult.Failure =
        MemoryBackendResult.Failure(
            "Snapshot source changed",
            "The HPROF source no longer matches the loaded object graph. Re-import the snapshot before analysis.",
        )

    override suspend fun importMapping(file: Path): MemoryBackendResult<LoadedSnapshot?> =
        snapshotIo {
            val parsedMapping =
                try {
                    ProguardMappingParser.parse(file)
                } catch (exception: Exception) {
                    return@snapshotIo MemoryBackendResult.Failure(
                        localizedStringResource(Res.string.unable_to_load_mapping, language),
                        exception.message ?: exception::class.simpleName.orEmpty(),
                    )
                }
            mapping = parsedMapping
            mappingFile = file
            mappingDigest = digestFile(file)
            val last = lastLoadRequest
            if (last == null) {
                MemoryBackendResult.Success<LoadedSnapshot?>(null)
            } else {
                when (val result = loadHeap(last)) {
                    is MemoryBackendResult.Success -> MemoryBackendResult.Success(result.value)
                    is MemoryBackendResult.Failure -> result
                }
            }
        }

    override suspend fun listSessions(): MemoryBackendResult<List<MemorySessionMetadata>> =
        withContext(Dispatchers.IO) {
            try {
                SqliteMemorySessionStore.open(dataRoot.resolve("memory-sessions.db")).use { store ->
                    MemoryBackendResult.Success(store.listRecent())
                }
            } catch (exception: SQLException) {
                MemoryBackendResult.Failure(
                    title = localizedStringResource(Res.string.unable_to_analyze_hprof, language),
                    detail = exception.message ?: exception::class.simpleName.orEmpty(),
                )
            }
        }

    override fun loadWorkspaceSettings(sessionId: String): MemorySessionUiSettings =
        SqliteMemorySessionStore.open(dataRoot.resolve("memory-sessions.db")).use { store ->
            store.loadUiSettings(sessionId)
        }

    override fun saveWorkspaceSettings(
        sessionId: String,
        settings: MemorySessionUiSettings,
    ) {
        SqliteMemorySessionStore.open(dataRoot.resolve("memory-sessions.db")).use { store ->
            store.saveUiSettings(sessionId, settings)
        }
    }

    override suspend fun loadSession(metadata: MemorySessionMetadata): MemoryBackendResult<LoadedSnapshot> =
        snapshotIo {
            mappingFile = metadata.mappingFile?.takeIf(Files::isRegularFile)
            mapping =
                mappingFile?.let { path ->
                    runCatching { ProguardMappingParser.parse(path) }.getOrNull()
                }
            mappingDigest = metadata.mappingDigest
            val file = metadata.convertedHprofFile ?: metadata.rawHprofFile
            if (!Files.isRegularFile(file)) {
                return@snapshotIo MemoryBackendResult.Failure(
                    localizedStringResource(Res.string.hprof_file_not_found, language),
                    localizedStringResource(Res.string.hprof_file_not_readable, language, file.fileName),
                )
            }
            loadHeap(
                HeapLoadRequest(
                    file = file,
                    rawFile = metadata.rawHprofFile,
                    convertedFile = metadata.convertedHprofFile,
                    sessionMetadata =
                        CapturedSessionIdentity(
                            serial = metadata.deviceSerial,
                            packageName = metadata.packageName,
                            sessionId = metadata.sessionId,
                            pid = 0,
                        ),
                ),
            )
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
        histogram: HeapHistogram,
        output: Path,
    ) {
        exports.exportClassHistogramCsv(histogram, output)
    }

    override fun exportClassInstances(
        heapDump: HeapDump,
        className: String,
        output: Path,
    ) {
        exports.exportClassInstancesCsv(heapDump, className, output)
    }

    override fun exportHeapDiff(
        diff: com.androidperformancestudio.memory.model.HeapDiff,
        output: Path,
    ) {
        exports.exportHeapDiffCsv(diff, output)
    }

    override fun exportHeapSnapshotJson(
        heapDump: HeapDump,
        histogram: HeapHistogram,
        output: Path,
        snapshotSummary: com.androidperformancestudio.memory.model.HeapSnapshotSummary?,
        exportContext: com.androidperformancestudio.memory.model.HeapExportContext?,
    ) {
        exports.exportHeapSnapshotJson(heapDump, histogram, output, snapshotSummary, exportContext)
    }

    override fun exportInvestigationReport(
        heapDump: HeapDump,
        histogram: HeapHistogram,
        diff: com.androidperformancestudio.memory.model.HeapDiff?,
        output: Path,
        snapshotSummary: com.androidperformancestudio.memory.model.HeapSnapshotSummary?,
        exportContext: com.androidperformancestudio.memory.model.HeapExportContext?,
    ) {
        exports.exportInvestigationReportMarkdown(heapDump, histogram, diff, output, snapshotSummary, exportContext)
    }

    override fun exportObjectInvestigation(
        investigation: com.androidperformancestudio.memory.model.HeapObjectInvestigation,
        output: Path,
    ) {
        exports.exportObjectInvestigationJson(investigation, output)
    }

    override fun exportBitmapSession(
        session: BitmapDumpSession,
        output: Path,
    ) {
        bitmapExports.exportSessionZip(session, output)
    }

    override fun exportBitmapComparison(
        comparison: BitmapDumpComparison,
        output: Path,
    ) {
        bitmapExports.exportComparisonMarkdown(comparison, output)
    }

    private suspend fun loadHeap(
        request: HeapLoadRequest,
        onProgress: (Int) -> Unit = {},
    ): MemoryBackendResult<LoadedSnapshot> {
        lastLoadRequest = request
        return loadRustHeap(request, onProgress)
    }

    /** Keep ownership outside the dispatcher boundary so cancelled delivery cannot lose a sidecar. */
    private suspend fun <T : LoadedSnapshot?> snapshotIo(load: suspend () -> MemoryBackendResult<T>): MemoryBackendResult<T> =
        snapshotLoadMutex.withLock {
            val previousHeap = lastLoadedHeap
            val previousMapping = mapping
            val previousMappingFile = mappingFile
            val previousMappingDigest = mappingDigest
            var acquired: LoadedSnapshot? = null
            var delivered = false
            var cancellation: CancellationException? = null
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        load().also { loaded ->
                            if (loaded is MemoryBackendResult.Success) acquired = loaded.value
                        }
                    }
                if (result is MemoryBackendResult.Success) delivered = true
                result
            } catch (exception: CancellationException) {
                cancellation = exception
                throw exception
            } finally {
                if (!delivered) {
                    try {
                        val abandoned = acquired?.takeIf { it !== previousHeap }
                        closeHeapResources(listOfNotNull(abandoned), cancellation)
                    } finally {
                        lastLoadedHeap = previousHeap
                        mapping = previousMapping
                        mappingFile = previousMappingFile
                        mappingDigest = previousMappingDigest
                    }
                }
            }
        }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun loadRustHeap(
        request: HeapLoadRequest,
        onProgress: (Int) -> Unit,
    ): MemoryBackendResult<LoadedSnapshot> {
        val context = currentCoroutineContext()
        var opened: RustHeapSnapshotSource? = null
        val leases = mutableListOf<ManagedHprofSource.Lease>()
        var transferred = false
        var cancellation: CancellationException? = null
        return try {
            val executable = rustExecutable()
            require(Files.isRegularFile(executable) && Files.isExecutable(executable)) {
                "Rust HPROF executable is unavailable: $executable"
            }
            onProgress(1)
            val managed = ManagedHprofSource(dataRoot.resolve("hprof-sources"))

            fun acquire(path: Path): ManagedHprofSource.Lease = managed.acquire(path) { context.ensureActive() }.also { leases += it }
            val analysisLease = acquire(request.file)
            val analysisFile = analysisLease.path
            val analysisDigest = analysisLease.digest
            val rawLease = if (request.rawFile == request.file) analysisLease else acquire(request.rawFile)
            val rawFile = rawLease.path
            val rawDigest = rawLease.digest
            val convertedFile =
                request.convertedFile?.let { original ->
                    if (original == request.file) analysisFile else acquire(original).path
                }
            val currentMapping = validatedMapping()
            onProgress(10)
            val source =
                runInterruptible(Dispatchers.IO) {
                    RustHeapSnapshotSource
                        .open(
                            executable,
                            analysisFile,
                            analysisDigest,
                            currentMapping,
                            dataRoot.resolve("hprof-index-rust"),
                            graphStorage = rustGraphStorage,
                            graphDirectory = if (rustGraphStorage == "disk") dataRoot.resolve("hprof-graph-rust") else null,
                        ).also { opened = it }
                }
            context.ensureActive()
            onProgress(65)
            val histogram = runInterruptible(Dispatchers.IO) { source.histogram() }
            val perHeap =
                source.metadata.heaps.associateWith { heapName ->
                    context.ensureActive()
                    runInterruptible(Dispatchers.IO) { source.histogram(heapName).classes }
                }
            val findings = runInterruptible(Dispatchers.IO) { source.findings() }
            val capturedAt = Instant.now()
            var loaded =
                RustLoadedSnapshot(
                    id = request.sessionMetadata?.sessionId ?: request.file.fileName.toString(),
                    rawHprofFile = rawFile,
                    convertedHprofFile = convertedFile,
                    capturedAt = capturedAt,
                    source = source,
                    findings = findings,
                    histogram = histogram,
                    warning =
                        listOfNotNull(
                            request.warning,
                            invalidMappingWarning(currentMapping),
                            source.metadata.warnings
                                .joinToString("\n") { it.message }
                                .ifBlank { null },
                        ).joinToString("\n").ifBlank { null },
                    cleanupWarning = request.cleanupWarning,
                    mapping = currentMapping,
                    availableHeaps = source.metadata.heaps,
                    perHeapClasses = perHeap,
                    uiSettings = request.sessionMetadata?.sessionId?.let(::loadWorkspaceSettings) ?: MemorySessionUiSettings(),
                    sourceFileDigest = rawDigest,
                    analysisSourceDigest = analysisDigest,
                    mappingDigest = mappingDigest,
                    sourceLeases = leases.toList(),
                )
            onProgress(80)
            val metrics = hprofClassMetricsAnalyzer.analyze(analysisFile)
            context.ensureActive()
            if (metrics != null) loaded = enrichRustSnapshotMetrics(loaded, source, metrics, currentMapping)
            require(ManagedHprofSource.sha256(analysisFile) { context.ensureActive() } == analysisDigest) {
                "The HPROF source changed during Rust analysis"
            }
            request.sessionMetadata?.let { metadata ->
                persistSession(
                    metadata,
                    capturedAt,
                    request.rawFile,
                    request.convertedFile,
                    loaded.histogram.summary,
                    mappingFile,
                    mappingDigest,
                )
            }
            onProgress(100)
            context.ensureActive()
            lastLoadedHeap = loaded
            opened = null
            transferred = true
            MemoryBackendResult.Success(loaded)
        } catch (exception: CancellationException) {
            cancellation = exception
            throw exception
        } catch (exception: Exception) {
            MemoryBackendResult.Failure(
                "Rust HPROF analysis failed",
                exception.message ?: exception::class.simpleName.orEmpty(),
            )
        } finally {
            val resources = listOfNotNull<AutoCloseable>(opened) + if (!transferred) leases else emptyList()
            closeHeapResources(resources, cancellation)
        }
    }

    private fun validatedMapping(): ProguardMapping? {
        val current = mapping
        return if (current == null) {
            null
        } else {
            val file = mappingFile
            val expectedDigest = mappingDigest
            if (file == null || expectedDigest == null) {
                current
            } else if (Files.isRegularFile(file)) {
                current.takeIf { digestFile(file) == expectedDigest }
            } else {
                null
            }
        }
    }

    private fun invalidMappingWarning(mappingForLoad: ProguardMapping?): String? =
        if (mapping != null && mappingForLoad == null) {
            "mapping.txt digest changed or the file is unavailable; mapping was ignored until re-imported"
        } else {
            null
        }

    private fun digestFile(
        file: Path,
        checkpoint: () -> Unit = {},
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_DIGEST_BUFFER_BYTES)
            while (true) {
                checkpoint()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /** Runs deep analysis for non-HPROF graph imports and adapts the result to snapshot APIs. */
    @Suppress("LongParameterList")
    private fun analyzeAndPackage(
        heapDump: HeapDump,
        id: String,
        packageName: String,
        pid: Int,
        capturedAt: Instant,
        emptyWarningFileName: String? = null,
        extraWarning: String? = null,
        cleanupWarning: String? = null,
        onAnalysisProgress: (Int) -> Unit = {},
        deobfuscator: ProguardMapping? = mapping,
        analysisSourceDigest: String? = null,
    ): HeapDumpLoadedSnapshot {
        val deepAnalysis = analyzer.analyze(heapDump, deobfuscator = deobfuscator, onProgress = onAnalysisProgress)
        val histogram = deepAnalysis.histogram
        val emptyHeapWarning =
            if (emptyWarningFileName != null && histogram.summary.objectCount == 0) {
                localizedStringResource(Res.string.no_heap_objects_parsed, language, emptyWarningFileName)
            } else {
                null
            }
        val noMappingWarning =
            if (deobfuscator == null && histogram.classes.any { isLikelyObfuscatedClassName(it.className) }) {
                localizedStringResource(Res.string.mapping_not_loaded_hint, language)
            } else {
                null
            }
        val histogramAnalyzer = MemoryHistogramAnalyzer()
        val availableHeaps = histogramAnalyzer.heapNamesOf(heapDump)
        val perHeapClasses =
            availableHeaps.associateWith { heapName ->
                histogramAnalyzer
                    .histogram(
                        heapDump,
                        heapName = heapName,
                        retainedSizes = deepAnalysis.dominatorTree.retainedSizes,
                        immediateDominators = deepAnalysis.dominatorTree.immediateDominators,
                        deobfuscator = deobfuscator,
                    ).classes
            }
        val analysisHeap =
            heapDump.copy(
                id = id,
                packageName = packageName,
                pid = pid,
                capturedAt = capturedAt,
                heapSummary = histogram.summary,
                topClasses = histogram.classes,
                leakSuspects = deepAnalysis.leakSuspects,
                objectRetainedSizes = deepAnalysis.dominatorTree.retainedSizes,
                objectImmediateDominators = deepAnalysis.dominatorTree.immediateDominators,
                bitmapInstances = deepAnalysis.bitmapInstances,
                activityLeaks = deepAnalysis.activityLeaks,
                activityFragmentLeakObjectIds = deepAnalysis.activityFragmentLeakObjectIds,
            )
        val parsed =
            analysisHeap.copy(
                leakCanaryReport =
                    LeakCanaryReport(
                        status = LeakCanaryStatus.NOT_RUN,
                        message = "LeakCanary analysis has not been run for this snapshot.",
                    ),
            )
        val source =
            HeapDumpSnapshotSource(
                heap = parsed,
                loadedHistogram = histogram,
                loadedHeaps = availableHeaps,
                loadedPerHeapClasses = perHeapClasses,
            )
        val loaded =
            HeapDumpLoadedSnapshot(
                heapDump = parsed,
                histogram = histogram,
                source = source,
                mapping = deobfuscator,
                availableHeaps = availableHeaps,
                perHeapClasses = perHeapClasses,
                warning =
                    listOfNotNull(extraWarning, emptyHeapWarning, noMappingWarning)
                        .joinToString("\n")
                        .ifBlank { null },
                cleanupWarning = cleanupWarning,
                analysisSourceDigest =
                    analysisSourceDigest ?: (heapDump.convertedHprofFile ?: heapDump.rawHprofFile)?.let { digestFile(it) },
            )
        lastLoadedHeap = loaded
        return loaded
    }

    private fun persistSession(
        metadata: CapturedSessionIdentity,
        capturedAt: Instant,
        rawFile: Path,
        convertedFile: Path?,
        summary: HeapSummary,
        mappingFile: Path?,
        mappingDigest: String?,
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
                    mappingFile = mappingFile,
                    mappingDigest = mappingDigest,
                    classCount = summary.classCount,
                    objectCount = summary.objectCount,
                    shallowSizeBytes = summary.shallowSize,
                ),
            )
        }
    }

    private fun LeakCanaryAgentResponse.toLiveSession(status: LeakCanaryLiveStatus): LeakCanaryLiveSession {
        val newEvents =
            events.map { event ->
                LeakCanaryLiveEvent(
                    sequence = event.sequence,
                    kind = event.kind,
                    monotonicNs = event.monotonicNs,
                    className = event.className,
                    description = event.description,
                    watchId = event.watchId,
                    retained = event.retained,
                    source = event.source,
                )
            }
        leakCanaryEvents = (leakCanaryEvents + newEvents).takeLast(MAX_LIVE_EVENTS)
        return LeakCanaryLiveSession(
            status = status,
            packageName = packageName,
            processName = events.lastOrNull()?.processName,
            sessionId = sessionId,
            cursor = latestSequence,
            droppedEvents = droppedEvents,
            leakCanaryAvailable = leakCanaryAvailable,
            events = leakCanaryEvents,
            message = message,
        )
    }

    private fun findLeakCanaryAgentBundle(): Pair<Path, Path>? {
        val names =
            listOf(
                "android-agent-leakcanary-debug.aar" to "leakcanary-agent-protocol-0.1.0-SNAPSHOT.jar",
            )
        val roots =
            generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()) { it.parent }
                .flatMap { root ->
                    sequenceOf(
                        root.resolve("memory-profiler/build/leakcanary-agent-bundle"),
                        root.resolve("desktop-viewer/memory-profiler/build/leakcanary-agent-bundle"),
                    )
                }.toList()
        return roots
            .asSequence()
            .flatMap { directory ->
                names.asSequence().map { (aarName, jarName) -> directory.resolve(aarName) to directory.resolve(jarName) }
            }.firstOrNull { (aar, jar) -> Files.isRegularFile(aar) && Files.isRegularFile(jar) }
    }

    private fun analysisFailure(exception: Exception): MemoryBackendResult.Failure =
        MemoryBackendResult.Failure(
            title = localizedStringResource(Res.string.unable_to_analyze_hprof, language),
            detail = exception.message ?: exception::class.simpleName.orEmpty(),
        )

    private fun bitmapFailure(exception: Exception): MemoryBackendResult.Failure =
        MemoryBackendResult.Failure(
            title = localizedStringResource(Res.string.bitmap_dump_failed, language),
            detail = exception.message ?: exception::class.simpleName.orEmpty(),
        )

    private fun resolveNativeProcessing(
        processed: NativeHeapProcessingResult,
        artifact: com.androidperformancestudio.contracts.CaptureArtifact,
        file: Path,
    ): NativeHeapResolution =
        when (processed) {
            is NativeHeapProcessingResult.Success -> {
                val finalArtifact = artifactFactory.processorResult(artifact, processed)
                artifactStore.write(finalArtifact)
                NativeHeapResolution(
                    analysis = processed.analysis,
                    artifact = finalArtifact,
                    source = NativeHeapEvidenceSource.TRACE_PROCESSOR,
                )
            }
            is NativeHeapProcessingResult.Unavailable ->
                try {
                    val fallback = NativeHeapTraceParser.parseStrict(file)
                    val finalArtifact = artifactFactory.wireFallback(artifact, processed.reason)
                    artifactStore.write(finalArtifact)
                    NativeHeapResolution(
                        analysis = fallback,
                        artifact = finalArtifact,
                        source = NativeHeapEvidenceSource.WIRE_FALLBACK,
                        fallbackReason = processed.reason,
                    )
                } catch (error: Exception) {
                    NativeHeapResolution(failureReason = "Native trace is corrupt; wire fallback was not used: ${error.message}")
                }
            is NativeHeapProcessingResult.Failure -> NativeHeapResolution(failureReason = processed.reason)
        }

    private data class NativeHeapResolution(
        val analysis: com.androidperformancestudio.memory.model.NativeHeapAnalysis =
            com.androidperformancestudio.memory.model
                .NativeHeapAnalysis(),
        val artifact: com.androidperformancestudio.contracts.CaptureArtifact? = null,
        val source: NativeHeapEvidenceSource = NativeHeapEvidenceSource.TRACE_PROCESSOR,
        val fallbackReason: String? = null,
        val failureReason: String? = null,
    )

    private fun StudioResult.Failure.toBackendFailure(title: String): MemoryBackendResult.Failure =
        MemoryBackendResult.Failure(title = title, detail = error.message)

    private fun missingAdb(): MemoryBackendResult.Failure =
        MemoryBackendResult.Failure(
            title = localizedStringResource(Res.string.android_sdk_platform_tools_not_found, language),
            detail = localizedStringResource(Res.string.install_sdk_platform_tools, language),
        )

    private fun sessionId(): String = SESSION_ID_FORMAT.format(Instant.now())

    private fun importedSessionIdentity(file: Path): CapturedSessionIdentity {
        val stableId = importedSessionId(file)
        return CapturedSessionIdentity(
            serial = "",
            packageName = "",
            sessionId = stableId,
            pid = 0,
        )
    }

    private fun importedSessionId(file: Path): String {
        val normalized = file.toAbsolutePath().normalize().toString()
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(normalized.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
        return "import-${hex.take(12)}"
    }

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
        private const val CAPTURE_PROGRESS_WEIGHT = 60
        private const val PARSER_PROGRESS_WEIGHT = 40
        private const val PERCENT_COMPLETE = 100
        private const val DEFAULT_DIGEST_BUFFER_BYTES = 64 * 1024
        private val SESSION_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)
        private const val MAX_LIVE_EVENTS = 1_000
    }
}

internal fun defaultMemoryDataRoot(): Path = Path.of(System.getProperty("user.home"), ".android-performance-studio", "memory-profiler")

// Package flags alone do not establish heap-dump permission, especially on debug system builds.
// Offer application-owned processes and let am dumpheap enforce the actual device permissions.
internal fun AdbTargetSnapshot.heapDumpableProcesses(): List<MemoryProcessOption> =
    applicationProcesses().map { process ->
        MemoryProcessOption(process.pid, process.name, process.packageName)
    }
