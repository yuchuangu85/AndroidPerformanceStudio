package com.androidperformancestudio.winscope.capture

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbException
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import com.androidperformancestudio.platform.perfetto.PerfettoCaptureDocument
import com.androidperformancestudio.platform.perfetto.PerfettoConfigComposer
import com.androidperformancestudio.platform.perfetto.PerfettoDataSource
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRunner
import com.androidperformancestudio.platform.toolchain.JvmHostProcessRunner
import com.androidperformancestudio.winscope.analysis.WinscopeAnalyzer
import com.androidperformancestudio.winscope.model.WinscopeCapabilities
import com.androidperformancestudio.winscope.model.WinscopeCaptureConfig
import com.androidperformancestudio.winscope.model.WinscopeCapturePreset
import com.androidperformancestudio.winscope.model.WinscopeCompleteness
import com.androidperformancestudio.winscope.model.WinscopeDevice
import com.androidperformancestudio.winscope.model.WinscopeDisplay
import com.androidperformancestudio.winscope.model.WinscopeLimitation
import com.androidperformancestudio.winscope.model.WinscopePhase
import com.androidperformancestudio.winscope.model.WinscopeRuntimeState
import com.androidperformancestudio.winscope.model.WinscopeSession
import com.androidperformancestudio.winscope.model.WinscopeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class WinscopeCapabilityDetector(
    private val adbFactory: (Path) -> AdbClient = ::DefaultAdbClient,
    private val processRunner: HostProcessRunner = JvmHostProcessRunner(),
) {
    suspend fun detect(
        adbPath: Path,
        serial: String,
    ): StudioResult<WinscopeCapabilities> =
        try {
            val adb = adbFactory(adbPath)
            val facts =
                adb
                    .shell(
                        serial,
                        listOf(
                            "sh",
                            "-c",
                            "getprop ro.build.version.sdk; getprop ro.build.type; getprop ro.product.model; id -u",
                        ),
                        10.seconds,
                    ).stdout
                    .lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toList()
            val sdk = facts.getOrNull(0)?.toIntOrNull() ?: 0
            val buildType = facts.getOrNull(1).orEmpty().ifBlank { "unknown" }
            val query = adb.shell(serial, listOf("perfetto", "--query"), 15.seconds).stdout
            val registered = parseDataSources(query)
            val displays =
                runCatching {
                    adb
                        .shell(serial, listOf("dumpsys", "SurfaceFlinger", "--display-id"), 10.seconds)
                        .stdout
                        .lineSequence()
                        .mapNotNull { line ->
                            DISPLAY_REGEX.find(line)?.let { match ->
                                WinscopeDisplay(
                                    match.groupValues[1].toLong(),
                                    match.groupValues[2].ifBlank { "Display ${match.groupValues[1]}" },
                                )
                            }
                        }.toList()
                }.getOrDefault(emptyList())
            val available =
                WinscopeSource.entries.filterTo(mutableSetOf()) { source ->
                    source == WinscopeSource.SCREEN_RECORDING || source.perfettoName in registered
                }
            val root = facts.getOrNull(3)?.toIntOrNull() == 0
            val device =
                WinscopeDevice(
                    serial = serial,
                    model = facts.getOrNull(2).orEmpty().ifBlank { "Android" },
                    androidSdk = sdk,
                    buildType = buildType,
                    rootActive = root,
                    rootAvailable = !root && buildType in setOf("userdebug", "eng"),
                )
            val limitations =
                buildList {
                    if (sdk < 35) add(WinscopeLimitation(null, "ANDROID_15_REQUIRED", "Live Winscope capture requires Android 15 or newer"))
                    if (!root) add(WinscopeLimitation(null, "ADB_ROOT_NOT_ACTIVE", "ADB root is not active"))
                    WinscopeSource.entries.filter { it.perfettoName != null && it !in available }.forEach { source ->
                        add(WinscopeLimitation(source, "DATA_SOURCE_UNAVAILABLE", "${source.perfettoName} is not registered"))
                    }
                }
            StudioResult.Success(WinscopeCapabilities(device, registered, available, limitations, displays))
        } catch (error: AdbException) {
            failure("WINSCOPE_CAPABILITY_QUERY_FAILED", error.message ?: "Unable to query device capabilities", error)
        } catch (error: IllegalArgumentException) {
            failure("ADB_PATH_INVALID", error.message ?: "ADB path is invalid", error)
        }

    suspend fun restartAsRoot(
        adbPath: Path,
        serial: String,
    ): StudioResult<Unit> =
        try {
            val result =
                processRunner.executeText(
                    HostProcessRequest(
                        executable = adbPath,
                        arguments = listOf("-s", serial, "root"),
                        timeout = 30.seconds,
                    ),
                )
            if (result.exitCode == 0 && !result.stdout.contains("cannot run as root", ignoreCase = true)) {
                val ready =
                    processRunner.executeText(
                        HostProcessRequest(
                            executable = adbPath,
                            arguments = listOf("-s", serial, "wait-for-device"),
                            timeout = 30.seconds,
                        ),
                    )
                if (ready.exitCode == 0) {
                    StudioResult.Success(Unit)
                } else {
                    failure(
                        "ADB_ROOT_RECONNECT_FAILED",
                        (ready.stderr + ready.stdout).trim().ifBlank {
                            "Device did not reconnect after adb root"
                        },
                    )
                }
            } else {
                failure("ADB_ROOT_UNAVAILABLE", (result.stderr + result.stdout).trim().ifBlank { "ADB root is unavailable" })
            }
        } catch (error: Exception) {
            failure("ADB_ROOT_FAILED", error.message ?: "Unable to restart ADB as root", error)
        }

    companion object {
        private val DISPLAY_REGEX = Regex("Display (\\d+).*displayName=\\\"(.*)\\\"")

        internal fun parseDataSources(output: String): Set<String> =
            output
                .substringAfter("DATA SOURCES REGISTERED:", "")
                .substringBefore("TRACING SESSIONS:")
                .lineSequence()
                .map(String::trim)
                .filter { it.isNotBlank() && !it.startsWith("NAME") && !it.startsWith("===") }
                .map { it.substringBefore(' ') }
                .toSet()
    }
}

class WinscopeCaptureController(
    private val adbFactory: (Path) -> AdbClient = ::DefaultAdbClient,
    private val storageDirectory: Path = defaultCaptureDirectory(),
    private val sourceProbe: suspend (Path) -> StudioResult<Set<WinscopeSource>> = ::probeWinscopeSources,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(WinscopeRuntimeState())
    val state: StateFlow<WinscopeRuntimeState> = _state.asStateFlow()

    private var active: ActiveCapture? = null
    private var completionJob: Job? = null

    init {
        Files.createDirectories(storageDirectory)
    }

    suspend fun start(
        adbPath: Path,
        capabilities: WinscopeCapabilities,
        config: WinscopeCaptureConfig,
    ): StudioResult<Unit> =
        mutex.withLock {
            if (active != null) return failure("WINSCOPE_CAPTURE_ACTIVE", "A Winscope capture is already active")
            if (!capabilities.liveCaptureSupported) {
                return failure("WINSCOPE_LIVE_UNAVAILABLE", "Android 15+ WindowManager or SurfaceFlinger evidence is required")
            }
            var available = config.requestedSources.intersect(capabilities.availableSources)
            if (WinscopeCapabilities.CORE_SOURCES.none(available::contains)) {
                return failure("WINSCOPE_CORE_UNAVAILABLE", "WindowManager and SurfaceFlinger evidence are unavailable")
            }
            val id = UUID.randomUUID().toString()
            val remoteTrace = "$REMOTE_DIRECTORY/aps-winscope-$id.perfetto-trace"
            val remoteRecording = "$REMOTE_DIRECTORY/aps-winscope-$id.mp4"
            val localTrace = storageDirectory.resolve("$id.perfetto-trace")
            val localRecording = storageDirectory.resolve("$id.mp4")
            val adb = adbFactory(adbPath)
            _state.value = WinscopeRuntimeState(WinscopePhase.PREPARING, message = "Preparing Winscope capture")
            try {
                val configFile = Files.createTempFile("winscope-$id", ".pbtxt")
                try {
                    Files.writeString(configFile, WinscopeConfigBuilder.build(config.copy(requestedSources = available)))
                    val remoteConfig = "$REMOTE_DIRECTORY/aps-winscope-$id.pbtxt"
                    adb.push(capabilities.device.serial, configFile, remoteConfig, 30.seconds)
                    val started =
                        adb.shell(
                            capabilities.device.serial,
                            listOf("sh", "-c", "cat $remoteConfig | perfetto --txt -c - -o $remoteTrace --background-wait"),
                            60.seconds,
                        )
                    val perfettoPid =
                        parsePid(started.stdout + "\n" + started.stderr)
                            ?: return captureFailure(
                                "PERFETTO_PID_MISSING",
                                "Perfetto did not return a process id",
                                adb,
                                capabilities.device.serial,
                                remoteConfig,
                            )
                    val recordingPid =
                        if (WinscopeSource.SCREEN_RECORDING in available) {
                            startScreenRecording(adb, capabilities.device.serial, remoteRecording, config)
                        } else {
                            null
                        }
                    val legacyWindowManager =
                        WinscopeSource.WINDOW_MANAGER in config.requestedSources &&
                            WinscopeSource.WINDOW_MANAGER !in available &&
                            capabilities.device.rootActive &&
                            startLegacyWindowManager(adb, capabilities.device.serial, config)
                    if (WinscopeSource.SCREEN_RECORDING in available && recordingPid == null) {
                        available -= WinscopeSource.SCREEN_RECORDING
                    }
                    active =
                        ActiveCapture(
                            id,
                            adb,
                            capabilities,
                            config,
                            available,
                            perfettoPid,
                            recordingPid,
                            legacyWindowManager,
                            remoteConfig,
                            remoteTrace,
                            remoteRecording,
                            localTrace,
                            localRecording,
                            Instant.now(),
                        )
                    _state.value = WinscopeRuntimeState(WinscopePhase.RECORDING, message = "Recording Winscope trace")
                    completionJob =
                        scope.launch {
                            delay(config.durationSeconds.seconds)
                            finish(requestStop = false)
                        }
                    StudioResult.Success(Unit)
                } finally {
                    Files.deleteIfExists(configFile)
                }
            } catch (error: AdbException) {
                active = null
                setFailure("WINSCOPE_CAPTURE_START_FAILED", error.message ?: "Unable to start Winscope capture")
            } catch (error: Exception) {
                active = null
                setFailure("WINSCOPE_CAPTURE_START_FAILED", error.message ?: "Unable to start Winscope capture")
            }
        }

    suspend fun stop(): StudioResult<WinscopeSession> {
        completionJob?.cancel()
        return finish(requestStop = true)
    }

    suspend fun snapshot(
        adbPath: Path,
        capabilities: WinscopeCapabilities,
    ): StudioResult<WinscopeSession> =
        mutex.withLock {
            if (active != null) return failure("WINSCOPE_CAPTURE_ACTIVE", "Stop the active trace before taking a snapshot")
            if (!capabilities.liveCaptureSupported) {
                return failure("WINSCOPE_LIVE_UNAVAILABLE", "Android 15+ WindowManager or SurfaceFlinger evidence is required")
            }
            val config =
                WinscopeCaptureConfig(
                    durationSeconds = 1,
                    requestedSources = WinscopeCapabilities.CORE_SOURCES,
                )
            val id = UUID.randomUUID().toString()
            val adb = adbFactory(adbPath)
            val remoteTrace = "$REMOTE_DIRECTORY/aps-winscope-dump-$id.perfetto-trace"
            val localTrace = storageDirectory.resolve("dump-$id.perfetto-trace")
            val screenshot = storageDirectory.resolve("dump-$id.png")
            val remoteConfig = "$REMOTE_DIRECTORY/aps-winscope-dump-$id.pbtxt"
            _state.value = WinscopeRuntimeState(WinscopePhase.PREPARING, message = "Taking Winscope snapshot")
            try {
                val configFile = Files.createTempFile("winscope-dump", ".pbtxt")
                try {
                    Files.writeString(configFile, WinscopeConfigBuilder.buildDump())
                    adb.push(capabilities.device.serial, configFile, remoteConfig)
                    adb.shell(
                        capabilities.device.serial,
                        listOf("sh", "-c", "cat $remoteConfig | perfetto --txt -c - -o $remoteTrace"),
                        60.seconds,
                    )
                    enforceRemoteSize(adb, capabilities.device.serial, remoteTrace)
                    adb.pull(capabilities.device.serial, remoteTrace, localTrace, 2.minutes)
                    val screenshotBytes =
                        adb.execOut(capabilities.device.serial, listOf("screencap", "-p"), 30.seconds, MAX_SCREENSHOT_BYTES).stdout
                    require(screenshotBytes.isNotEmpty()) { "Device returned an empty screenshot" }
                    Files.write(screenshot, screenshotBytes)
                    cleanup(adb, capabilities.device.serial, remoteConfig, remoteTrace)
                } finally {
                    Files.deleteIfExists(configFile)
                }
                val available = WinscopeCapabilities.CORE_SOURCES.intersect(capabilities.availableSources)
                val session =
                    WinscopeSession(
                        id = id,
                        traceFile = localTrace,
                        screenshotFile = screenshot,
                        capturedAt = Instant.now(),
                        device = capabilities.device,
                        requestedSources = WinscopeCapabilities.CORE_SOURCES + WinscopeSource.SCREENSHOT,
                        availableSources = available + WinscopeSource.SCREENSHOT,
                        completeness =
                            if (available.containsAll(
                                    WinscopeCapabilities.CORE_SOURCES,
                                )
                            ) {
                                WinscopeCompleteness.COMPLETE
                            } else {
                                WinscopeCompleteness.PARTIAL
                            },
                        sensitive = true,
                        managedFiles = true,
                        isDump = true,
                    )
                _state.value = WinscopeRuntimeState(WinscopePhase.READY, session, "Winscope snapshot ready")
                StudioResult.Success(session)
            } catch (error: Exception) {
                setFailure("WINSCOPE_SNAPSHOT_FAILED", error.message ?: "Unable to take Winscope snapshot")
            }
        }

    suspend fun recover(
        adbPath: Path,
        capabilities: WinscopeCapabilities,
    ): StudioResult<List<String>> =
        try {
            val output =
                adbFactory(adbPath)
                    .shell(
                        capabilities.device.serial,
                        listOf("sh", "-c", "ls -1t $REMOTE_DIRECTORY/aps-winscope-*.perfetto-trace 2>/dev/null"),
                        10.seconds,
                    ).stdout
            StudioResult.Success(
                output
                    .lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toList(),
            )
        } catch (error: AdbException) {
            failure("WINSCOPE_RECOVERY_QUERY_FAILED", error.message ?: "Unable to find recoverable traces", error)
        }

    suspend fun pullRecovered(
        adbPath: Path,
        capabilities: WinscopeCapabilities,
        remotePath: String,
    ): StudioResult<WinscopeSession> =
        try {
            require(remotePath.startsWith("$REMOTE_DIRECTORY/aps-winscope-") && remotePath.endsWith(".perfetto-trace")) {
                "Invalid recoverable trace path"
            }
            val id = UUID.randomUUID().toString()
            val local = storageDirectory.resolve("recovered-$id.perfetto-trace")
            val adb = adbFactory(adbPath)
            enforceRemoteSize(adb, capabilities.device.serial, remotePath)
            adb.pull(capabilities.device.serial, remotePath, local, 2.minutes)
            cleanup(adb, capabilities.device.serial, remotePath)
            StudioResult.Success(
                WinscopeSession(
                    id = id,
                    traceFile = local,
                    capturedAt = Instant.now(),
                    device = capabilities.device,
                    availableSources = capabilities.availableSources,
                    completeness = WinscopeCompleteness.UNKNOWN,
                    managedFiles = true,
                ),
            )
        } catch (error: Exception) {
            failure("WINSCOPE_RECOVERY_PULL_FAILED", error.message ?: "Unable to recover trace", error)
        }

    private suspend fun finish(requestStop: Boolean): StudioResult<WinscopeSession> =
        mutex.withLock {
            val capture = active ?: return failure("WINSCOPE_NOT_RECORDING", "No Winscope capture is active")
            _state.value = WinscopeRuntimeState(WinscopePhase.PULLING, message = "Stopping and pulling Winscope evidence")
            return try {
                if (requestStop) {
                    capture.adb.shell(
                        capture.capabilities.device.serial,
                        listOf("kill", "-INT", capture.perfettoPid.toString()),
                        10.seconds,
                    )
                }
                capture.recordingPid?.let { recordingPid ->
                    runCatching {
                        capture.adb.shell(
                            capture.capabilities.device.serial,
                            listOf("kill", "-INT", recordingPid.toString()),
                            10.seconds,
                        )
                    }
                }
                if (capture.legacyWindowManager) {
                    runCatching {
                        capture.adb.shell(
                            capture.capabilities.device.serial,
                            listOf("cmd", "window", "tracing", "stop"),
                            30.seconds,
                        )
                    }
                }
                waitUntilStopped(capture, capture.perfettoPid, "Perfetto")
                capture.recordingPid?.let { waitUntilStopped(capture, it, "screen recording") }
                enforceRemoteSize(capture.adb, capture.capabilities.device.serial, capture.remoteTrace)
                capture.adb.pull(capture.capabilities.device.serial, capture.remoteTrace, capture.localTrace, 2.minutes)
                if (capture.recordingPid != null) {
                    runCatching {
                        capture.adb.pull(
                            capture.capabilities.device.serial,
                            capture.remoteRecording,
                            capture.localRecording,
                            2.minutes,
                        )
                    }
                }
                if (capture.legacyWindowManager) mergeLegacyWindowManager(capture)
                cleanup(
                    capture.adb,
                    capture.capabilities.device.serial,
                    capture.remoteConfig,
                    capture.remoteTrace,
                    capture.remoteRecording,
                    LEGACY_WINDOW_MANAGER_FILE,
                    LEGACY_WINDOW_MANAGER_PB_FILE,
                )
                val recordingFile =
                    capture.localRecording.takeIf { Files.isRegularFile(it) && Files.size(it) > 0L }
                if (recordingFile == null) Files.deleteIfExists(capture.localRecording)
                val traceSources =
                    when (val probed = sourceProbe(capture.localTrace)) {
                        is StudioResult.Success -> probed.value.intersect(capture.config.requestedSources)
                        is StudioResult.Failure -> throw IllegalStateException(
                            "Unable to verify captured Winscope evidence: ${probed.error.message}",
                        )
                    }
                val availableSources = traceSources + if (recordingFile == null) emptySet() else setOf(WinscopeSource.SCREEN_RECORDING)
                if (WinscopeCapabilities.CORE_SOURCES.none(traceSources::contains)) {
                    active = null
                    val recordingMessage =
                        recordingFile
                            ?.let { "; only the screen recording contains usable evidence and is saved at $it" }
                            .orEmpty()
                    return setFailure(
                        "WINSCOPE_CORE_EVIDENCE_MISSING",
                        "No WindowManager or SurfaceFlinger evidence was captured$recordingMessage. " +
                            "The raw Perfetto trace is at ${capture.localTrace}. Use an Android 15+ userdebug/eng device with adb root.",
                    )
                }
                val missing = capture.config.requestedSources - availableSources
                val limitations =
                    missing.map { WinscopeLimitation(it, "DATA_SOURCE_UNAVAILABLE", "${it.displayName} was requested but unavailable") }
                val session =
                    WinscopeSession(
                        id = capture.id,
                        traceFile = capture.localTrace,
                        recordingFile = recordingFile,
                        capturedAt = capture.startedAt,
                        device = capture.capabilities.device,
                        requestedSources = capture.config.requestedSources,
                        availableSources = availableSources,
                        limitations = limitations,
                        completeness = if (missing.isEmpty()) WinscopeCompleteness.COMPLETE else WinscopeCompleteness.PARTIAL,
                        sensitive =
                            recordingFile != null ||
                                WinscopeSource.INPUT in traceSources ||
                                (capture.config.protoLogStacktraces && WinscopeSource.PROTO_LOG in traceSources),
                        managedFiles = true,
                    )
                active = null
                _state.value = WinscopeRuntimeState(WinscopePhase.READY, session, "Winscope trace ready")
                StudioResult.Success(session)
            } catch (error: Exception) {
                active = null
                setFailure("WINSCOPE_CAPTURE_FINISH_FAILED", error.message ?: "Unable to finish Winscope capture")
            }
        }

    private suspend fun waitUntilStopped(
        capture: ActiveCapture,
        pid: Long,
        processName: String,
    ) {
        repeat(40) {
            val result =
                capture.adb.shell(
                    capture.capabilities.device.serial,
                    listOf("sh", "-c", "kill -0 $pid 2>/dev/null; echo $?"),
                    5.seconds,
                )
            if (result.stdout
                    .lineSequence()
                    .lastOrNull(String::isNotBlank)
                    ?.trim()
                    ?.toIntOrNull() != 0
            ) {
                return
            }
            delay(250)
        }
        throw IllegalStateException("Timed out waiting for $processName to stop")
    }

    private suspend fun startLegacyWindowManager(
        adb: AdbClient,
        serial: String,
        config: WinscopeCaptureConfig,
    ): Boolean =
        runCatching {
            cleanup(adb, serial, LEGACY_WINDOW_MANAGER_FILE, LEGACY_WINDOW_MANAGER_PB_FILE)
            adb.shell(
                serial,
                listOf(
                    "sh",
                    "-c",
                    "cmd window tracing ${if (config.preset == WinscopeCapturePreset.FULL_DETAIL) "transaction" else "frame"}; " +
                        "cmd window tracing level ${if (config.preset == WinscopeCapturePreset.FULL_DETAIL) "verbose" else "debug"}; " +
                        "cmd window tracing start",
                ),
                30.seconds,
            )
            true
        }.getOrDefault(false)

    private suspend fun mergeLegacyWindowManager(capture: ActiveCapture): Boolean =
        runCatching {
            val remote =
                capture.adb
                    .shell(
                        capture.capabilities.device.serial,
                        listOf("sh", "-c", "ls -1t ${LEGACY_WINDOW_MANAGER_FILES.joinToString(" ")} 2>/dev/null | head -n 1"),
                        10.seconds,
                    ).stdout
                    .lineSequence()
                    .map(String::trim)
                    .firstOrNull { it in LEGACY_WINDOW_MANAGER_FILES }
                    ?: return false
            enforceRemoteSize(capture.adb, capture.capabilities.device.serial, remote)
            val local = storageDirectory.resolve("${capture.id}-window-manager.winscope")
            try {
                capture.adb.pull(capture.capabilities.device.serial, remote, local, 2.minutes)
                appendLegacyWindowManagerTrace(capture.localTrace, local) > 0
            } finally {
                Files.deleteIfExists(local)
            }
        }.getOrDefault(false)

    private suspend fun enforceRemoteSize(
        adb: AdbClient,
        serial: String,
        remotePath: String,
    ) {
        val size =
            adb
                .shell(serial, listOf("sh", "-c", "stat -c %s $remotePath"), 10.seconds)
                .stdout
                .trim()
                .toLongOrNull()
                ?: throw IllegalStateException("Unable to determine remote trace size")
        require(size in 1..MAX_TRACE_BYTES) { "Winscope trace is empty or exceeds the 1 GB limit" }
    }

    private suspend fun startScreenRecording(
        adb: AdbClient,
        serial: String,
        remotePath: String,
        config: WinscopeCaptureConfig,
    ): Long? {
        val display = config.selectedDisplayId?.let { "--display-id $it " }.orEmpty()
        val output =
            adb
                .shell(
                    serial,
                    listOf(
                        "sh",
                        "-c",
                        "screenrecord $display--time-limit ${config.durationSeconds} $remotePath >/dev/null 2>&1 & echo \$!",
                    ),
                    10.seconds,
                ).stdout
        return parsePid(output)
    }

    private suspend fun cleanup(
        adb: AdbClient,
        serial: String,
        vararg paths: String,
    ) {
        val safe =
            paths.filter {
                it.startsWith("$REMOTE_DIRECTORY/aps-winscope-") || it in LEGACY_WINDOW_MANAGER_FILES
            }
        if (safe.isNotEmpty()) runCatching { adb.shell(serial, listOf("rm", "-f") + safe, 10.seconds) }
    }

    private suspend fun captureFailure(
        code: String,
        message: String,
        adb: AdbClient,
        serial: String,
        remoteConfig: String,
    ): StudioResult<Unit> {
        cleanup(adb, serial, remoteConfig)
        return setFailure(code, message)
    }

    private fun <T> setFailure(
        code: String,
        message: String,
    ): StudioResult<T> {
        _state.value = WinscopeRuntimeState(WinscopePhase.FAILED, message = message, errorCode = code)
        return failure(code, message)
    }

    override fun close() {
        completionJob?.cancel()
    }

    private data class ActiveCapture(
        val id: String,
        val adb: AdbClient,
        val capabilities: WinscopeCapabilities,
        val config: WinscopeCaptureConfig,
        val availableSources: Set<WinscopeSource>,
        val perfettoPid: Long,
        val recordingPid: Long?,
        val legacyWindowManager: Boolean,
        val remoteConfig: String,
        val remoteTrace: String,
        val remoteRecording: String,
        val localTrace: Path,
        val localRecording: Path,
        val startedAt: Instant,
    )

    companion object {
        private const val REMOTE_DIRECTORY = "/data/misc/perfetto-traces"
        private const val MAX_TRACE_BYTES = 1_073_741_824L
        private const val MAX_SCREENSHOT_BYTES = 64 * 1024 * 1024
        private const val LEGACY_WINDOW_MANAGER_FILE = "/data/misc/wmtrace/wm_trace.winscope"
        private const val LEGACY_WINDOW_MANAGER_PB_FILE = "/data/misc/wmtrace/wm_trace.pb"
        private val LEGACY_WINDOW_MANAGER_FILES =
            arrayOf(LEGACY_WINDOW_MANAGER_FILE, LEGACY_WINDOW_MANAGER_PB_FILE)

        private fun defaultCaptureDirectory(): Path =
            Path.of(System.getProperty("user.home"), ".android-performance-studio", "winscope-sessions")

        internal fun parsePid(output: String): Long? =
            output
                .lineSequence()
                .map(String::trim)
                .mapNotNull(String::toLongOrNull)
                .firstOrNull { it > 0 }
    }
}

private suspend fun probeWinscopeSources(trace: Path): StudioResult<Set<WinscopeSource>> {
    val analyzer =
        when (val opened = WinscopeAnalyzer.open(WinscopeSession("capture-verification", trace, capturedAt = Instant.EPOCH))) {
            is StudioResult.Success -> opened.value
            is StudioResult.Failure -> return opened
        }
    return try {
        analyzer.probeSources()
    } finally {
        analyzer.close()
    }
}

internal fun appendLegacyWindowManagerTrace(
    perfettoTrace: Path,
    legacyTrace: Path,
): Int {
    val legacy = Files.readAllBytes(legacyTrace)
    require(
        legacy.size >= LEGACY_WINDOW_MANAGER_MAGIC.size &&
            legacy.copyOfRange(0, LEGACY_WINDOW_MANAGER_MAGIC.size).contentEquals(LEGACY_WINDOW_MANAGER_MAGIC),
    ) {
        "Invalid legacy WindowManager trace"
    }
    val packets = ByteArrayOutputStream()
    var offset = LEGACY_WINDOW_MANAGER_MAGIC.size
    var count = 0
    while (offset < legacy.size) {
        val (tag, afterTag) = readVarint(legacy, offset)
        offset = afterTag
        val field = (tag ushr 3).toInt()
        val wireType = (tag and 7).toInt()
        if (wireType == 2) {
            val (sizeValue, afterSize) = readVarint(legacy, offset)
            val size = sizeValue.toInt()
            val end = afterSize + size
            require(size >= 0 && end in afterSize..legacy.size) { "Invalid legacy WindowManager trace field" }
            if (field == 2) {
                val entry = legacy.copyOfRange(afterSize, end)
                val timestamp = readFixed64Field(entry, 1)
                val winscope = lengthDelimitedField(6, entry)
                val packet =
                    ByteArrayOutputStream()
                        .apply {
                            writeVarint(this, 8L shl 3)
                            writeVarint(this, timestamp)
                            write(lengthDelimitedField(112, winscope))
                        }.toByteArray()
                packets.write(lengthDelimitedField(1, packet))
                count++
            }
            offset = end
        } else {
            offset = skipField(legacy, offset, wireType)
        }
    }
    require(count > 0) { "Legacy WindowManager trace contains no entries" }
    Files.write(perfettoTrace, packets.toByteArray(), StandardOpenOption.APPEND)
    return count
}

private val LEGACY_WINDOW_MANAGER_MAGIC = byteArrayOf(0x09, 0x57, 0x49, 0x4e, 0x54, 0x52, 0x41, 0x43, 0x45)

private fun lengthDelimitedField(
    field: Int,
    value: ByteArray,
): ByteArray =
    ByteArrayOutputStream()
        .apply {
            writeVarint(this, (field.toLong() shl 3) or 2)
            writeVarint(this, value.size.toLong())
            write(value)
        }.toByteArray()

private fun readFixed64Field(
    message: ByteArray,
    wantedField: Int,
): Long {
    var offset = 0
    while (offset < message.size) {
        val (tag, afterTag) = readVarint(message, offset)
        offset = afterTag
        val field = (tag ushr 3).toInt()
        val wireType = (tag and 7).toInt()
        if (field == wantedField && wireType == 1) {
            require(offset + 8 <= message.size) { "Invalid fixed64 field" }
            var value = 0L
            repeat(8) { index -> value = value or ((message[offset + index].toLong() and 0xff) shl (index * 8)) }
            return value
        }
        offset = skipField(message, offset, wireType)
    }
    return 0L
}

private fun skipField(
    bytes: ByteArray,
    offset: Int,
    wireType: Int,
): Int =
    when (wireType) {
        0 -> readVarint(bytes, offset).second
        1 -> (offset + 8).also { require(it <= bytes.size) { "Invalid fixed64 field" } }
        2 -> {
            val (size, start) = readVarint(bytes, offset)
            (start + size.toInt()).also { require(size <= Int.MAX_VALUE && it in start..bytes.size) { "Invalid length-delimited field" } }
        }
        5 -> (offset + 4).also { require(it <= bytes.size) { "Invalid fixed32 field" } }
        else -> error("Unsupported protobuf wire type $wireType")
    }

private fun readVarint(
    bytes: ByteArray,
    start: Int,
): Pair<Long, Int> {
    var value = 0L
    var shift = 0
    var offset = start
    while (offset < bytes.size && shift < 64) {
        val byte = bytes[offset++].toInt() and 0xff
        value = value or ((byte and 0x7f).toLong() shl shift)
        if (byte and 0x80 == 0) return value to offset
        shift += 7
    }
    error("Invalid protobuf varint")
}

private fun writeVarint(
    output: ByteArrayOutputStream,
    input: Long,
) {
    var value = input
    while (value and -0x80L != 0L) {
        output.write(((value and 0x7f) or 0x80).toInt())
        value = value ushr 7
    }
    output.write(value.toInt())
}

object WinscopeConfigBuilder {
    fun build(config: WinscopeCaptureConfig): String {
        val document =
            PerfettoCaptureDocument(
                durationMillis = config.durationSeconds * 1_000L,
                bufferSizeKb = config.preset.bufferSizeKb,
                flushPeriodMillis = 1_000,
                dataSources = config.requestedSources.mapNotNull { it.toPerfetto(config) },
            )
        return PerfettoConfigComposer.compose(document) + "write_into_file: true\n"
    }

    fun buildDump(): String =
        PerfettoConfigComposer.compose(
            PerfettoCaptureDocument(
                durationMillis = 1_000,
                bufferSizeKb = 65_536,
                dataSources =
                    listOf(
                        PerfettoDataSource(
                            WinscopeSource.WINDOW_MANAGER.perfettoName!!,
                            "windowmanager_config { log_level: LOG_LEVEL_DEBUG log_frequency: LOG_FREQUENCY_SINGLE_DUMP }",
                        ),
                        PerfettoDataSource(
                            WinscopeSource.SURFACE_FLINGER.perfettoName!!,
                            "surfaceflinger_layers_config { mode: MODE_DUMP trace_flags: TRACE_FLAG_INPUT trace_flags: TRACE_FLAG_COMPOSITION }",
                        ),
                    ),
            ),
        ) + "write_into_file: true\n"

    private fun WinscopeSource.toPerfetto(config: WinscopeCaptureConfig): PerfettoDataSource? =
        when (this) {
            WinscopeSource.WINDOW_MANAGER ->
                PerfettoDataSource(
                    perfettoName!!,
                    if (config.preset == WinscopeCapturePreset.FULL_DETAIL) {
                        "windowmanager_config { log_level: LOG_LEVEL_VERBOSE log_frequency: LOG_FREQUENCY_TRANSACTION }"
                    } else {
                        "windowmanager_config { log_level: LOG_LEVEL_DEBUG log_frequency: LOG_FREQUENCY_FRAME }"
                    },
                )
            WinscopeSource.SURFACE_FLINGER ->
                PerfettoDataSource(
                    perfettoName!!,
                    buildString {
                        append("surfaceflinger_layers_config { mode: MODE_ACTIVE ")
                        append("trace_flags: TRACE_FLAG_INPUT trace_flags: TRACE_FLAG_COMPOSITION trace_flags: TRACE_FLAG_BUFFERS ")
                        if (config.preset == WinscopeCapturePreset.FULL_DETAIL) {
                            append("trace_flags: TRACE_FLAG_EXTRA trace_flags: TRACE_FLAG_HWC trace_flags: TRACE_FLAG_VIRTUAL_DISPLAYS ")
                        }
                        append('}')
                    },
                )
            WinscopeSource.TRANSACTIONS ->
                PerfettoDataSource(
                    perfettoName!!,
                    "surfaceflinger_transactions_config { mode: ${if (config.preset == WinscopeCapturePreset.FULL_DETAIL) "MODE_ACTIVE" else "MODE_CONTINUOUS"} }",
                )
            WinscopeSource.PROTO_LOG ->
                PerfettoDataSource(
                    perfettoName!!,
                    buildString {
                        append("protolog_config { tracing_mode: ${if (config.protoLogEnableAll) "ENABLE_ALL" else "DEFAULT"} ")
                        append("default_log_from_level: PROTOLOG_LEVEL_${config.protoLogLevel.name} ")
                        if (config.protoLogStacktraces) {
                            PROTO_LOG_GROUPS.forEach { group ->
                                append(
                                    "group_overrides { group_name: \"$group\" log_from: PROTOLOG_LEVEL_${config.protoLogLevel.name} collect_stacktrace: true } ",
                                )
                            }
                        }
                        append('}')
                    },
                )
            WinscopeSource.INPUT ->
                PerfettoDataSource(perfettoName!!, "android_input_event_config { mode: TRACE_MODE_TRACE_ALL }")
            WinscopeSource.EVENT_LOG ->
                PerfettoDataSource(perfettoName!!, "android_log_config { log_ids: LID_EVENTS }")
            WinscopeSource.SCREEN_RECORDING,
            WinscopeSource.SCREENSHOT,
            -> null
            else -> PerfettoDataSource(perfettoName!!)
        }

    private val PROTO_LOG_GROUPS =
        setOf(
            "WM_ERROR",
            "WM_DEBUG_ORIENTATION",
            "WM_DEBUG_FOCUS_LIGHT",
            "WM_DEBUG_BOOT",
            "WM_DEBUG_RESIZE",
            "WM_DEBUG_ADD_REMOVE",
            "WM_DEBUG_CONFIGURATION",
            "WM_DEBUG_SWITCH",
            "WM_DEBUG_CONTAINERS",
            "WM_DEBUG_FOCUS",
            "WM_DEBUG_IMMERSIVE",
            "WM_DEBUG_LOCKTASK",
            "WM_DEBUG_STATES",
            "WM_DEBUG_TASKS",
            "WM_DEBUG_STARTING_WINDOW",
            "WM_SHOW_TRANSACTIONS",
            "WM_SHOW_SURFACE_ALLOC",
            "WM_DEBUG_APP_TRANSITIONS",
            "WM_DEBUG_ANIM",
            "WM_DEBUG_APP_TRANSITIONS_ANIM",
            "WM_DEBUG_RECENTS_ANIMATIONS",
            "WM_DEBUG_DRAW",
            "WM_DEBUG_REMOTE_ANIMATIONS",
            "WM_DEBUG_SCREEN_ON",
            "WM_DEBUG_KEEP_SCREEN_ON",
            "WM_DEBUG_WINDOW_MOVEMENT",
            "WM_DEBUG_IME",
            "WM_DEBUG_WINDOW_ORGANIZER",
            "WM_DEBUG_SYNC_ENGINE",
            "WM_DEBUG_WINDOW_TRANSITIONS",
            "WM_DEBUG_WINDOW_TRANSITIONS_MIN",
            "WM_DEBUG_WINDOW_INSETS",
            "WM_DEBUG_CONTENT_RECORDING",
            "WM_DEBUG_WALLPAPER",
            "WM_DEBUG_BACK_PREVIEW",
            "WM_SHELL",
            "WM_SHELL_INIT",
            "WM_SHELL_TASK_ORG",
            "WM_SHELL_TRANSITIONS",
            "WM_SHELL_RECENTS_TRANSITION",
            "WM_SHELL_RECENT_TASKS",
            "WM_SHELL_STARTING_WINDOW",
            "WM_SHELL_BACK_PREVIEW",
            "WM_SHELL_BUBBLES",
            "WM_SHELL_PICTURE_IN_PICTURE",
            "WM_SHELL_SPLIT_SCREEN",
            "WM_SHELL_DESKTOP_MODE",
            "WM_SHELL_DRAG_AND_DROP",
            "WM_SHELL_FOLDABLE",
            "WM_SHELL_FLOATING_APPS",
            "WM_SHELL_SYSUI_EVENTS",
        )
}

private fun <T> failure(
    code: String,
    message: String,
    cause: Throwable? = null,
): StudioResult<T> = StudioResult.Failure(StudioError(ErrorCategory.PROCESS_EXIT, code, message, cause))
