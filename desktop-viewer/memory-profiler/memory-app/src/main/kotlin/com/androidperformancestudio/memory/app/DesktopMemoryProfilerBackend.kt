@file:Suppress("LongMethod", "MaxLineLength", "MagicNumber", "TooGenericExceptionCaught")

package com.androidperformancestudio.memory.app

import com.androidperformancestudio.adb.AdbDevicePropertiesReader
import com.androidperformancestudio.adb.AdbDeviceRefresher
import com.androidperformancestudio.adb.AdbTargetCatalog
import com.androidperformancestudio.adb.SystemAdbLocator
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
import com.androidperformancestudio.memory.capture.BitmapCaptureRequest
import com.androidperformancestudio.memory.capture.BitmapHeapDumpCaptureSession
import com.androidperformancestudio.memory.capture.MemoryCaptureRequest
import com.androidperformancestudio.memory.capture.MemoryHeapDumpCaptureSession
import com.androidperformancestudio.memory.capture.NativeHeapCaptureRequest
import com.androidperformancestudio.memory.capture.NativeHeapCaptureSession
import com.androidperformancestudio.memory.export.BitmapDumpExportAdapters
import com.androidperformancestudio.memory.export.MemoryExportAdapters
import com.androidperformancestudio.memory.hprof.BitmapDumpParseException
import com.androidperformancestudio.memory.hprof.BitmapDumpParser
import com.androidperformancestudio.memory.hprof.HprofInputProbe
import com.androidperformancestudio.memory.hprof.HprofParseException
import com.androidperformancestudio.memory.hprof.HprofParser
import com.androidperformancestudio.memory.hprof.HprofRecordIndexer
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
import com.androidperformancestudio.memory.model.LeakCanaryReport
import com.androidperformancestudio.memory.model.LeakCanaryStatus
import com.androidperformancestudio.memory.model.NativeHeapEvidenceSource
import com.androidperformancestudio.memory.model.NativeHeapTrace
import com.androidperformancestudio.memory.presentation.MemoryDeviceOption
import com.androidperformancestudio.memory.presentation.MemoryProcessOption
import com.androidperformancestudio.memory.storage.MemorySessionMetadata
import com.androidperformancestudio.memory.storage.MemorySessionUiSettings
import com.androidperformancestudio.memory.storage.SqliteMemorySessionStore
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.platform.toolchain.SystemHostPlatformDetector
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.Dispatchers
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
    private val dataRoot: Path = defaultDataRoot(),
    private val adbLocator: () -> Path? = ::locateSystemAdb,
    private val captureSessionFactory: (Path) -> MemoryHeapDumpCaptureSession = ::MemoryHeapDumpCaptureSession,
    private val bitmapCaptureSessionFactory: (Path) -> BitmapHeapDumpCaptureSession = ::BitmapHeapDumpCaptureSession,
    private val language: UiLanguage = UiLanguage.ENGLISH,
    private val nativeHeapArtifactAnalyzer: NativeHeapArtifactAnalyzer = PerfettoNativeHeapArtifactAnalyzer(),
    private val javaHeapArtifactAnalyzer: JavaHeapArtifactAnalyzer = PerfettoJavaHeapArtifactAnalyzer(),
) : MemoryProfilerBackend {
    private val parser = HprofParser()
    private val inputProbe = HprofInputProbe()
    private val recordIndexer = HprofRecordIndexer()
    private val analyzer = MemoryDeepAnalyzer()
    private val sharkLeakAnalyzer = SharkLeakAnalyzer()
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
    private var lastLoadedHeap: LoadedHeap? = null

    override suspend fun listDevices(): MemoryBackendResult<List<MemoryDeviceOption>> {
        val adb = adbLocator() ?: return missingAdb()
        return when (val result = AdbDeviceRefresher(adb).refresh()) {
            is StudioResult.Failure ->
                result.toBackendFailure(localizedStringResource(Res.string.unable_to_list_android_devices, language))
            is StudioResult.Success ->
                MemoryBackendResult.Success(
                    result.value.map { device ->
                        val apiLevel =
                            if (device.state == AdbDeviceState.ONLINE) {
                                when (val properties = AdbDevicePropertiesReader(adb).read(device.serial)) {
                                    is StudioResult.Success -> properties.value.sdkInt
                                    is StudioResult.Failure -> null
                                }
                            } else {
                                null
                            }
                        MemoryDeviceOption(
                            serial = device.serial,
                            name = device.model?.replace('_', ' ') ?: device.serial,
                            online = device.state == AdbDeviceState.ONLINE,
                            apiLevel = apiLevel,
                            supportsBitmapDump = apiLevel != null && apiLevel >= BitmapHeapDumpCaptureSession.MINIMUM_BITMAP_DUMP_API,
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

    override suspend fun importJavaHeap(file: Path): MemoryBackendResult<LoadedHeap> =
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
    override suspend fun importHprof(file: Path): MemoryBackendResult<LoadedHeap> = importHprof(file) {}

    override suspend fun importHprof(
        file: Path,
        onProgress: (Int) -> Unit,
    ): MemoryBackendResult<LoadedHeap> =
        withContext(Dispatchers.IO) {
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

    override suspend fun analyzeLeaks(): MemoryBackendResult<LeakCanaryReport> =
        withContext(Dispatchers.IO) {
            val loaded =
                lastLoadedHeap
                    ?: return@withContext MemoryBackendResult.Failure(
                        "Leak analysis unavailable",
                        "Load an HPROF snapshot before starting LeakCanary analysis.",
                    )
            val hprofFile = loaded.heapDump.convertedHprofFile ?: loaded.heapDump.rawHprofFile
            if (hprofFile == null || !Files.isRegularFile(hprofFile)) {
                return@withContext MemoryBackendResult.Failure(
                    "Leak analysis unavailable",
                    "The loaded snapshot does not have a readable HPROF source.",
                )
            }
            runCatching { sharkLeakAnalyzer.analyze(loaded.heapDump, hprofFile) }
                .fold(
                    onSuccess = { MemoryBackendResult.Success(it) },
                    onFailure = { error ->
                        MemoryBackendResult.Failure(
                            "Leak analysis failed",
                            error.message ?: error::class.simpleName.orEmpty(),
                        )
                    },
                )
        }

    override suspend fun importMapping(file: Path): MemoryBackendResult<LoadedHeap?> =
        withContext(Dispatchers.IO) {
            val parsedMapping =
                try {
                    ProguardMappingParser.parse(file)
                } catch (exception: Exception) {
                    return@withContext MemoryBackendResult.Failure(
                        localizedStringResource(Res.string.unable_to_load_mapping, language),
                        exception.message ?: exception::class.simpleName.orEmpty(),
                    )
                }
            mapping = parsedMapping
            mappingFile = file
            mappingDigest = digestFile(file)
            val last = lastLoadRequest
            if (last == null) {
                MemoryBackendResult.Success<LoadedHeap?>(null)
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

    override suspend fun loadSession(metadata: MemorySessionMetadata): MemoryBackendResult<LoadedHeap> =
        withContext(Dispatchers.IO) {
            mappingFile = metadata.mappingFile?.takeIf(Files::isRegularFile)
            mapping =
                mappingFile?.let { path ->
                    runCatching { ProguardMappingParser.parse(path) }.getOrNull()
                }
            mappingDigest = metadata.mappingDigest
            val file = metadata.convertedHprofFile ?: metadata.rawHprofFile
            if (!Files.isRegularFile(file)) {
                return@withContext MemoryBackendResult.Failure(
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

    private fun loadHeap(
        request: HeapLoadRequest,
        onProgress: (Int) -> Unit = {},
    ): MemoryBackendResult<LoadedHeap> {
        lastLoadRequest = request
        return try {
            val probe = inputProbe.inspect(request.file)
            val probeWarning = probe.warnings.joinToString(separator = "\n").ifBlank { null }
            val indexFile = ensureLargeFileIndex(request.file, probe.fileSizeBytes)
            val parsedFile =
                parser.parse(request.file) { parserProgress -> onProgress(parserProgress / 2) }.copy(
                    rawHprofFile = request.rawFile,
                    convertedHprofFile = request.convertedFile,
                )
            val mappingForLoad = validatedMapping()
            val mappingWarning =
                if (mapping != null && mappingForLoad == null) {
                    "mapping.txt digest changed or the file is unavailable; mapping was ignored until re-imported"
                } else {
                    null
                }
            val deobfuscated = mappingForLoad?.let { parsedFile.withDeobfuscation(it) } ?: parsedFile
            val parserWarning =
                parsedFile.warnings
                    .joinToString(separator = "\n", transform = { it.message })
                    .ifBlank { null }
            val capturedAt = Instant.now()
            val loaded =
                analyzeAndPackage(
                    heapDump = deobfuscated,
                    id = request.sessionMetadata?.sessionId ?: request.file.fileName.toString(),
                    packageName = request.sessionMetadata?.packageName.orEmpty(),
                    pid = request.sessionMetadata?.pid ?: 0,
                    capturedAt = capturedAt,
                    emptyWarningFileName = request.file.fileName.toString(),
                    extraWarning =
                        listOfNotNull(request.warning, probeWarning, mappingWarning, parserWarning)
                            .joinToString("\n")
                            .ifBlank { null },
                    cleanupWarning = request.cleanupWarning,
                    onAnalysisProgress = { onProgress(50 + it / 2) },
                    deobfuscator = mappingForLoad,
                )
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
            val uiSettings = request.sessionMetadata?.sessionId?.let(::loadWorkspaceSettings) ?: MemorySessionUiSettings()
            MemoryBackendResult.Success(
                loaded.copy(
                    uiSettings = uiSettings,
                    sourceFileDigest = digestFile(request.rawFile),
                    mappingDigest = mappingDigest,
                    indexFile = indexFile,
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

    private fun ensureLargeFileIndex(
        file: Path,
        fileSizeBytes: Long,
    ): Path? {
        if (fileSizeBytes <= HprofInputProbe.RECOMMENDED_MATERIALIZED_BYTES) return null
        val sourceDigest = digestFile(file)
        val indexFile = dataRoot.resolve("hprof-index").resolve("$sourceDigest.idx")
        if (!Files.isRegularFile(indexFile)) {
            recordIndexer.build(file, indexFile)
        } else {
            recordIndexer.read(indexFile)
        }
        return indexFile
    }

    private fun digestFile(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_DIGEST_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /**
     * Runs the deep heap analysis (dominator tree, retained sizes, leak suspects, bitmap/activity
     * leak detection) and packages the result. Shared by the HPROF load path and the perfetto
     * `java_hprof` import so both produce an identical [LoadedHeap].
     */
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
    ): LoadedHeap {
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
            )
        val parsed =
            analysisHeap.copy(
                leakCanaryReport =
                    LeakCanaryReport(
                        status = LeakCanaryStatus.NOT_RUN,
                        message = "LeakCanary analysis has not been run for this snapshot.",
                    ),
            )
        val loaded =
            LoadedHeap(
                heapDump = parsed,
                histogram = histogram,
                mapping = deobfuscator,
                availableHeaps = availableHeaps,
                perHeapClasses = perHeapClasses,
                warning =
                    listOfNotNull(extraWarning, emptyHeapWarning, noMappingWarning)
                        .joinToString("\n")
                        .ifBlank { null },
                cleanupWarning = cleanupWarning,
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
