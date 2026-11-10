package com.androidperformancestudio.desktop

import com.androidperformancestudio.ui.LocalPageMenuHost
import com.androidperformancestudio.ui.CopyableText
import com.androidperformancestudio.ui.LocalTitleBarTargetHost
import com.androidperformancestudio.ui.LocalWindowMenuBarActive
import com.androidperformancestudio.ui.MenuCommand
import com.androidperformancestudio.ui.MenuShortcuts
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.TitleBarTargetControls
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.WindowMenuBar
import com.androidperformancestudio.ui.WindowMenuChrome
import com.androidperformancestudio.ui.WindowSurfaceEffect
import com.androidperformancestudio.ui.chooseOpenFile
import com.androidperformancestudio.ui.rememberPageMenuHost
import com.androidperformancestudio.ui.rememberTitleBarTargetHost
import com.androidperformancestudio.ui.rememberViewerThemeContext
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.viewerColors
import com.androidperformancestudio.desktop_app.generated.resources.Res
import com.androidperformancestudio.desktop_app.generated.resources.*

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import com.androidperformancestudio.desktop.shell.CommercialAppShell
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
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun FrameWindowScope.DesktopAppMainPage(
    windowTitle: MutableState<String>? = null,
    settingsRequest: SettingsRequest? = null
) {
    val navigator = remember { AppNavigator() }
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
    val aiSettingsStore = remember { com.androidperformancestudio.aiplatform.app.AiPlatformSettingsStore() }
    val aiCoordinator = remember { DesktopAiCoordinator() }
    var aiPlatform by remember { mutableStateOf<com.androidperformancestudio.aiplatform.app.AiPlatformRuntime?>(null) }
    var aiStartupError by remember { mutableStateOf<String?>(null) }
    var showAiWindow by remember { mutableStateOf(false) }
    var aiWindowOpenRequestId by remember { mutableStateOf(0L) }
    var aiWindowContext by remember { mutableStateOf<com.androidperformancestudio.aiplatform.app.BusinessContext?>(null) }
    var aiEntryError by remember { mutableStateOf<String?>(null) }
    var aiEntryPreparing by remember { mutableStateOf(false) }
    val aiExecutionState = aiPlatform?.service?.state?.collectAsState()?.value
    val currentAiBusy by rememberUpdatedState(aiExecutionState?.status?.terminal == false)
    val coroutineScope = rememberCoroutineScope()
    aiCoordinator.sourceModel = sourceWorkspaceRuntime::aiModel
    aiCoordinator.onOpen = { context ->
        aiWindowContext = context
        showAiWindow = true
        aiWindowOpenRequestId++
    }
    val aiContextHost = remember {
        object : com.androidperformancestudio.ui.AiContextHost {
            override val analysisBusy: Boolean
                get() = aiEntryPreparing || currentAiBusy
            override fun publish(context: com.androidperformancestudio.ui.UiAiContext) {
                val evidence = context.evidence.map { item -> com.androidperformancestudio.ai.PerformanceEvidence(item.id, item.kind, item.summary, item.payloadJson) }
                aiCoordinator.contexts.publish(com.androidperformancestudio.aiplatform.app.createBusinessContext(context.key, context.title, evidence, context.currentSelection))
            }
            override fun remove(key: String) { aiCoordinator.contexts.remove(key) }
            override fun analyze(context: com.androidperformancestudio.ui.UiAiContext) {
                if (analysisBusy || context.key != navigator.destination.aiContextKey()) return
                aiEntryPreparing = true
                aiEntryError = null
                coroutineScope.launch {
                    try {
                        aiCoordinator.analyze(profilerAnalysisRequest(context))
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        aiEntryError = failure.message?.takeIf { it.matches(Regex("E_[A-Z0-9_]{1,80}")) } ?: "E_AI_ANALYSIS_FAILED"
                    } finally {
                        aiEntryPreparing = false
                    }
                }
            }
        }
    }
    val openGlobalAi: () -> Unit = {
        aiWindowContext = navigator.destination.aiContextKey()?.let(aiCoordinator.contexts::current)
        showAiWindow = true
        aiWindowOpenRequestId++
    }
    LaunchedEffect(sourceWorkspaceRuntime) {
        try {
            runDesktopAiRuntime(open = {
                com.androidperformancestudio.aiplatform.app.AiPlatformRuntime.open(
                    openAiGateway = { model -> sourceWorkspaceRuntime.providerGateway(AiProviderConnection.CUSTOM, model) },
                    openAiAvailable = { sourceWorkspaceRuntime.providerHasCredential(AiProviderConnection.CUSTOM) },
                    codexGateway = { model -> sourceWorkspaceRuntime.providerGateway(AiProviderConnection.CODEX, model) },
                    codexAvailable = { sourceWorkspaceRuntime.providerHasCredential(AiProviderConnection.CODEX) },
                    claudeEnvironment = sourceWorkspaceRuntime::claudeEnvironment,
                    connectionRevision = { providerId ->
                        sourceWorkspaceRuntime.providerConnectionRevision(AiProviderConnection.fromProviderId(providerId))
                    },
                    gatewayForRun = { providerId, model, revision ->
                        sourceWorkspaceRuntime.providerGateway(AiProviderConnection.fromProviderId(providerId), model, revision)
                    },
                    claudeEnvironmentForRun = { revision -> sourceWorkspaceRuntime.claudeEnvironment(revision) },
                    sourceAuthorization = { request -> request.sourceCandidates.all { candidate ->
                        val resolved = sourceWorkspaceRuntime.candidate(candidate.id)
                        resolved != null && sourceWorkspaceRuntime.repository.workspace(resolved.location.workspaceId)?.allowAiSourceUpload == true &&
                            resolved.location.contentHash == candidate.contentHash
                    } },
                )
            }) { platform ->
                aiPlatform = platform; aiCoordinator.runtime = platform
            }
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (_: Exception) { aiStartupError = "E_AI_STARTUP" }
    }
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
        navigator.open(AppDestination.SETTINGS)
    }
    val language = applicationSettings.language.resolve(Locale.getDefault())
    val openTraceFile: () -> Unit = {
        chooseHomeArtifact(window, localizedStringResource(Res.string.open_trace_file, language))
            ?.let { navigator.openPerfettoTrace(it) }
    }
    val analyzeHprof: () -> Unit = {
        chooseHomeArtifact(window, localizedStringResource(Res.string.analyze_hprof, language))
            ?.let { navigator.openMemoryProfiler(it) }
    }
    val darkTheme = applicationSettings.theme.resolveDark(isSystemInDarkTheme())
    val simpleperfSettings =
        SimpleperfUiSettings(
            theme = SimpleperfThemePreference.parse(applicationSettings.theme.storageValue),
            language = SimpleperfLanguagePreference.parse(applicationSettings.language.storageValue),
            flameTooltipMode = simpleperfPreferences.flameTooltipMode,
            simpleperfEngine = simpleperfPreferences.simpleperfEngine,
        )

    val openUserGuide: () -> Unit = {
        coroutineScope.launch(Dispatchers.IO) {
            runCatching { userDocumentationLauncher.open(language) }
        }
    }

    // The window owns one fixed menu skeleton; the active page only fills its slots, so switching
    // destinations never adds or removes a top-level native menu.
    val pageMenuHost = rememberPageMenuHost()
    val titleBarTargetHost = rememberTitleBarTargetHost()
    val canEmbedTitleBarTargets = MacOsWindowChrome.embedsTitleBar()
    val targetBarSource = remember(applicationSettings.androidSdkPath) {
        DesktopTargetBarSource(applicationSettings.androidSdkPath)
    }
    // The fixed title-bar device/process controls discover their own lists; there is no refresh action.
    LaunchedEffect(targetBarSource, titleBarTargetHost, canEmbedTitleBarTargets) {
        if (!canEmbedTitleBarTargets) return@LaunchedEffect
        while (true) {
            val devices = targetBarSource.loadDevices()
            if (devices != null) titleBarTargetHost.updateDevices(devices)
            val serial = titleBarTargetHost.selectedDeviceSerial
            if (serial != null) {
                val processes = targetBarSource.loadProcesses(serial)
                if (processes != null && titleBarTargetHost.selectedDeviceSerial == serial) {
                    titleBarTargetHost.updateProcesses(processes)
                }
            }
            delay(DesktopTargetBarSource.REFRESH_INTERVAL_MILLIS)
        }
    }
    LaunchedEffect(targetBarSource, titleBarTargetHost, titleBarTargetHost.selectedDeviceSerial) {
        val serial = titleBarTargetHost.selectedDeviceSerial ?: return@LaunchedEffect
        val processes = targetBarSource.loadProcesses(serial) ?: return@LaunchedEffect
        if (titleBarTargetHost.selectedDeviceSerial == serial) titleBarTargetHost.updateProcesses(processes)
    }
    WindowMenuBar(
        host = pageMenuHost,
        chrome =
            WindowMenuChrome(
                language = language,
                globalOpenItems =
                    homeArtifactMenuCommands(navigator.destination, language, openTraceFile, analyzeHprof),
                // macOS reaches Settings through the application menu (ApplicationSettingsMenuInstaller).
                onOpenSettings = { openSettings(SettingsPage.GENERAL) }.takeUnless { MenuShortcuts.isMacOs },
                helpItems =
                    listOf(
                        MenuCommand(localizedStringResource(Res.string.user_guide, language), onClick = openUserGuide),
                        MenuCommand(if (language == UiLanguage.SIMPLIFIED_CHINESE) "AI 分析" else "AI Analysis", onClick = openGlobalAi),
                    ),
            ),
    )

    LaunchedEffect(settingsRequest?.requestId) {
        if (shouldOpenSettingsForRequest(settingsRequest)) {
            settingsRequest?.let { request ->
                // The macOS app-menu Preferences honors the active page's own settings section,
                // matching File > Settings on other platforms.
                val pageSettings = pageMenuHost.menu?.onOpenSettings
                if (request.page == SettingsPage.GENERAL && pageSettings != null) pageSettings() else openSettings(request.page)
            }
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
    LaunchedEffect(navigator.destination, language) {
        windowTitle?.value = localizedStringResource(navigator.destination.titleResource, language)
    }

    val windowColors =
        viewerColors(
            darkTheme = darkTheme,
            accentColor = applicationSettings.themeColor,
        )
    com.androidperformancestudio.aiplatform.presentation.AiConversationWindowHost(
        visible = showAiWindow,
        openRequestId = aiWindowOpenRequestId,
        runtime = aiPlatform,
        startupError = aiStartupError,
        context = aiWindowContext,
        language = language,
        darkTheme = darkTheme,
        defaultModel = sourceWorkspaceRuntime.aiModel(),
        onClose = { showAiWindow = false; coroutineScope.launch { aiPlatform?.service?.cancel() } },
        onOpenSourceCandidate = { id -> sourceWorkspaceRuntime.candidate(id)?.takeIf { it.confidence != ResolutionConfidence.WEAK }?.let { navigator.openSource(it.location) } },
        onOpenSettings = { openSettings(SettingsPage.AI) },
        windowChrome = { title -> AiConversationWindowChrome(title, windowColors, darkTheme) },
        loadModels = sourceWorkspaceRuntime::savedProviderModels,
        selectModel = { providerId, model ->
            sourceWorkspaceRuntime.selectProviderModel(providerId, model) {
                aiSettingsStore.update {
                    check(it.providerId == providerId) { "E_PROVIDER_CONNECTION_CHANGED" }
                    it.copy(model = model)
                }
            }
        },
    )

    WindowSurfaceEffect(windowColors.workspace)
    aiEntryError?.let { error ->
        AlertDialog(
            onDismissRequest = { aiEntryError = null },
            title = { androidx.compose.material3.Text(if (language == UiLanguage.SIMPLIFIED_CHINESE) "AI 分析失败" else "AI Analysis Failed") },
            text = { CopyableText(error, language = language) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { aiEntryError = null }) {
                    androidx.compose.material3.Text(if (language == UiLanguage.SIMPLIFIED_CHINESE) "关闭" else "Close")
                }
            },
        )
    }
    WindowsWindowChromeEffect(windowColors, darkTheme)
    Column(modifier = Modifier.fillMaxSize().background(windowColors.workspace)) {
        MacOsTitleBar(
            title = windowTitle?.value ?: APP_DISPLAY_NAME,
            background = windowColors.toolbar,
            // The title text inverts against its own strip so it stays legible in either theme.
            contentColor = windowColors.primaryText,
            dividerColor = windowColors.border,
            targetControls = {
                ViewerTheme(
                    darkTheme = darkTheme,
                    displayScale = 1f,
                    accentColor = applicationSettings.themeColor,
                ) {
                    TitleBarTargetControls(titleBarTargetHost, language)
                }
            },
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            ViewerTheme(
                darkTheme = darkTheme,
                displayScale = applicationSettings.displayScale.multiplier,
                accentColor = applicationSettings.themeColor,
            ) {
                val viewerThemeContext = rememberViewerThemeContext()
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
                    CommercialAppShell(
                        navigator = navigator,
                        language = language,
                        navigationRailCollapsed = applicationSettings.navigationRailCollapsed,
                        onOpenAi = openGlobalAi,
                    ) {
                        navigator.retainedDestinations.forEach { destination ->
                            key(destination) {
                                val active = destination == navigator.destination
                                CompositionLocalProvider(
                                    LocalWindowMenuBarActive provides active,
                                    LocalPageMenuHost provides pageMenuHost,
                                    com.androidperformancestudio.ui.LocalAiContextHost provides aiContextHost,
                                    LocalTitleBarTargetHost provides
                                        titleBarTargetHost.takeIf { canEmbedTitleBarTargets && active },
                                ) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .fillMaxSize()
                                                .zIndex(if (active) 1f else 0f)
                                                .alpha(if (active) 1f else 0f)
                                                .blockPointerInputWhenInactive(active)
                                                .blockKeyboardInputWhenInactive(active)
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
                                    onOpenRecentArtifact = { item ->
                                        when (item.feature) {
                                            com.androidperformancestudio.desktop.dashboard.StudioFeature.LAYOUT ->
                                                navigator.openLayoutArchive(item.artifactPath)
                                            com.androidperformancestudio.desktop.dashboard.StudioFeature.CPU ->
                                                navigator.openSimpleperfSession(item.artifactPath)
                                            com.androidperformancestudio.desktop.dashboard.StudioFeature.TRACE ->
                                                navigator.openPerfettoTrace(item.artifactPath)
                                            com.androidperformancestudio.desktop.dashboard.StudioFeature.MEMORY ->
                                                navigator.openMemoryProfiler(item.artifactPath)
                                        }
                                    },
                                    onOpenTraceFile = openTraceFile,
                                    onAnalyzeHprof = analyzeHprof,
                                    androidSdkPath = applicationSettings.androidSdkPath,
                                    active = active,
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
                                    initialArchiveFile = navigator.layoutArchiveRequest?.path,
                                    initialArchiveRequestId = navigator.layoutArchiveRequest?.requestId ?: 0L,
                                    aiAnalysisClient = remember(sourceWorkspaceRuntime, aiCoordinator, aiSettingsStore) {
                                        SourceAwareLayoutAiAnalysisClient(
                                            sourceWorkspaceRuntime,
                                            globalAnalysis = aiCoordinator::analyze,
                                            loadSettings = aiSettingsStore::load,
                                        )
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
                                    initialSessionFile = navigator.simpleperfSessionRequest?.path,
                                    initialSessionRequestId = navigator.simpleperfSessionRequest?.requestId ?: 0L,
                                    onSettingsChanged = updateSimpleperfPreferences,
                                    onNavigateHome = { navigator.open(AppDestination.HOME) },
                                    onOpenPreferences = { section ->
                                        simpleperfSettingsSection = section
                                        openSettings(SettingsPage.SIMPLEPERF)
                                    },
                                    onCaptureSettingsContextChanged = { simpleperfCaptureSettingsContext = it },
                                    onOpenUserGuide = openUserGuide,
                                    aiAnalysisClient = remember(sourceWorkspaceRuntime, aiCoordinator) { ProductionSimpleperfAiAnalysisClient(sourceWorkspaceRuntime, aiCoordinator) },
                                    onOpenSourceCandidate = { candidateId ->
                                        sourceWorkspaceRuntime.candidate(candidateId)
                                            ?.takeIf { it.confidence != ResolutionConfidence.WEAK }
                                            ?.let { candidate ->
                                                navigator.openSource(candidate.location)
                                            }
                                    },
                                )
                            AppDestination.PERFETTO ->
                                PerfettoMainPage(
                                    language = language,
                                    isActive = active,
                                    androidSdkPath = applicationSettings.androidSdkPath?.let { path ->
                                        runCatching { java.nio.file.Path.of(path) }.getOrNull()
                                    },
                                    onNavigateHome = { navigator.open(AppDestination.HOME) },
                                    initialTraceFile = navigator.perfettoTraceFile,
                                    initialTraceRequestId = navigator.perfettoTraceRequestId,
                                    initialTraceNotice = navigator.perfettoTraceNotice,
                                    initialTraceTimestampNanos = navigator.perfettoTimestampNanos,
                                    onOpenUserGuide = openUserGuide,
                                )
                            AppDestination.MEMORY_PROFILER ->
                                MemoryProfilerMainPage(
                                    language = language,
                                    highlightClassName = memoryHighlightClassName,
                                    initialImportFile = navigator.memoryImportFile,
                                    initialImportRequestId = navigator.memoryImportRequestId,
                                    initialImportIsJavaHeap = navigator.memoryImportIsJavaHeap,
                                    onBack = { navigator.open(AppDestination.HOME) },
                                )
                            AppDestination.FRAME_PROFILER ->
                                FrameProfilerMainPage(
                                    language = language,
                                    isActive = active,
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
                                            request.intendedVsyncNs,
                                        )
                                    },
                                )
                            AppDestination.STARTUP_PROFILER ->
                                StartupProfilerMainPage(
                                    language = language,
                                    onBack = { navigator.open(AppDestination.HOME) },
                                    onOpenTrace = { path ->
                                        navigator.openPerfettoTrace(
                                            path,
                                            localizedStringResource(
                                                Res.string.opened_from_tool_for_correlation_only,
                                                language,
                                                localizedStringResource(Res.string.startup_profiler, language),
                                            ),
                                        )
                                    },
                                )
                            AppDestination.BATTERY_PROFILER ->
                                BatteryProfilerMainPage(
                                    language = language,
                                    onBack = { navigator.open(AppDestination.HOME) },
                                )
                            AppDestination.NETWORK_PROFILER ->
                                NetworkProfilerMainPage(
                                    language = language,
                                    onBack = { navigator.open(AppDestination.HOME) },
                                )
                            AppDestination.GPU_INSPECTOR ->
                                GpuIntegrationMainPage(
                                    language = language,
                                    onBack = { navigator.open(AppDestination.HOME) },
                                    onOpenTraceAnalyzer = { navigator.open(AppDestination.PERFETTO) },
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
                                    androidSdkPath = applicationSettings.androidSdkPath?.let { path -> runCatching { java.nio.file.Path.of(path) }.getOrNull() },
                                    initialTraceFile = navigator.methodRecordingTraceFile,
                                    onBack = { navigator.open(AppDestination.HOME) },
                                )
                            AppDestination.SETTINGS ->
                                DesktopAppSettingsPage(
                                    selectedPage = settingsPage,
                                    applicationSettings = applicationSettings,
                                    simpleperfSettings = simpleperfSettings,
                                    simpleperfCaptureSettingsContext = simpleperfCaptureSettingsContext,
                                    simpleperfInitialSection = simpleperfSettingsSection,
                                    viewerThemeContext = viewerThemeContext,
                                    language = language,
                                    simpleperfLocale = if (language == UiLanguage.SIMPLIFIED_CHINESE) java.util.Locale.SIMPLIFIED_CHINESE else java.util.Locale.ENGLISH,
                                    sourceWorkspaceRuntime = sourceWorkspaceRuntime,
                                    onPageSelected = { settingsPage = it },
                                    onApplicationSettingsChanged = updateApplicationSettings,
                                    onSimpleperfSettingsChanged = updateSimpleperfPreferences,
                                    onLayoutInspectorSettingsChanged = { layoutInspectorSettingsRevision += 1 },
                                    onOpenUserGuide = openUserGuide,
                                    persistenceErrorPage = settingsPersistenceErrorPage,
                                    aiPlatformRuntime = aiPlatform,
                                    aiPlatformSettingsStore = aiSettingsStore,
                                )
                        }
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
                                            CopyableText(localizedStringResource(Res.string.source_no_rebind_match, language), language = language)
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
                    }
                }
            }
        }
    }
}

internal fun homeArtifactMenuCommands(
    destination: AppDestination,
    language: UiLanguage,
    onOpenTraceFile: () -> Unit,
    onAnalyzeHprof: () -> Unit,
): List<MenuCommand> =
    if (destination == AppDestination.HOME) {
        listOf(
            MenuCommand(localizedStringResource(Res.string.open_trace_file, language), onClick = onOpenTraceFile),
            MenuCommand(localizedStringResource(Res.string.analyze_hprof, language), onClick = onAnalyzeHprof),
        )
    } else {
        emptyList()
    }

private fun chooseHomeArtifact(
    parent: java.awt.Window,
    title: String,
): Path? = chooseOpenFile(parent, title)?.toPath()?.takeIf(Files::isRegularFile)

private fun Modifier.blockPointerInputWhenInactive(active: Boolean): Modifier =
    if (active) {
        this
    } else {
        pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }
    }


private fun Modifier.blockKeyboardInputWhenInactive(active: Boolean): Modifier =
    if (active) {
        this
    } else {
        onPreviewKeyEvent { true }
    }
