package com.androidperformancestudio.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.desktop.dashboard.DeviceOverviewSource
import com.androidperformancestudio.desktop.dashboard.StudioDeviceOverview
import com.androidperformancestudio.desktop.dashboard.StudioDeviceSummary
import com.androidperformancestudio.desktop.dashboard.StudioFeature
import com.androidperformancestudio.desktop.dashboard.StudioRecentItem
import com.androidperformancestudio.desktop.dashboard.WorkspaceOverviewRepository
import com.androidperformancestudio.desktop.dashboard.WorkspaceOverviewSnapshot
import com.androidperformancestudio.desktop.shell.navigationIconResource
import com.androidperformancestudio.desktop_app.generated.resources.Res
import com.androidperformancestudio.desktop_app.generated.resources.*
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioCard
import com.androidperformancestudio.ui.studio.StudioDataTable
import com.androidperformancestudio.ui.studio.StudioEmptyState
import com.androidperformancestudio.ui.studio.StudioErrorState
import com.androidperformancestudio.ui.studio.StudioLoadingState
import com.androidperformancestudio.ui.studio.StudioMetricCard
import com.androidperformancestudio.ui.studio.StudioPanel
import com.androidperformancestudio.ui.studio.StudioPanelHeader
import com.androidperformancestudio.ui.studio.StudioStatusChip
import com.androidperformancestudio.ui.studio.StudioStatusTone
import com.androidperformancestudio.ui.studio.StudioTableColumn
import com.androidperformancestudio.ui.studio.StudioTokens
import com.androidperformancestudio.ui_components.generated.resources.Res as UiComponentsRes
import com.androidperformancestudio.ui_components.generated.resources.ic_action_import
import com.androidperformancestudio.ui_components.generated.resources.ic_action_play
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_memory_profiler
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_startup_profiler
import com.androidperformancestudio.ui_components.generated.resources.ic_stat_device
import com.androidperformancestudio.ui_components.generated.resources.ic_stat_history
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

internal const val HOME_MODULE_CARD_HEIGHT_DP = 78
internal const val HOME_QUICK_ACTION_HEIGHT_DP = 92
internal const val HOME_RAIL_WIDTH_DP = 320
internal const val HOME_TWO_COLUMN_MIN_WIDTH_DP = 900

internal fun homeGridColumnCount(availableWidthDp: Int): Int =
    when {
        availableWidthDp >= 820 -> 3
        availableWidthDp >= 560 -> 2
        else -> 1
    }

/** Quick actions stay a single four-up row until the column is too narrow for the descriptions. */
internal fun homeQuickActionColumnCount(availableWidthDp: Int): Int =
    when {
        availableWidthDp >= 720 -> 4
        availableWidthDp >= 380 -> 2
        else -> 1
    }

private val sessionTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun Instant.sessionLabel(): String = sessionTimestampFormatter.format(this)

private fun homeDatePattern(language: UiLanguage): String =
    if (language == UiLanguage.SIMPLIFIED_CHINESE) "M月d日 EEE" else "MMM d, EEE"

private fun homeDateLocale(language: UiLanguage): Locale =
    if (language == UiLanguage.SIMPLIFIED_CHINESE) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH

@Composable
internal fun AppHomePage(
    language: UiLanguage,
    onOpenSourceWorkspaces: () -> Unit,
    onOpenLayoutInspector: () -> Unit,
    onOpenSimpleperf: () -> Unit,
    onOpenPerfetto: () -> Unit,
    onOpenMemoryProfiler: () -> Unit,
    onOpenFrameProfiler: () -> Unit,
    onOpenStartupProfiler: () -> Unit,
    onOpenBatteryProfiler: () -> Unit,
    onOpenNetworkProfiler: () -> Unit,
    onOpenGpuInspector: () -> Unit,
    onOpenBenchmarkRegression: () -> Unit,
    onOpenRecentArtifact: (StudioRecentItem) -> Unit,
    onOpenTraceFile: () -> Unit,
    onAnalyzeHprof: () -> Unit,
    androidSdkPath: String? = null,
    active: Boolean = true,
) {
    val overviewRepository = remember { WorkspaceOverviewRepository.desktop() }
    val deviceOverviewSource = remember(androidSdkPath) { DeviceOverviewSource(androidSdkPath) }
    var recentSnapshot by remember { mutableStateOf<WorkspaceOverviewSnapshot?>(null) }
    var loadingRecentItems by remember { mutableStateOf(true) }
    var deviceOverview by remember { mutableStateOf<StudioDeviceOverview?>(null) }
    var deviceRefreshRevision by remember { mutableStateOf(0) }
    LaunchedEffect(active) {
        if (active) {
            loadingRecentItems = true
            recentSnapshot = overviewRepository.loadRecent(limit = 6)
            loadingRecentItems = false
        }
    }
    LaunchedEffect(active, deviceOverviewSource, deviceRefreshRevision) {
        if (active) {
            // Keep the current projection visible while refreshing so pressing refresh does not
            // swap the panel to the loading state and back. The first load still shows loading
            // because the state starts as null.
            deviceOverview = deviceOverviewSource.load()
        }
    }
    val entries =
        listOf(
            HomeFeatureEntry(
                destination = AppDestination.LAYOUT_INSPECTOR,
                title = localizedStringResource(Res.string.layout_inspector, language),
                description =
                    localizedStringResource(
                        Res.string.inspect_android_view_hierarchies_screenshots_bounds_and_properties,
                        language,
                    ),
                onClick = onOpenLayoutInspector,
            ),
            HomeFeatureEntry(
                destination = AppDestination.SIMPLEPERF,
                title = localizedStringResource(Res.string.cpu_profiler, language),
                description =
                    localizedStringResource(
                        Res.string.simpleperf_based_cpu_sampling_flame_graphs_call_tree_analysis,
                        language,
                    ),
                onClick = onOpenSimpleperf,
            ),
            HomeFeatureEntry(
                destination = AppDestination.PERFETTO,
                title = localizedStringResource(Res.string.trace_analyzer, language),
                description =
                    localizedStringResource(
                        Res.string.perfetto_system_level_trace_capture_with_scheduling_binder_and_graphic,
                        language,
                    ),
                onClick = onOpenPerfetto,
            ),
            HomeFeatureEntry(
                destination = AppDestination.MEMORY_PROFILER,
                title = localizedStringResource(Res.string.memory_profiler, language),
                description =
                    localizedStringResource(
                        Res.string.heap_dump_capture_object_statistics_and_class_histogram_analysis,
                        language,
                    ),
                onClick = onOpenMemoryProfiler,
            ),
            HomeFeatureEntry(
                destination = AppDestination.FRAME_PROFILER,
                title = localizedStringResource(Res.string.frame_profiler, language),
                description =
                    localizedStringResource(
                        Res.string.capture_online_or_import_gfxinfo_framestats_to_analyze_frame_timing,
                        language,
                    ),
                onClick = onOpenFrameProfiler,
            ),
            HomeFeatureEntry(
                destination = AppDestination.STARTUP_PROFILER,
                title = localizedStringResource(Res.string.startup_profiler, language),
                description =
                    localizedStringResource(
                        Res.string.cold_warm_startup_breakdown_and_baseline_profile_support,
                        language,
                    ),
                onClick = onOpenStartupProfiler,
            ),
            HomeFeatureEntry(
                destination = AppDestination.BATTERY_PROFILER,
                title = localizedStringResource(Res.string.battery_profiler, language),
                description =
                    localizedStringResource(
                        Res.string.batterystats_analysis_with_wakelock_alarm_and_network_usage_stats,
                        language,
                    ),
                onClick = onOpenBatteryProfiler,
            ),
            HomeFeatureEntry(
                destination = AppDestination.NETWORK_PROFILER,
                title = localizedStringResource(Res.string.network_profiler, language),
                description =
                    localizedStringResource(
                        Res.string.http_https_traffic_capture_and_request_timeline_analysis,
                        language,
                    ),
                onClick = onOpenNetworkProfiler,
            ),
            HomeFeatureEntry(
                destination = AppDestination.GPU_INSPECTOR,
                title = localizedStringResource(Res.string.gpu_inspector, language),
                description =
                    localizedStringResource(
                        Res.string.discover_and_launch_android_gpu_inspector_then_index_and_verify,
                        language,
                    ),
                onClick = onOpenGpuInspector,
            ),
            HomeFeatureEntry(
                destination = AppDestination.BENCHMARK_REGRESSION,
                title = localizedStringResource(Res.string.benchmark_regression, language),
                description =
                    localizedStringResource(
                        Res.string.compare_androidx_benchmark_baselines_and_current_results_with_ci_regre,
                        language,
                    ),
                onClick = onOpenBenchmarkRegression,
            ),
            HomeFeatureEntry(
                destination = AppDestination.SOURCE_WORKSPACES,
                title = localizedStringResource(Res.string.source_workspaces, language),
                description = localizedStringResource(Res.string.source_home_description, language),
                onClick = onOpenSourceWorkspaces,
            ),
        )
    val quickActions =
        listOf(
            HomeQuickAction(
                label = localizedStringResource(Res.string.quick_action_start_capture, language),
                description =
                    localizedStringResource(Res.string.quick_action_start_capture_description, language),
                icon = UiComponentsRes.drawable.ic_action_play,
                onClick = onOpenPerfetto,
            ),
            HomeQuickAction(
                label = localizedStringResource(Res.string.quick_action_import_trace, language),
                description =
                    localizedStringResource(Res.string.quick_action_import_trace_description, language),
                icon = UiComponentsRes.drawable.ic_action_import,
                onClick = onOpenTraceFile,
            ),
            HomeQuickAction(
                label = localizedStringResource(Res.string.quick_action_analyze_hprof, language),
                description =
                    localizedStringResource(Res.string.quick_action_analyze_hprof_description, language),
                icon = UiComponentsRes.drawable.ic_nav_memory_profiler,
                onClick = onAnalyzeHprof,
            ),
            HomeQuickAction(
                label = localizedStringResource(Res.string.quick_action_run_startup_test, language),
                description =
                    localizedStringResource(Res.string.quick_action_run_startup_test_description, language),
                icon = UiComponentsRes.drawable.ic_nav_startup_profiler,
                onClick = onOpenStartupProfiler,
            ),
        )

    val colors = LocalViewerColors.current
    Surface(modifier = Modifier.fillMaxSize(), color = colors.workspace) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(StudioTokens.workspacePadding),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth.value >= HOME_TWO_COLUMN_MIN_WIDTH_DP) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
                        verticalAlignment = Alignment.Top,
                    ) {
                        HomeMainColumn(
                            entries = entries,
                            quickActions = quickActions,
                            deviceOverview = deviceOverview,
                            recentSnapshot = recentSnapshot,
                            loadingRecentItems = loadingRecentItems,
                            language = language,
                            onOpenRecentArtifact = onOpenRecentArtifact,
                            modifier = Modifier.weight(1f),
                        )
                        HomeRailColumn(
                            deviceOverview = deviceOverview,
                            language = language,
                            onRefreshDevices = { deviceRefreshRevision++ },
                            modifier = Modifier.width(HOME_RAIL_WIDTH_DP.dp),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
                    ) {
                        HomeMainColumn(
                            entries = entries,
                            quickActions = quickActions,
                            deviceOverview = deviceOverview,
                            recentSnapshot = recentSnapshot,
                            loadingRecentItems = loadingRecentItems,
                            language = language,
                            onOpenRecentArtifact = onOpenRecentArtifact,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        HomeRailColumn(
                            deviceOverview = deviceOverview,
                            language = language,
                            onRefreshDevices = { deviceRefreshRevision++ },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeMainColumn(
    entries: List<HomeFeatureEntry>,
    quickActions: List<HomeQuickAction>,
    deviceOverview: StudioDeviceOverview?,
    recentSnapshot: WorkspaceOverviewSnapshot?,
    loadingRecentItems: Boolean,
    language: UiLanguage,
    onOpenRecentArtifact: (StudioRecentItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
    ) {
        HomeMetrics(
            deviceOverview = deviceOverview,
            recentSnapshot = recentSnapshot,
            loadingRecentItems = loadingRecentItems,
            language = language,
        )
        HomeSection(title = localizedStringResource(Res.string.quick_actions, language)) {
            HomeQuickActionGrid(actions = quickActions)
        }
        HomeSection(title = localizedStringResource(Res.string.home_analysis_modules, language)) {
            HomeModuleGrid(entries = entries, language = language)
        }
        HomeRecentSessions(
            recentSnapshot = recentSnapshot,
            loadingRecentItems = loadingRecentItems,
            language = language,
            onOpenRecentArtifact = onOpenRecentArtifact,
        )
    }
}

@Composable
private fun HomeRailColumn(
    deviceOverview: StudioDeviceOverview?,
    language: UiLanguage,
    onRefreshDevices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
    ) {
        HomeDateCard(language)
        HomeDeviceSummary(
            overview = deviceOverview,
            language = language,
            onRefresh = onRefreshDevices,
        )
    }
}

@Composable
private fun HomeMetrics(
    deviceOverview: StudioDeviceOverview?,
    recentSnapshot: WorkspaceOverviewSnapshot?,
    loadingRecentItems: Boolean,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    val devicesValue = (deviceOverview as? StudioDeviceOverview.Available)?.connectedCount?.toString() ?: "—"
    val recentValue =
        recentSnapshot
            ?.takeIf { !loadingRecentItems && it.unavailableSourceCount < it.sourceCount }
            ?.items
            ?.size
            ?.toString()
            ?: "—"
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
    ) {
        StudioMetricCard(
            label = localizedStringResource(Res.string.connected_devices, language),
            value = devicesValue,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            icon = UiComponentsRes.drawable.ic_stat_device,
            tone = colors.info,
        )
        StudioMetricCard(
            label = localizedStringResource(Res.string.recent_artifacts_shown, language),
            value = recentValue,
            supportingText = localizedStringResource(Res.string.recent_artifacts_scope, language),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            icon = UiComponentsRes.drawable.ic_stat_history,
            tone = colors.accent,
        )
    }
}

@Composable
private fun HomeSection(
    title: String,
    content: @Composable () -> Unit,
) {
    val colors = LocalViewerColors.current
    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
        Text(
            text = title,
            color = colors.primaryText,
            fontSize = ViewerTypography.subsectionTitle.fontSize,
            lineHeight = ViewerTypography.subsectionTitle.lineHeight,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

@Composable
private fun HomeQuickActionGrid(actions: List<HomeQuickAction>) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columnCount = homeQuickActionColumnCount(maxWidth.value.toInt())
        Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
            actions.chunked(columnCount).forEach { rowActions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
                ) {
                    rowActions.forEach { action ->
                        QuickActionCard(action = action, modifier = Modifier.weight(1f))
                    }
                    repeat(columnCount - rowActions.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeModuleGrid(
    entries: List<HomeFeatureEntry>,
    language: UiLanguage,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columnCount = homeGridColumnCount(maxWidth.value.toInt())
        Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
            entries.chunked(columnCount).forEach { rowEntries ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
                ) {
                    rowEntries.forEach { entry ->
                        FeatureEntryCard(entry = entry, modifier = Modifier.weight(1f))
                    }
                    repeat(columnCount - rowEntries.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeRecentSessions(
    recentSnapshot: WorkspaceOverviewSnapshot?,
    loadingRecentItems: Boolean,
    language: UiLanguage,
    onOpenRecentArtifact: (StudioRecentItem) -> Unit,
) {
    val colors = LocalViewerColors.current
    StudioDataTable(
        columns =
            listOf(
                StudioTableColumn(localizedStringResource(Res.string.recent_column_name, language), 2.2f),
                StudioTableColumn(localizedStringResource(Res.string.recent_column_package, language), 1.6f),
                StudioTableColumn(localizedStringResource(Res.string.recent_column_type, language), 0.85f),
                StudioTableColumn(localizedStringResource(Res.string.recent_column_started, language), 1.3f),
                StudioTableColumn(localizedStringResource(Res.string.recent_column_action, language), 0.8f),
            ),
        title = localizedStringResource(Res.string.home_recent_sessions, language),
        modifier = Modifier.fillMaxWidth(),
    ) {
        when {
            loadingRecentItems -> StudioLoadingState(
                title = localizedStringResource(Res.string.home_recent_sessions, language),
                detail = localizedStringResource(Res.string.loading_recent_artifacts, language),
            )
            recentSnapshot != null && recentSnapshot.unavailableSourceCount == recentSnapshot.sourceCount -> StudioErrorState(
                title = localizedStringResource(Res.string.recent_indexes_unavailable, language),
                detail = localizedStringResource(Res.string.recent_indexes_unavailable_hint, language),
            )
            recentSnapshot?.items?.isEmpty() == true && recentSnapshot?.unavailableSourceCount == 0 -> StudioEmptyState(
                title = localizedStringResource(Res.string.no_recent_artifacts, language),
                detail = localizedStringResource(Res.string.recent_artifacts_hint, language),
            )
            else -> {
                if ((recentSnapshot?.unavailableSourceCount ?: 0) > 0) {
                    Text(
                        text = localizedStringResource(Res.string.some_recent_indexes_unavailable, language),
                        color = colors.secondaryText,
                        fontSize = ViewerTypography.secondary.fontSize,
                        lineHeight = ViewerTypography.secondary.lineHeight,
                    )
                }
                recentSnapshot?.items.orEmpty().forEach { item ->
                    RecentSessionRow(item = item, language = language, onOpen = { onOpenRecentArtifact(item) })
                }
            }
        }
    }
}

@Composable
private fun RecentSessionRow(
    item: StudioRecentItem,
    language: UiLanguage,
    onOpen: () -> Unit,
) {
    val colors = LocalViewerColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = StudioTokens.compactContentPadding, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.title,
            modifier = Modifier.weight(2.2f),
            color = colors.primaryText,
            fontSize = ViewerTypography.body.fontSize,
            lineHeight = ViewerTypography.body.lineHeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.packageName ?: "—",
            modifier = Modifier.weight(1.6f),
            color = colors.secondaryText,
            fontSize = ViewerTypography.bodyCompact.fontSize,
            lineHeight = ViewerTypography.bodyCompact.lineHeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(modifier = Modifier.weight(0.85f)) {
            StudioStatusChip(label = item.featureLabel(language), tone = StudioStatusTone.NEUTRAL)
        }
        Text(
            text = item.capturedAt?.sessionLabel() ?: "—",
            modifier = Modifier.weight(1.3f),
            color = colors.secondaryText,
            fontSize = ViewerTypography.bodyCompact.fontSize,
            lineHeight = ViewerTypography.bodyCompact.lineHeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(modifier = Modifier.weight(0.8f), contentAlignment = Alignment.CenterEnd) {
            // A plain clickable label instead of a TextButton: Material's 40 dp minimum height was
            // what made every session row so tall.
            Text(
                text = localizedStringResource(Res.string.open_artifact, language),
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(StudioTokens.smallRadius))
                        .clickable(onClick = onOpen)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                color = colors.accent,
                fontSize = ViewerTypography.bodyCompact.fontSize,
                lineHeight = ViewerTypography.bodyCompact.lineHeight,
            )
        }
    }
}

@Composable
private fun StudioRecentItem.featureLabel(language: UiLanguage): String =
    localizedStringResource(
        when (feature) {
            StudioFeature.LAYOUT -> Res.string.recent_feature_layout
            StudioFeature.CPU -> Res.string.recent_feature_cpu
            StudioFeature.TRACE -> Res.string.recent_feature_trace
            StudioFeature.MEMORY -> Res.string.recent_feature_memory
        },
        language,
    )

@Composable
private fun HomeDateCard(language: UiLanguage) {
    val colors = LocalViewerColors.current
    val locale = remember(language) { homeDateLocale(language) }
    val formatter = remember(locale) { DateTimeFormatter.ofPattern(homeDatePattern(language), locale) }
    StudioPanel(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = LocalDate.now().format(formatter),
            color = colors.primaryText,
            fontSize = ViewerTypography.sectionTitle.fontSize,
            lineHeight = ViewerTypography.sectionTitle.lineHeight,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = localizedStringResource(Res.string.home_focus_on_performance, language),
            color = colors.secondaryText,
            fontSize = ViewerTypography.secondary.fontSize,
            lineHeight = ViewerTypography.secondary.lineHeight,
        )
    }
}

@Composable
private fun HomeDeviceSummary(
    overview: StudioDeviceOverview?,
    language: UiLanguage,
    onRefresh: () -> Unit,
) {
    val colors = LocalViewerColors.current
    val title = localizedStringResource(Res.string.connected_devices, language)
    when (overview) {
        null -> StudioLoadingState(
            title = title,
            detail = localizedStringResource(Res.string.loading_devices, language),
        )
        StudioDeviceOverview.Unavailable -> StudioErrorState(
            title = title,
            detail = localizedStringResource(Res.string.device_discovery_unavailable, language),
            retryLabel = localizedStringResource(Res.string.refresh_devices, language),
            onRetry = onRefresh,
        )
        is StudioDeviceOverview.Available ->
            StudioPanel(modifier = Modifier.fillMaxWidth()) {
                StudioPanelHeader(title = title) {
                    TextButton(onClick = onRefresh) {
                        Text(
                            text = localizedStringResource(Res.string.refresh_devices, language),
                            color = colors.accent,
                            fontSize = ViewerTypography.bodyCompact.fontSize,
                            lineHeight = ViewerTypography.bodyCompact.lineHeight,
                        )
                    }
                }
                if (overview.devices.isEmpty()) {
                    Text(
                        text = localizedStringResource(Res.string.no_connected_devices, language),
                        color = colors.secondaryText,
                        fontSize = ViewerTypography.body.fontSize,
                        lineHeight = ViewerTypography.body.lineHeight,
                    )
                } else {
                    overview.devices.forEach { device -> DeviceRow(device = device, language = language) }
                }
            }
    }
}

@Composable
private fun DeviceRow(
    device: StudioDeviceSummary,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
    ) {
        Icon(
            painter = painterResource(UiComponentsRes.drawable.ic_stat_device),
            contentDescription = null,
            modifier = Modifier.size(StudioTokens.navigationIconSize),
            tint = if (device.online) colors.accent else colors.mutedText,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.displayName,
                color = colors.primaryText,
                fontSize = ViewerTypography.body.fontSize,
                lineHeight = ViewerTypography.body.lineHeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = device.serial,
                color = colors.secondaryText,
                fontSize = ViewerTypography.secondary.fontSize,
                lineHeight = ViewerTypography.secondary.lineHeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StudioStatusChip(
            label = localizedStringResource(device.state.labelResource(), language),
            tone = if (device.online) StudioStatusTone.SUCCESS else StudioStatusTone.WARNING,
        )
    }
}

private fun AdbDeviceState.labelResource() =
    when (this) {
        AdbDeviceState.ONLINE -> Res.string.device_online
        AdbDeviceState.OFFLINE -> Res.string.device_offline
        AdbDeviceState.UNAUTHORIZED -> Res.string.device_unauthorized
        AdbDeviceState.NO_PERMISSIONS -> Res.string.device_no_permissions
        AdbDeviceState.UNKNOWN -> Res.string.device_unknown
    }

private data class HomeFeatureEntry(
    val destination: AppDestination,
    val title: String,
    val description: String,
    val onClick: () -> Unit,
)

private data class HomeQuickAction(
    val label: String,
    val description: String,
    val icon: DrawableResource,
    val onClick: () -> Unit,
)

@Composable
private fun QuickActionCard(
    action: HomeQuickAction,
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    val shape = RoundedCornerShape(StudioTokens.cardRadius)
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    StudioCard(
        modifier =
            modifier
                .height(HOME_QUICK_ACTION_HEIGHT_DP.dp)
                .shadow(elevation = if (hovered) 6.dp else 1.dp, shape = shape, clip = false)
                .clickable(interactionSource = interactionSource, indication = null, onClick = action.onClick),
        containerColor = if (hovered) colors.selectedRow else colors.panel,
        borderColor = if (hovered) colors.accent.copy(alpha = 0.42f) else colors.border.copy(alpha = 0.72f),
        contentPadding = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioTokens.sectionGap),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.accent.copy(alpha = if (colors.isDark) 0.24f else 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(action.icon),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = colors.accent,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = action.label,
                    color = colors.primaryText,
                    fontSize = ViewerTypography.cardTitle.fontSize,
                    lineHeight = ViewerTypography.cardTitle.lineHeight,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = action.description,
                    color = colors.secondaryText,
                    fontSize = ViewerTypography.secondary.fontSize,
                    lineHeight = ViewerTypography.secondary.lineHeight,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FeatureEntryCard(
    entry: HomeFeatureEntry,
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    val shape = RoundedCornerShape(StudioTokens.cardRadius)
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    StudioCard(
        modifier =
            modifier
                .height(HOME_MODULE_CARD_HEIGHT_DP.dp)
                .shadow(elevation = if (hovered) 6.dp else 1.dp, shape = shape, clip = false)
                .clickable(interactionSource = interactionSource, indication = null, onClick = entry.onClick),
        containerColor = if (hovered) colors.selectedRow else colors.panel,
        borderColor = if (hovered) colors.accent.copy(alpha = 0.42f) else colors.border.copy(alpha = 0.72f),
        contentPadding = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(colors.accent.copy(alpha = if (colors.isDark) 0.22f else 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(entry.destination.navigationIconResource),
                    contentDescription = null,
                    modifier = Modifier.size(StudioTokens.navigationIconSize),
                    tint = colors.accent,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = entry.title,
                    color = colors.primaryText,
                    fontSize = ViewerTypography.cardTitle.fontSize,
                    lineHeight = ViewerTypography.cardTitle.lineHeight,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.description,
                    color = colors.secondaryText,
                    fontSize = ViewerTypography.secondary.fontSize,
                    lineHeight = ViewerTypography.secondary.lineHeight,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
