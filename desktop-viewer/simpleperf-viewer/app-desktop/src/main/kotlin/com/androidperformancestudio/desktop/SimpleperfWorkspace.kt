package com.androidperformancestudio.desktop

import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.awt.ComposeWindow
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
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Path
import java.util.Locale
import com.androidperformancestudio.presentation.SimpleperfLanguage as PresentationLanguage

@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList")
fun FrameWindowScope.SimpleperfWorkspace(
    window: ComposeWindow,
    settings: SimpleperfUiSettings = SimpleperfUiSettings(),
    onSettingsChanged: (SimpleperfUiSettings) -> Unit = {},
    onNavigateHome: (() -> Unit)? = null,
    onOpenPreferences: ((CaptureSettingsSection) -> Unit)? = null,
    onOpenUserGuide: (() -> Unit)? = null,
    onCaptureSettingsContextChanged: (SimpleperfCaptureSettingsContext?) -> Unit = {},
) {
    var currentSettings by remember(settings) { mutableStateOf(settings) }
    val dependencies = remember { createWorkspaceDependencies() }
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
        resolvedLanguage,
        reportState,
        reportActions,
        sessionOpener::open,
        scope,
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
        language = resolvedLanguage.toPresentationLanguage(),
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
    )
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun FrameWindowScope.SimpleperfMenu(
    language: SimpleperfLanguage,
    reportState: ReportState,
    reportActions: ReportActions,
    sessionOpener: suspend (Path) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
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
            SimpleperfFileMenuModel(
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

private fun SimpleperfLanguage.toPresentationLanguage(): PresentationLanguage =
    when (this) {
        SimpleperfLanguage.SIMPLIFIED_CHINESE -> PresentationLanguage.SIMPLIFIED_CHINESE
        SimpleperfLanguage.ENGLISH -> PresentationLanguage.ENGLISH
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

private fun createWorkspaceDependencies(): WorkspaceDependencies {
    val platform = SystemHostPlatformDetector().detect()
    if (platform is StudioResult.Failure) {
        return WorkspaceDependencies(UnavailableDeviceTargetGateway(platform), null)
    }
    val location = SystemAdbLocator((platform as StudioResult.Success).value).locate(AdbConfiguration())
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
