package dev.agentperf.desktop

import com.androidperformancestudio.ui.localizedStringResource
import dev.agentperf.desktop_app.generated.resources.Res
import dev.agentperf.desktop_app.generated.resources.*

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import com.androidperformancestudio.desktop.SimpleperfLanguagePreference
import com.androidperformancestudio.desktop.SimpleperfCaptureSettingsContext
import com.androidperformancestudio.desktop.SimpleperfThemePreference
import com.androidperformancestudio.desktop.SimpleperfUiSettings
import com.androidperformancestudio.desktop.SimpleperfMainPage
import com.androidperformancestudio.battery.app.BatteryProfilerMainPage
import com.androidperformancestudio.benchmark.app.BenchmarkRegressionMainPage
import com.androidperformancestudio.frame.app.FrameProfilerMainPage
import com.androidperformancestudio.gpu.app.GpuIntegrationMainPage
import com.androidperformancestudio.memory.app.MemoryProfilerMainPage
import com.androidperformancestudio.network.app.NetworkProfilerMainPage
import com.androidperformancestudio.perfetto.app.PerfettoMainPage
import com.androidperformancestudio.presentation.CaptureSettingsSection
import com.androidperformancestudio.startup.app.StartupProfilerMainPage
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
public fun FrameWindowScope.DesktopAppMainPage(settingsRequest: SettingsRequest? = null) {
    val navigator = remember { AppNavigator() }
    var showSettings by remember { mutableStateOf(false) }
    var settingsPage by remember { mutableStateOf(SettingsPage.GENERAL) }
    var layoutInspectorSettingsRevision by remember { mutableStateOf(0L) }
    var simpleperfCaptureSettingsContext by remember {
        mutableStateOf<SimpleperfCaptureSettingsContext?>(null)
    }
    var simpleperfSettingsSection by remember {
        mutableStateOf(CaptureSettingsSection.SAMPLING_TEMPLATE)
    }
    var memoryHighlightClassName by remember { mutableStateOf<String?>(null) }
    val applicationSettingsStore = remember { ApplicationUiSettingsStore.desktop() }
    val simpleperfPreferencesStore = remember { SimpleperfPreferencesStore.desktop() }
    val externalAnalysisLauncher = remember { ExternalAnalysisLauncher() }
    val userDocumentationLauncher = remember { UserDocumentationLauncher() }
    val coroutineScope = rememberCoroutineScope()
    var applicationSettings by remember { mutableStateOf(applicationSettingsStore.load()) }
    var simpleperfPreferences by remember { mutableStateOf(simpleperfPreferencesStore.load()) }
    var settingsPersistenceErrorPage by remember { mutableStateOf<SettingsPage?>(null) }
    val updateApplicationSettings: (ApplicationUiSettings) -> Unit = { updated ->
        applicationSettings = updated
        settingsPersistenceErrorPage =
            if (applicationSettingsStore.save(updated)) null else SettingsPage.GENERAL
    }
    val updateSimpleperfPreferences: (SimpleperfUiSettings) -> Unit = { updated ->
        simpleperfPreferences = updated
        settingsPersistenceErrorPage =
            if (simpleperfPreferencesStore.save(updated)) null else SettingsPage.SIMPLEPERF
    }
    val openSettings: (SettingsPage) -> Unit = { page ->
        settingsPage = page
        showSettings = true
    }
    val chinese =
        applicationSettings.language.resolve(Locale.getDefault()) == ApplicationLanguage.SIMPLIFIED_CHINESE
    val darkTheme = applicationSettings.theme.resolveDark(isSystemInDarkTheme())
    val simpleperfSettings =
        SimpleperfUiSettings(
            theme = SimpleperfThemePreference.parse(applicationSettings.theme.storageValue),
            language = SimpleperfLanguagePreference.parse(applicationSettings.language.storageValue),
            flameTooltipMode = simpleperfPreferences.flameTooltipMode,
            simpleperfEngine = simpleperfPreferences.simpleperfEngine,
        )

    LaunchedEffect(settingsRequest?.requestId) {
        if (shouldOpenSettingsForRequest(settingsRequest)) {
            settingsRequest?.let { openSettings(it.page) }
        }
    }
    DisposableEffect(userDocumentationLauncher) {
        onDispose(userDocumentationLauncher::close)
    }
    LaunchedEffect(navigator.destination) {
        if (navigator.destination.shouldMaximizeWindow()) {
            window.placement = WindowPlacement.Maximized
        }
    }

    MaterialTheme(
        colorScheme = viewerMaterialColorScheme(darkTheme),
        typography = compactDesktopTypography(),
        shapes = compactDesktopShapes(),
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                when (navigator.destination) {
                    AppDestination.HOME ->
                        AppHomePage(
                            chinese = chinese,
                            onOpenLayoutInspector = { navigator.open(AppDestination.LAYOUT_INSPECTOR) },
                            onOpenSimpleperf = { navigator.open(AppDestination.SIMPLEPERF) },
                            onOpenPerfetto = { navigator.open(AppDestination.PERFETTO) },
                            onOpenMemoryProfiler = { navigator.open(AppDestination.MEMORY_PROFILER) },
                            onOpenFrameProfiler = { navigator.open(AppDestination.FRAME_PROFILER) },
                            onOpenStartupProfiler = { navigator.open(AppDestination.STARTUP_PROFILER) },
                            onOpenBatteryProfiler = { navigator.open(AppDestination.BATTERY_PROFILER) },
                            onOpenNetworkProfiler = { navigator.open(AppDestination.NETWORK_PROFILER) },
                            onOpenGpuInspector = { navigator.open(AppDestination.GPU_INSPECTOR) },
                            onOpenBenchmarkRegression = { navigator.open(AppDestination.BENCHMARK_REGRESSION) },
                        )
                    AppDestination.LAYOUT_INSPECTOR ->
                        LayoutInspectorMainPage(
                            commonThemePreference = applicationSettings.theme.storageValue,
                            commonLanguagePreference = applicationSettings.language.storageValue,
                            settingsRevision = layoutInspectorSettingsRevision,
                            onNavigateHome = { navigator.open(AppDestination.HOME) },
                            onOpenUnifiedSettings = {
                                openSettings(SettingsPage.LAYOUT_INSPECTOR)
                            },
                            onOpenMemoryProfiler = { className ->
                                memoryHighlightClassName = className
                                navigator.open(AppDestination.MEMORY_PROFILER)
                            },
                            correlationHint = navigator.inspectorCorrelationHint,
                        )
                    AppDestination.SIMPLEPERF ->
                        SimpleperfMainPage(
                            window = window,
                            settings = simpleperfSettings,
                            androidSdkPath = applicationSettings.androidSdkPath?.let { path -> runCatching { java.nio.file.Path.of(path) }.getOrNull() },
                            onSettingsChanged = updateSimpleperfPreferences,
                            onNavigateHome = { navigator.open(AppDestination.HOME) },
                            onOpenPreferences = { section ->
                                simpleperfSettingsSection = section
                                openSettings(SettingsPage.SIMPLEPERF)
                            },
                            onCaptureSettingsContextChanged = { simpleperfCaptureSettingsContext = it },
                            onOpenUserGuide = {
                                val lang =
                                    if (chinese) {
                                        UserDocumentationLanguage.SIMPLIFIED_CHINESE
                                    } else {
                                        UserDocumentationLanguage.ENGLISH
                                    }
                                coroutineScope.launch(Dispatchers.IO) {
                                    runCatching { userDocumentationLauncher.open(lang) }
                                }
                            },
                        )
                    AppDestination.PERFETTO ->
                        PerfettoMainPage(
                            chinese = chinese,
                            onNavigateHome = { navigator.open(AppDestination.HOME) },
                            initialTraceFile = navigator.perfettoTraceFile,
                            initialTraceNotice = navigator.perfettoTraceNotice,
                            onOpenUserGuide = {
                                val lang =
                                    if (chinese) {
                                        UserDocumentationLanguage.SIMPLIFIED_CHINESE
                                    } else {
                                        UserDocumentationLanguage.ENGLISH
                                    }
                                coroutineScope.launch(Dispatchers.IO) {
                                    runCatching { userDocumentationLauncher.open(lang) }
                                }
                            },
                        )
                    AppDestination.MEMORY_PROFILER ->
                        MemoryProfilerMainPage(
                            chinese = chinese,
                            highlightClassName = memoryHighlightClassName,
                            onBack = { navigator.open(AppDestination.HOME) },
                        )
                    AppDestination.FRAME_PROFILER ->
                        FrameProfilerMainPage(
                            chinese = chinese,
                            onBack = { navigator.open(AppDestination.HOME) },
                            onOpenLayoutInspector = { request ->
                                val activity =
                                    request.activityName
                                        ?.substringAfterLast('.')
                                        ?.let { " · $it" }
                                        .orEmpty()
                                val message =
                                    localizedStringResource(Res.string.layout_correlation_from_frame, chinese, request.frameId, request.packageName, activity)
                                navigator.openLayoutInspector(
                                    InspectorCorrelationHint(
                                        deviceSerial = request.deviceSerial,
                                        targetPackageName = request.packageName,
                                        message = message,
                                        correlationNotice =
                                            localizedStringResource(Res.string.correlation_only_no_view_causality_is_inferred, chinese),
                                        foregroundMismatchPrefix =
                                            localizedStringResource(Res.string.foreground_package_differs, chinese),
                                    ),
                                )
                            },
                        )
                    AppDestination.STARTUP_PROFILER ->
                        StartupProfilerMainPage(
                            chinese = chinese,
                            onBack = { navigator.open(AppDestination.HOME) },
                        )
                    AppDestination.BATTERY_PROFILER ->
                        BatteryProfilerMainPage(
                            chinese = chinese,
                            onBack = { navigator.open(AppDestination.HOME) },
                        )
                    AppDestination.NETWORK_PROFILER ->
                        NetworkProfilerMainPage(
                            chinese = chinese,
                            onBack = { navigator.open(AppDestination.HOME) },
                        )
                    AppDestination.GPU_INSPECTOR ->
                        GpuIntegrationMainPage(
                            chinese = chinese,
                            onBack = { navigator.open(AppDestination.HOME) },
                            onOpenTrace = { path ->
                                navigator.openPerfettoTrace(
                                    path,
                                    localizedStringResource(
                                        Res.string.opened_from_tool_for_correlation_only,
                                        chinese,
                                        localizedStringResource(Res.string.gpu_inspector, chinese),
                                    ),
                                )
                            },
                        )
                    AppDestination.BENCHMARK_REGRESSION ->
                        BenchmarkRegressionMainPage(
                            chinese = chinese,
                            onBack = { navigator.open(AppDestination.HOME) },
                            onOpenTrace = { path ->
                                navigator.openPerfettoTrace(
                                    path,
                                    localizedStringResource(
                                        Res.string.opened_from_tool_for_correlation_only,
                                        chinese,
                                        localizedStringResource(Res.string.benchmark_regression, chinese),
                                    ),
                                )
                            },
                        )
                }
                if (showSettings) {
                    DesktopAppSettingsDialog(
                        selectedPage = settingsPage,
                        applicationSettings = applicationSettings,
                        simpleperfSettings = simpleperfSettings,
                        simpleperfCaptureSettingsContext = simpleperfCaptureSettingsContext,
                        simpleperfInitialSection = simpleperfSettingsSection,
                        darkTheme = darkTheme,
                        chinese = chinese,
                        simpleperfLocale = if (chinese) java.util.Locale.SIMPLIFIED_CHINESE else java.util.Locale.ENGLISH,
                        onPageSelected = { settingsPage = it },
                        onApplicationSettingsChanged = updateApplicationSettings,
                        onSimpleperfSettingsChanged = updateSimpleperfPreferences,
                        onLayoutInspectorSettingsChanged = { layoutInspectorSettingsRevision += 1 },
                        onOpenUserGuide = {
                            val lang =
                                if (chinese) {
                                    UserDocumentationLanguage.SIMPLIFIED_CHINESE
                                } else {
                                    UserDocumentationLanguage.ENGLISH
                                }
                            coroutineScope.launch(Dispatchers.IO) {
                                runCatching { userDocumentationLauncher.open(lang) }
                            }
                        },
                        persistenceErrorPage = settingsPersistenceErrorPage,
                        onDismiss = { showSettings = false },
                    )
                }
            }
        }
    }
}
