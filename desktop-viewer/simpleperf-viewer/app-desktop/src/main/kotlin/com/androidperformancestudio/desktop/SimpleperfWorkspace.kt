package com.androidperformancestudio.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.awt.ComposeWindow
import com.androidperformancestudio.adb.AdbConfiguration
import com.androidperformancestudio.adb.AdbDeviceTargetGateway
import com.androidperformancestudio.adb.SystemAdbLocator
import com.androidperformancestudio.application.DeviceOption
import com.androidperformancestudio.application.DeviceSelection
import com.androidperformancestudio.application.DeviceTargetController
import com.androidperformancestudio.application.DeviceTargetGateway
import com.androidperformancestudio.application.OfflineProfileImporter
import com.androidperformancestudio.application.ReportController
import com.androidperformancestudio.application.ThreadOption
import com.androidperformancestudio.capture.CaptureSession
import com.androidperformancestudio.capture.CaptureState
import com.androidperformancestudio.capture.DeviceSimpleperfManager
import com.androidperformancestudio.capture.SimpleperfCaptureSession
import com.androidperformancestudio.export.ReportExportService
import com.androidperformancestudio.export.SessionPackageService
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.parser.HostSimpleperfLocator
import com.androidperformancestudio.parser.SimpleperfReportConverter
import com.androidperformancestudio.presentation.DeviceTargetActions
import com.androidperformancestudio.presentation.HomeScreen
import com.androidperformancestudio.toolchain.SystemHostPlatformDetector
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Path
import java.util.Locale
import com.androidperformancestudio.presentation.SimpleperfLanguage as PresentationLanguage

@Composable
@Suppress("FunctionName")
fun SimpleperfWorkspace(
    window: ComposeWindow,
    settings: SimpleperfUiSettings = SimpleperfUiSettings(),
) {
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
    val state by controller.state.collectAsState()
    val captureState by controller.captureState.collectAsState()
    val reportState by reportController.state.collectAsState()
    val scope = rememberCoroutineScope()
    val reportActionFactory =
        remember(reportController, sessionPackages, reportExports, scope, window) {
            DesktopReportActionFactory(
                reportController,
                sessionPackages,
                reportExports,
                ::createOfflineImporter,
                scope,
                window,
            )
        }
    LaunchedEffect(controller) { controller.refreshDevices() }
    HomeScreen(
        state = state,
        captureState = captureState,
        reportState = reportState,
        actions = controller.deviceActions(scope, reportController, offlineImporter),
        reportActions = reportActionFactory.create(reportState),
        darkTheme = settings.theme.resolveDark(isSystemInDarkTheme()),
        language = settings.language.resolve(Locale.getDefault()).toPresentationLanguage(),
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
                            is StudioResult.Success -> reportController.openSession(imported.value.sessionDirectory)
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

private fun createWorkspaceDependencies(): WorkspaceDependencies {
    val platform = SystemHostPlatformDetector().detect()
    if (platform is StudioResult.Failure) {
        return WorkspaceDependencies(UnavailableDeviceTargetGateway(platform), null)
    }
    val location = SystemAdbLocator((platform as StudioResult.Success).value).locate(AdbConfiguration())
    return when (location) {
        is StudioResult.Success -> {
            val adbExecutable = location.value.executable
            val preparer = DeviceSimpleperfManager(adbExecutable, assets = emptyList())
            WorkspaceDependencies(
                deviceGateway = AdbDeviceTargetGateway(adbExecutable),
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
