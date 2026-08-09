package com.androidperformancestudio.desktop

import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.desktop_app.generated.resources.Res
import com.androidperformancestudio.desktop_app.generated.resources.*

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
import com.androidperformancestudio.methodrecording.app.MethodRecordingMainPage
import com.androidperformancestudio.network.app.NetworkProfilerMainPage
import com.androidperformancestudio.perfetto.app.PerfettoMainPage
import com.androidperformancestudio.presentation.CaptureSettingsSection
import com.androidperformancestudio.startup.app.StartupProfilerMainPage
import com.androidperformancestudio.analysis.AiSourceCandidateReference
import com.androidperformancestudio.source.ResolutionCandidate
import com.androidperformancestudio.source.ResolutionConfidence
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
    var composeSourceCandidates by remember { mutableStateOf<List<ResolutionCandidate>>(emptyList()) }
    var archivedSourceToRebind by remember { mutableStateOf<AiSourceCandidateReference?>(null) }
    val applicationSettingsStore = remember { ApplicationUiSettingsStore.desktop() }
    val simpleperfPreferencesStore = remember { SimpleperfPreferencesStore.desktop() }
    val externalAnalysisLauncher = remember { ExternalAnalysisLauncher() }
    val userDocumentationLauncher = remember { UserDocumentationLauncher() }
    val sourceWorkspaceRuntime = remember { SourceWorkspaceRuntime.desktop() }
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
    val language = applicationSettings.language.resolve(Locale.getDefault())
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
    DisposableEffect(sourceWorkspaceRuntime) {
        onDispose(sourceWorkspaceRuntime::close)
    }
    LaunchedEffect(navigator.destination) {
        if (navigator.destination.shouldMaximizeWindow()) {
            window.placement = WindowPlacement.Maximized
        }
        if (navigator.destination != AppDestination.PERFETTO) {
            navigator.clearPerfettoTrace()
        }
    }

    ViewerTheme(
        darkTheme = darkTheme,
        typography = compactDesktopTypography(),
        shapes = compactDesktopShapes(),
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                navigator.retainedDestinations.forEach { destination ->
                    key(destination) {
                        val active = destination == navigator.destination
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .zIndex(if (active) 1f else 0f)
                                    .alpha(if (active) 1f else 0f)
                                    .then(if (active) Modifier else Modifier.clearAndSetSemantics {}),
                        ) {
                when (destination) {
                    AppDestination.HOME ->
                        AppHomePage(
                            language = language,
                            onOpenSourceWorkspaces = { navigator.open(AppDestination.SOURCE_WORKSPACES) },
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
                            onOpenMethodRecording = { navigator.open(AppDestination.METHOD_RECORDING) },
                        )
                    AppDestination.SOURCE_WORKSPACES ->
                        SourceWorkspacesPage(
                            language = language,
                            runtime = sourceWorkspaceRuntime,
                            initialLocation = navigator.sourceLocation,
                            onNavigateHome = { navigator.open(AppDestination.HOME) },
                            onOpenAiSettings = { openSettings(SettingsPage.AI) },
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
                            aiAnalysisClient = remember(sourceWorkspaceRuntime) {
                                SourceAwareLayoutAiAnalysisClient(sourceWorkspaceRuntime)
                            },
                            onOpenSourceCandidate = { candidateId, archived ->
                                val current = sourceWorkspaceRuntime.candidate(candidateId)
                                    ?.takeIf { it.confidence != ResolutionConfidence.WEAK }
                                current?.location?.let(navigator::openSource)
                                    ?: run {
                                        archivedSourceToRebind = archived?.takeIf { it.resolutionConfidence != "WEAK" }
                                    }
                            },
                            onCanOpenSourceCandidate = { candidateId ->
                                sourceWorkspaceRuntime.candidate(candidateId)?.confidence
                                    ?.let { it != ResolutionConfidence.WEAK } == true
                            },
                            onCanOpenSourceCandidateDirectly = { candidateId ->
                                sourceWorkspaceRuntime.candidate(candidateId)?.confidence ==
                                    com.androidperformancestudio.source.ResolutionConfidence.EXACT
                            },
                            onOpenComposeSource = { fileName, packageHash, line ->
                                coroutineScope.launch {
                                    val candidates = sourceWorkspaceRuntime.resolveComposeSources(fileName, packageHash, line)
                                    if (candidates.size == 1 && candidates.single().confidence == ResolutionConfidence.EXACT) {
                                        navigator.openSource(candidates.single().location)
                                    } else {
                                        composeSourceCandidates = candidates
                                    }
                                }
                            },
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
                                coroutineScope.launch(Dispatchers.IO) {
                                    runCatching { userDocumentationLauncher.open(language) }
                                }
                            },
                            aiAnalysisClient = null,
                            onOpenSourceCandidate = { candidateId ->
                                sourceWorkspaceRuntime.candidate(candidateId)?.let { candidate ->
                                    navigator.openSource(candidate.location)
                                }
                            },
                        )
                    AppDestination.PERFETTO ->
                        PerfettoMainPage(
                            language = language,
                            darkTheme = darkTheme,
                            isActive = active,
                            onNavigateHome = { navigator.open(AppDestination.HOME) },
                            initialTraceFile = navigator.perfettoTraceFile,
                            initialTraceNotice = navigator.perfettoTraceNotice,
                            onOpenUserGuide = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    runCatching { userDocumentationLauncher.open(language) }
                                }
                            },
                        )
                    AppDestination.MEMORY_PROFILER ->
                        MemoryProfilerMainPage(
                            language = language,
                            darkTheme = darkTheme,
                            highlightClassName = memoryHighlightClassName,
                            initialImportFile = navigator.memoryImportFile,
                            initialImportIsJavaHeap = navigator.memoryImportIsJavaHeap,
                            onBack = { navigator.open(AppDestination.HOME) },
                        )
                    AppDestination.FRAME_PROFILER ->
                        FrameProfilerMainPage(
                            language = language,
                            darkTheme = darkTheme,
                            onBack = { navigator.open(AppDestination.HOME) },
                            onOpenLayoutInspector = { request ->
                                val activity =
                                    request.activityName
                                        ?.substringAfterLast('.')
                                        ?.let { " · $it" }
                                        .orEmpty()
                                val message =
                                    localizedStringResource(Res.string.layout_correlation_from_frame, language, request.frameId, request.packageName, activity)
                                navigator.openLayoutInspector(
                                    InspectorCorrelationHint(
                                        deviceSerial = request.deviceSerial,
                                        targetPackageName = request.packageName,
                                        message = message,
                                        correlationNotice =
                                            localizedStringResource(Res.string.correlation_only_no_view_causality_is_inferred, language),
                                        foregroundMismatchPrefix =
                                            localizedStringResource(Res.string.foreground_package_differs, language),
                                    ),
                                )
                            },
                            onOpenPerfetto = { request ->
                                val correlation =
                                    request.frameTimelineVsyncId?.let { "FrameTimeline VSync ID $it" }
                                        ?: request.intendedVsyncNs?.let { "intended VSync $it ns" }
                                        ?: "frame ${request.frameId ?: "—"}"
                                navigator.openPerfettoTrace(
                                    request.traceFile,
                                    localizedStringResource(Res.string.frame_perfetto_correlation, language, correlation),
                                )
                            },
                        )
                    AppDestination.STARTUP_PROFILER ->
                        StartupProfilerMainPage(
                            language = language,
                            darkTheme = darkTheme,
                            onBack = { navigator.open(AppDestination.HOME) },
                        )
                    AppDestination.BATTERY_PROFILER ->
                        BatteryProfilerMainPage(
                            language = language,
                            darkTheme = darkTheme,
                            onBack = { navigator.open(AppDestination.HOME) },
                        )
                    AppDestination.NETWORK_PROFILER ->
                        NetworkProfilerMainPage(
                            language = language,
                            darkTheme = darkTheme,
                            onBack = { navigator.open(AppDestination.HOME) },
                        )
                    AppDestination.GPU_INSPECTOR ->
                        GpuIntegrationMainPage(
                            language = language,
                            darkTheme = darkTheme,
                            onBack = { navigator.open(AppDestination.HOME) },
                            onOpenTrace = { path ->
                                navigator.openPerfettoTrace(
                                    path,
                                    localizedStringResource(
                                        Res.string.opened_from_tool_for_correlation_only,
                                        language,
                                        localizedStringResource(Res.string.gpu_inspector, language),
                                    ),
                                )
                            },
                        )
                    AppDestination.BENCHMARK_REGRESSION ->
                        BenchmarkRegressionMainPage(
                            language = language,
                            darkTheme = darkTheme,
                            onBack = { navigator.open(AppDestination.HOME) },
                            onOpenTrace = { path ->
                                navigator.openPerfettoTrace(
                                    path,
                                    localizedStringResource(
                                        Res.string.opened_from_tool_for_correlation_only,
                                        language,
                                        localizedStringResource(Res.string.benchmark_regression, language),
                                    ),
                                )
                            },
                        )
                    AppDestination.METHOD_RECORDING ->
                        MethodRecordingMainPage(
                            language = language,
                            darkTheme = darkTheme,
                            androidSdkPath = applicationSettings.androidSdkPath?.let { path -> runCatching { java.nio.file.Path.of(path) }.getOrNull() },
                            initialTraceFile = navigator.methodRecordingTraceFile,
                            onBack = { navigator.open(AppDestination.HOME) },
                        )
                }
                        }
                    }
                }
                if (composeSourceCandidates.isNotEmpty()) {
                    AlertDialog(
                        onDismissRequest = { composeSourceCandidates = emptyList() },
                        title = { Text(localizedStringResource(Res.string.source_select_candidate, language)) },
                        text = {
                            Column {
                                composeSourceCandidates.forEach { candidate ->
                                    TextButton(
                                        onClick = {
                                            composeSourceCandidates = emptyList()
                                            navigator.openSource(candidate.location)
                                        },
                                    ) {
                                        Text(
                                            localizedStringResource(
                                                Res.string.source_candidate_label,
                                                language,
                                                candidate.location.relativePath,
                                                candidate.location.range?.startLine ?: 1,
                                                candidate.confidence.name,
                                            ),
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { composeSourceCandidates = emptyList() }) {
                                Text(localizedStringResource(Res.string.source_cancel, language))
                            }
                        },
                    )
                }
                archivedSourceToRebind?.let { reference ->
                    val workspaces = sourceWorkspaceRuntime.rebindableLocalWorkspaces(reference)
                    AlertDialog(
                        onDismissRequest = { archivedSourceToRebind = null },
                        title = { Text(localizedStringResource(Res.string.source_rebind_archive, language)) },
                        text = {
                            Column {
                                if (workspaces.isEmpty()) {
                                    Text(localizedStringResource(Res.string.source_no_rebind_match, language))
                                }
                                workspaces.forEach { workspace ->
                                    TextButton(
                                        onClick = {
                                            val location = sourceWorkspaceRuntime.rebindArchivedSource(reference, workspace.id)
                                            archivedSourceToRebind = null
                                            location?.let(navigator::openSource)
                                        },
                                    ) {
                                        Text(workspace.displayName)
                                    }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { archivedSourceToRebind = null }) {
                                Text(localizedStringResource(Res.string.source_cancel, language))
                            }
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
                        language = language,
                        simpleperfLocale = if (language == UiLanguage.SIMPLIFIED_CHINESE) java.util.Locale.SIMPLIFIED_CHINESE else java.util.Locale.ENGLISH,
                        sourceWorkspaceRuntime = sourceWorkspaceRuntime,
                        onPageSelected = { settingsPage = it },
                        onApplicationSettingsChanged = updateApplicationSettings,
                        onSimpleperfSettingsChanged = updateSimpleperfPreferences,
                        onLayoutInspectorSettingsChanged = { layoutInspectorSettingsRevision += 1 },
                        onOpenUserGuide = {
                            coroutineScope.launch(Dispatchers.IO) {
                                runCatching { userDocumentationLauncher.open(language) }
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
