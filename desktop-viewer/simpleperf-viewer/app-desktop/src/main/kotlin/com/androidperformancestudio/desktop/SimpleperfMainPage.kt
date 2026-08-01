@file:Suppress("MaxLineLength", "MagicNumber")

package com.androidperformancestudio.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.adb.AdbConfiguration
import com.androidperformancestudio.adb.AdbDeviceTargetGateway
import com.androidperformancestudio.adb.SystemAdbLocator
import com.androidperformancestudio.application.DeviceOption
import com.androidperformancestudio.application.DeviceSelection
import com.androidperformancestudio.application.DeviceTargetController
import com.androidperformancestudio.application.DeviceTargetGateway
import com.androidperformancestudio.application.OfflineProfileImporter
import com.androidperformancestudio.application.ReportController
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.ThreadOption
import com.androidperformancestudio.capture.CaptureSession
import com.androidperformancestudio.capture.CaptureState
import com.androidperformancestudio.capture.DeviceSimpleperfManager
import com.androidperformancestudio.capture.SimpleperfCaptureSession
import com.androidperformancestudio.export.ReportExportService
import com.androidperformancestudio.export.SessionPackageService
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.parser.HostSimpleperfLocator
import com.androidperformancestudio.parser.SimpleperfReportConverter
import com.androidperformancestudio.presentation.CaptureSettingsSection
import com.androidperformancestudio.presentation.DeviceTargetActions
import com.androidperformancestudio.presentation.HomeScreen
import com.androidperformancestudio.presentation.ReportActions
import com.androidperformancestudio.toolchain.SystemHostPlatformDetector
import com.androidperformancestudio.ui.UiLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path
import java.util.Locale

@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
fun FrameWindowScope.SimpleperfMainPage(
    window: ComposeWindow,
    settings: SimpleperfUiSettings = SimpleperfUiSettings(),
    androidSdkPath: Path? = null,
    onSettingsChanged: (SimpleperfUiSettings) -> Unit = {},
    onNavigateHome: (() -> Unit)? = null,
    onOpenPreferences: ((CaptureSettingsSection) -> Unit)? = null,
    onOpenUserGuide: (() -> Unit)? = null,
    onCaptureSettingsContextChanged: (SimpleperfCaptureSettingsContext?) -> Unit = {},
    aiAnalysisClient: SimpleperfAiAnalysisClient? = null,
    onOpenSourceCandidate: ((String) -> Unit)? = null,
) {
    var currentSettings by remember(settings) { mutableStateOf(settings) }
    val dependencies = remember(androidSdkPath) { createWorkspaceDependencies(androidSdkPath) }
    val controller =
        remember(dependencies) {
            DeviceTargetController(dependencies.deviceGateway, dependencies.captureSession)
        }
    val reportController = remember { ReportController() }
    DisposableEffect(reportController) {
        onDispose(reportController::close)
    }
    val sessionPackages = remember { SessionPackageService() }
    val reportExports = remember { ReportExportService() }
    val offlineImporter = remember { createOfflineImporter() }
    val firefoxProfilerLaunchers = remember { applicationFirefoxProfilerLaunchers }
    val selectedEngine by rememberUpdatedState(currentSettings.simpleperfEngine)
    val sessionOpener =
        remember(reportController, firefoxProfilerLaunchers) {
            SimpleperfSessionOpener(
                selectedEngine = { selectedEngine },
                openLocal = reportController::openSession,
                openLocalFirefoxProfiler = { session ->
                    reportController.openFirefoxProfiler(
                        session,
                        "FIREFOX_PROFILER_LOCAL_OPEN_FAILED",
                    ) {
                        firefoxProfilerLaunchers.local.open(session)
                    }
                },
                openOfficialFirefoxProfiler = { session ->
                    reportController.openFirefoxProfiler(
                        session,
                        "FIREFOX_PROFILER_OPEN_FAILED",
                    ) {
                        firefoxProfilerLaunchers.official.open(session)
                    }
                },
            )
        }
    val state by controller.state.collectAsState()
    val captureState by controller.captureState.collectAsState()
    val reportState by reportController.state.collectAsState()
    val scope = rememberCoroutineScope()
    var pendingAiReport by remember { mutableStateOf<com.androidperformancestudio.application.ReportData?>(null) }
    var aiAnalysisWorking by remember { mutableStateOf(false) }
    var aiAnalysisResult by remember { mutableStateOf<SimpleperfAiAnalysisReport?>(null) }
    var aiAnalysisError by remember { mutableStateOf<String?>(null) }
    val reportActionFactory =
        remember(reportController, sessionPackages, reportExports, sessionOpener, scope, window) {
            DesktopReportActionFactory(
                reportController,
                sessionPackages,
                reportExports,
                ::createOfflineImporter,
                sessionOpener::open,
                scope,
                window,
            )
        }
    val reportActions = reportActionFactory.create(reportState)
    val resolvedLanguage = currentSettings.language.resolve(Locale.getDefault())
    var captureSettingsSection by remember { mutableStateOf<CaptureSettingsSection?>(null) }
    val availableEvents =
        state.selection
            ?.capabilities
            ?.eventNames
            .orEmpty()
    val captureSettingsEnabled =
        !state.isLoading &&
            captureState !is CaptureState.Preparing &&
            captureState !is CaptureState.Recording &&
            captureState !is CaptureState.Stopping &&
            captureState !is CaptureState.Pulling
    val captureSettingsContext =
        remember(controller, state.captureSetup, availableEvents, captureSettingsEnabled) {
            SimpleperfCaptureSettingsContext(
                setup = state.captureSetup,
                availableEvents = availableEvents,
                enabled = captureSettingsEnabled,
                onSelectTemplate = controller::selectSamplingTemplate,
                onUpdateSamplingParameters = controller::updateSamplingParameters,
            )
        }
    val currentOnCaptureSettingsContextChanged by rememberUpdatedState(onCaptureSettingsContextChanged)
    LaunchedEffect(captureSettingsContext) {
        currentOnCaptureSettingsContextChanged(captureSettingsContext)
    }
    DisposableEffect(Unit) {
        onDispose { currentOnCaptureSettingsContextChanged(null) }
    }
    LaunchedEffect(controller) { controller.refreshDevices() }
    SimpleperfMenu(
        reportState,
        reportActions,
        sessionOpener::open,
        scope,
        language = resolvedLanguage,
        onOpenCaptureSettings = { section ->
            onOpenPreferences?.invoke(section) ?: run { captureSettingsSection = section }
        },
        onOpenPreferences = onOpenPreferences,
    )
    HomeScreen(
        state = state,
        captureState = captureState,
        reportState = reportState,
        actions =
            controller.deviceActions(
                scope,
                reportController,
                offlineImporter,
                sessionOpener::open,
            ),
        reportActions = reportActions,
        darkTheme = currentSettings.theme.resolveDark(isSystemInDarkTheme()),
        language = resolvedLanguage,
        captureSettingsSection = captureSettingsSection,
        captureSettingsManagedExternally = onOpenPreferences != null,
        onCaptureSettingsSectionChange = { section ->
            if (section != null && onOpenPreferences != null) {
                onOpenPreferences(section)
            } else {
                captureSettingsSection = section
            }
        },
        flameTooltipMode = currentSettings.flameTooltipMode,
        onFlameTooltipModeChange = {
            currentSettings = currentSettings.copy(flameTooltipMode = it)
            onSettingsChanged(currentSettings)
        },
        simpleperfEngine = currentSettings.simpleperfEngine,
        onSimpleperfEngineChange = {
            currentSettings = currentSettings.copy(simpleperfEngine = it)
            onSettingsChanged(currentSettings)
        },
        onOpenUserGuide = onOpenUserGuide,
        onNavigateHome = onNavigateHome,
        onRunAiAnalysis =
            aiAnalysisClient?.let {
                {
                    (reportState.loadState as? ReportLoadState.Ready)?.report?.let { report ->
                        pendingAiReport = report
                    }
                }
            },
    )

    pendingAiReport?.let { report ->
        var performanceOnly by remember(report) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pendingAiReport = null },
            title = { Text("Run Simpleperf AI Analysis") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Scope: ${selectedSimpleperfScope(reportState, report)}\n" +
                            "Evidence: ${report.topFunctions.size.coerceAtMost(20)} hotspot(s), ${report.overview.sampleCount} samples\n" +
                            "Only deterministic source candidates and minimal snippets may be sent.",
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = performanceOnly, onCheckedChange = { performanceOnly = it })
                        Text("Performance data only")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !aiAnalysisWorking,
                    onClick = {
                        pendingAiReport = null
                        aiAnalysisWorking = true
                        aiAnalysisError = null
                        scope.launch {
                            try {
                                aiAnalysisResult =
                                    withContext(Dispatchers.IO) {
                                        requireNotNull(aiAnalysisClient).analyze(report, reportState, !performanceOnly)
                                    }
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (failure: Throwable) {
                                aiAnalysisError = failure.message ?: failure::class.simpleName
                            } finally {
                                aiAnalysisWorking = false
                            }
                        }
                    },
                ) { Text("Analyze") }
            },
            dismissButton = { TextButton(onClick = { pendingAiReport = null }) { Text("Cancel") } },
        )
    }
    aiAnalysisResult?.let { result ->
        AlertDialog(
            onDismissRequest = { aiAnalysisResult = null },
            title = { Text("AI Analysis · ${result.model}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(result.summary)
                    result.findings.take(6).forEach { finding ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${finding.title} (${(finding.confidence * 100).toInt()}%)")
                            Text(finding.explanation)
                            Text(finding.recommendation)
                            finding.sourceCandidateIds.forEachIndexed { index, candidateId ->
                                TextButton(onClick = { onOpenSourceCandidate?.invoke(candidateId) }) {
                                    Text(if (finding.sourceCandidateIds.size == 1) "Open Source" else "Open Source Candidate ${index + 1}")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { aiAnalysisResult = null }) { Text("Close") } },
        )
    }
    aiAnalysisError?.let { message ->
        AlertDialog(
            onDismissRequest = { aiAnalysisError = null },
            title = { Text("AI Analysis Failed") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { aiAnalysisError = null }) { Text("Close") } },
        )
    }
}

@Suppress("ReturnCount")
private fun selectedSimpleperfScope(
    state: ReportState,
    report: com.androidperformancestudio.application.ReportData,
): String {
    state.workspace.selections.topFunctionKey
        ?.let { return "function $it" }
    state.workspace.selections.callNodeId?.let { nodeId ->
        val index = report.flameGraph.callNodes.indexOf(nodeId)
        report.flameGraph.callNodes
            .frameAt(index ?: -1)
            ?.symbolName
            ?.let { return "call node $it" }
    }
    return "report summary"
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun FrameWindowScope.SimpleperfMenu(
    reportState: ReportState,
    reportActions: ReportActions,
    sessionOpener: suspend (Path) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    language: UiLanguage,
    onOpenCaptureSettings: (CaptureSettingsSection) -> Unit,
    onOpenPreferences: ((CaptureSettingsSection) -> Unit)?,
) {
    val recentSessionStore = remember { RecentSimpleperfSessionStore.desktop() }
    var recentSessions by remember { mutableStateOf(recentSessionStore.load()) }
    val readySession = reportState.lastReadyReport?.session?.directory
    LaunchedEffect(readySession) {
        readySession?.let { recentSessions = recentSessionStore.record(it) }
    }
    SimpleperfFileMenuBar(
        model =
            simpleperfFileMenuModel(
                language = language,
                recentSessions = recentSessions,
                exportEnabled = reportState.loadState is ReportLoadState.Ready,
                isMacOs = System.getProperty("os.name").startsWith("Mac", ignoreCase = true),
                configurationEnabled = reportState.loadState == ReportLoadState.Closed,
            ),
        onOpen = reportActions.onOpenSession,
        exportActions =
            SimpleperfExportMenuActions(
                onSessionPackage = reportActions.onExportSession,
                onReport = reportActions.onExportReport,
                onGeckoProfile = reportActions.onExportGeckoProfile,
                onRawProtobuf = reportActions.onExportRawProtobuf,
                onScreenshot = reportActions.onExportScreenshot,
                onSimpleperfReport = reportActions.onGenerateSimpleperfReport,
                onHtmlReport = reportActions.onGenerateHtmlReport,
                onExternalOpen = reportActions.onExportExternalGuide,
            ),
        onOpenRecent = { session -> scope.launch { sessionOpener(session) } },
        onClearRecent = {
            recentSessionStore.clear()
            recentSessions = emptyList()
        },
        onOpenSettings = {
            onOpenPreferences?.invoke(CaptureSettingsSection.FLAME_GRAPH)
                ?: onOpenCaptureSettings(CaptureSettingsSection.SAMPLING_TEMPLATE)
        },
        onOpenCaptureSettings = onOpenCaptureSettings,
    )
}

private fun DeviceTargetController.deviceActions(
    scope: kotlinx.coroutines.CoroutineScope,
    reportController: ReportController,
    offlineImporter: OfflineProfileImporter,
    sessionOpener: suspend (Path) -> Unit,
): DeviceTargetActions =
    DeviceTargetActions(
        onRefresh = { scope.launch { refreshDevices() } },
        onSelectDevice = { serial -> scope.launch { selectDevice(serial) } },
        onSearch = ::updateSearch,
        onSelectPackage = ::selectPackage,
        onSelectProcess = { pid -> scope.launch { selectProcess(pid) } },
        onSelectThread = ::selectThread,
        onContinue = ::enterCapture,
        onBack = ::backToTargets,
        onSelectTemplate = ::selectSamplingTemplate,
        onUpdateSamplingParameters = ::updateSamplingParameters,
        onStartCapture = {
            scope.launch {
                when (val captured = startCapture()) {
                    is CaptureState.Completed ->
                        when (val imported = offlineImporter.importCapturedSession(captured.sessionDirectory)) {
                            is StudioResult.Success -> sessionOpener(imported.value.sessionDirectory)
                            is StudioResult.Failure ->
                                reportController.showFailure(captured.sessionDirectory, imported.error)
                        }
                    else -> Unit
                }
            }
        },
        onStopCapture = { scope.launch { stopCapture() } },
        onCancelCapture = cancelCapture,
    )

private data class WorkspaceDependencies(
    val deviceGateway: DeviceTargetGateway,
    val captureSession: CaptureSession?,
)

private data class FirefoxProfilerLaunchers(
    val local: LocalFirefoxProfilerLauncher,
    val official: OfficialFirefoxProfilerLauncher,
)

private val applicationFirefoxProfilerLaunchers by lazy {
    FirefoxProfilerLaunchers(LocalFirefoxProfilerLauncher(), OfficialFirefoxProfilerLauncher())
}

private suspend fun ReportController.openFirefoxProfiler(
    sessionDirectory: Path,
    errorCode: String,
    open: suspend () -> Path,
) {
    try {
        open()
    } catch (exception: FirefoxProfilerLaunchException) {
        showFailure(
            sessionDirectory,
            StudioError(
                category = ErrorCategory.IO,
                code = errorCode,
                message = exception.message ?: "Failed to open Firefox Profiler",
                cause = exception,
            ),
        )
    }
}

private fun createWorkspaceDependencies(androidSdkPath: Path? = null): WorkspaceDependencies {
    val platform = SystemHostPlatformDetector().detect()
    if (platform is StudioResult.Failure) {
        return WorkspaceDependencies(UnavailableDeviceTargetGateway(platform), null)
    }
    val location =
        SystemAdbLocator((platform as StudioResult.Success).value).locate(
            AdbConfiguration(androidSdkPath = androidSdkPath),
        )
    return when (location) {
        is StudioResult.Success -> {
            val adbExecutable = location.value.executable
            val bundledSimpleperfAssets = loadBundledDeviceSimpleperfAssets()
            val preparer = DeviceSimpleperfManager(adbExecutable, bundledSimpleperfAssets)
            WorkspaceDependencies(
                deviceGateway =
                    AdbDeviceTargetGateway(
                        adbExecutable,
                        bundledSimpleperfAssets.mapTo(mutableSetOf()) { it.abi },
                    ),
                captureSession = SimpleperfCaptureSession(adbExecutable, preparer),
            )
        }
        is StudioResult.Failure -> WorkspaceDependencies(UnavailableDeviceTargetGateway(location), null)
    }
}

private fun createOfflineImporter(configuredSimpleperf: Path? = null): OfflineProfileImporter {
    val pathDirectories =
        System
            .getenv("PATH")
            .orEmpty()
            .split(File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .map(Path::of)
    return OfflineProfileImporter(
        HostSimpleperfLocator(
            configuredExecutable = configuredSimpleperf ?: findAndroidNdkSimpleperf(),
            bundledExecutable = null,
            pathDirectories = pathDirectories,
        ),
        SimpleperfReportConverter(),
    )
}

private class UnavailableDeviceTargetGateway(
    private val failure: StudioResult.Failure,
) : DeviceTargetGateway {
    override suspend fun refreshDevices(): StudioResult<List<DeviceOption>> = failure

    override suspend fun loadSelection(serial: String): StudioResult<DeviceSelection> = failure

    override suspend fun loadThreads(
        serial: String,
        pid: Int,
    ): StudioResult<List<ThreadOption>> = failure
}
