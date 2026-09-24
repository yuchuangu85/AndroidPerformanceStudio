package com.androidperformancestudio.desktop

import com.androidperformancestudio.ui.ViewerTypography

import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.desktop_app.generated.resources.Res
import com.androidperformancestudio.desktop_app.generated.resources.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.studio.StudioEmptyState
import com.androidperformancestudio.ui.studio.StudioErrorState
import com.androidperformancestudio.ui.studio.StudioDataTable
import com.androidperformancestudio.ui.studio.StudioLoadingState
import com.androidperformancestudio.ui.studio.StudioPanel
import com.androidperformancestudio.ui.studio.StudioPanelHeader
import com.androidperformancestudio.ui.studio.StudioMetricCard
import com.androidperformancestudio.ui.studio.StudioStatusChip
import com.androidperformancestudio.ui.studio.StudioStatusTone
import com.androidperformancestudio.ui.studio.StudioTokens
import com.androidperformancestudio.ui.studio.StudioTableColumn
import com.androidperformancestudio.desktop.dashboard.StudioFeature
import com.androidperformancestudio.desktop.dashboard.StudioRecentItem
import com.androidperformancestudio.desktop.dashboard.WorkspaceOverviewRepository
import com.androidperformancestudio.desktop.dashboard.WorkspaceOverviewSnapshot
import com.androidperformancestudio.desktop.dashboard.DeviceOverviewSource
import com.androidperformancestudio.desktop.dashboard.StudioDeviceOverview
import com.androidperformancestudio.platform.adb.AdbDeviceState

internal const val HOME_CARD_HEIGHT_DP = 184
internal const val HOME_CARD_CORNER_RADIUS_DP = 14
internal const val HOME_MAX_CONTENT_WIDTH_DP = 1180

internal fun homeGridColumnCount(availableWidthDp: Int): Int =
    when {
        availableWidthDp >= 1040 -> 4
        availableWidthDp >= 760 -> 3
        availableWidthDp >= 500 -> 2
        else -> 1
    }

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
            deviceOverview = null
            deviceOverview = deviceOverviewSource.load()
        }
    }
    val entries =
        listOf(
            HomeFeatureEntry(
                title = localizedStringResource(Res.string.layout_inspector, language),
                subtitle = localizedStringResource(Res.string.layout_inspection, language),
                description =
                    localizedStringResource(
                        Res.string.inspect_android_view_hierarchies_screenshots_bounds_and_properties,
                        language
                    ),
                actionLabel = localizedStringResource(Res.string.open, language),
                onClick = onOpenLayoutInspector,
            ),
            HomeFeatureEntry(
                title = localizedStringResource(Res.string.cpu_profiler, language),
                subtitle = localizedStringResource(Res.string.cpu_profiling, language),
                description =
                    localizedStringResource(
                        Res.string.simpleperf_based_cpu_sampling_flame_graphs_call_tree_analysis,
                        language
                    ),
                actionLabel = localizedStringResource(Res.string.open, language),
                onClick = onOpenSimpleperf,
            ),
            HomeFeatureEntry(
                title = localizedStringResource(Res.string.trace_analyzer, language),
                subtitle = localizedStringResource(Res.string.system_trace, language),
                description =
                    localizedStringResource(
                        Res.string.perfetto_system_level_trace_capture_with_scheduling_binder_and_graphic,
                        language
                    ),
                actionLabel = localizedStringResource(Res.string.open, language),
                onClick = onOpenPerfetto,
            ),
            HomeFeatureEntry(
                title = localizedStringResource(Res.string.memory_profiler, language),
                subtitle = localizedStringResource(Res.string.memory, language),
                description =
                    localizedStringResource(
                        Res.string.heap_dump_capture_object_statistics_and_class_histogram_analysis,
                        language
                    ),
                actionLabel = localizedStringResource(Res.string.open_af210e3f, language),
                onClick = onOpenMemoryProfiler,
            ),
            HomeFeatureEntry(
                title = localizedStringResource(Res.string.frame_profiler, language),
                subtitle = localizedStringResource(Res.string.frame_timing, language),
                description =
                    localizedStringResource(
                        Res.string.capture_online_or_import_gfxinfo_framestats_to_analyze_frame_timing,
                        language
                    ),
                actionLabel = localizedStringResource(Res.string.open_af210e3f, language),
                onClick = onOpenFrameProfiler,
            ),
            HomeFeatureEntry(
                title = localizedStringResource(Res.string.startup_profiler, language),
                subtitle = localizedStringResource(Res.string.startup, language),
                description =
                    localizedStringResource(
                        Res.string.cold_warm_startup_breakdown_and_baseline_profile_support,
                        language
                    ),
                actionLabel = localizedStringResource(Res.string.open_af210e3f, language),
                onClick = onOpenStartupProfiler,
            ),
            HomeFeatureEntry(
                title = localizedStringResource(Res.string.battery_profiler, language),
                subtitle = localizedStringResource(Res.string.battery, language),
                description =
                    localizedStringResource(
                        Res.string.batterystats_analysis_with_wakelock_alarm_and_network_usage_stats,
                        language
                    ),
                actionLabel = localizedStringResource(Res.string.open_af210e3f, language),
                onClick = onOpenBatteryProfiler,
            ),
            HomeFeatureEntry(
                title = localizedStringResource(Res.string.network_profiler, language),
                subtitle = localizedStringResource(Res.string.network, language),
                description =
                    localizedStringResource(
                        Res.string.http_https_traffic_capture_and_request_timeline_analysis,
                        language
                    ),
                actionLabel = localizedStringResource(Res.string.open_af210e3f, language),
                onClick = onOpenNetworkProfiler,
            ),
            HomeFeatureEntry(
                title = localizedStringResource(Res.string.gpu_inspector, language),
                subtitle = localizedStringResource(Res.string.gpu_agi_integration, language),
                description = localizedStringResource(Res.string.discover_and_launch_android_gpu_inspector_then_index_and_verify, language),
                actionLabel = localizedStringResource(Res.string.open, language),
                onClick = onOpenGpuInspector,
            ),
            HomeFeatureEntry(
                title = localizedStringResource(Res.string.benchmark_regression, language),
                subtitle = localizedStringResource(Res.string.macrobenchmark_regression, language),
                description = localizedStringResource(Res.string.compare_androidx_benchmark_baselines_and_current_results_with_ci_regre, language),
                actionLabel = localizedStringResource(Res.string.open, language),
                onClick = onOpenBenchmarkRegression,
            ),
            HomeFeatureEntry(
                title = localizedStringResource(Res.string.source_workspaces, language),
                subtitle = localizedStringResource(Res.string.source_home_subtitle, language),
                description = localizedStringResource(Res.string.source_home_description, language),
                actionLabel = localizedStringResource(Res.string.open, language),
                onClick = onOpenSourceWorkspaces,
            ),
        )

    val colors = LocalViewerColors.current
    Surface(modifier = Modifier.fillMaxSize(), color = colors.canvasBackground) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = HOME_MAX_CONTENT_WIDTH_DP.dp)
                        .fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = localizedStringResource(Res.string.android_performance_studio, language),
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.primaryText,
                    fontSize = ViewerTypography.homeHero.fontSize,
                    lineHeight = ViewerTypography.homeHero.lineHeight,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = localizedStringResource(Res.string.choose_a_performance_analysis_tool, language),
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.secondaryText,
                    fontSize = ViewerTypography.lead.fontSize,
                    lineHeight = ViewerTypography.cardTitle.lineHeight,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val compact = maxWidth < 500.dp
                    val devicesLabel = localizedStringResource(Res.string.connected_devices, language)
                    val devicesValue = (deviceOverview as? StudioDeviceOverview.Available)?.connectedCount?.toString() ?: "—"
                    val recentLabel = localizedStringResource(Res.string.recent_artifacts_shown, language)
                    val recentValue = recentSnapshot?.takeIf { !loadingRecentItems && it.unavailableSourceCount < it.sourceCount }?.items?.size?.toString() ?: "—"
                    val recentScope = localizedStringResource(Res.string.recent_artifacts_scope, language)
                    if (compact) {
                        Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.sectionGap)) {
                            StudioMetricCard(label = devicesLabel, value = devicesValue, modifier = Modifier.fillMaxWidth())
                            StudioMetricCard(label = recentLabel, value = recentValue, modifier = Modifier.fillMaxWidth(), supportingText = recentScope)
                        }
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.sectionGap)) {
                            StudioMetricCard(label = devicesLabel, value = devicesValue, modifier = Modifier.weight(1f))
                            StudioMetricCard(label = recentLabel, value = recentValue, modifier = Modifier.weight(1f), supportingText = recentScope)
                        }
                    }
                }
            }
            Column(
                modifier =
                    Modifier
                        .widthIn(max = HOME_MAX_CONTENT_WIDTH_DP.dp)
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.Start,
            ) {
                Spacer(Modifier.height(StudioTokens.sectionGap))
                HomeDeviceSummary(
                    overview = deviceOverview,
                    language = language,
                    onRefresh = { deviceRefreshRevision++ },
                )
                Spacer(Modifier.height(StudioTokens.sectionGap))
                StudioPanel(modifier = Modifier.fillMaxWidth()) {
                    StudioPanelHeader(title = localizedStringResource(Res.string.quick_actions, language))
                    Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.sectionGap)) {
                        TextButton(onClick = onOpenTraceFile) {
                            Text(localizedStringResource(Res.string.open_trace_file, language))
                        }
                        TextButton(onClick = onAnalyzeHprof) {
                            Text(localizedStringResource(Res.string.analyze_hprof, language))
                        }
                    }
                }
                Spacer(Modifier.height(StudioTokens.sectionGap))

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val columnCount = homeGridColumnCount(maxWidth.value.toInt())
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        entries.chunked(columnCount).forEach { rowEntries ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                rowEntries.forEach { entry ->
                                    FeatureEntryCard(
                                        entry = entry,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(columnCount - rowEntries.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(StudioTokens.sectionGap))
                StudioDataTable(
                    columns = listOf(
                        StudioTableColumn(localizedStringResource(Res.string.recent_column_artifact, language), 2.4f),
                        StudioTableColumn(localizedStringResource(Res.string.recent_column_feature, language), 1f),
                        StudioTableColumn(localizedStringResource(Res.string.recent_column_action, language), 0.85f),
                    ),
                    title = localizedStringResource(Res.string.recent_artifacts, language),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    when {
                        loadingRecentItems -> StudioLoadingState(
                            title = localizedStringResource(Res.string.recent_artifacts, language),
                            detail = localizedStringResource(Res.string.loading_recent_artifacts, language),
                        )
                        recentSnapshot != null && recentSnapshot?.unavailableSourceCount == recentSnapshot?.sourceCount -> StudioErrorState(
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
                                )
                            }
                            recentSnapshot?.items.orEmpty().forEach { item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = StudioTokens.compactContentPadding),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(2.4f)) {
                                        Text(
                                            text = item.title,
                                            color = colors.primaryText,
                                            fontSize = ViewerTypography.body.fontSize,
                                            lineHeight = ViewerTypography.body.lineHeight,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val subtitle = listOfNotNull(item.packageName, item.capturedAt?.toString()).joinToString(" · ")
                                        if (subtitle.isNotEmpty()) {
                                            Text(
                                                text = subtitle,
                                                color = colors.secondaryText,
                                                fontSize = ViewerTypography.secondary.fontSize,
                                                lineHeight = ViewerTypography.secondary.lineHeight,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    Box(modifier = Modifier.weight(1f)) {
                                        StudioStatusChip(
                                            label = localizedStringResource(
                                                when (item.feature) {
                                                    StudioFeature.LAYOUT -> Res.string.recent_feature_layout
                                                    StudioFeature.CPU -> Res.string.recent_feature_cpu
                                                    StudioFeature.TRACE -> Res.string.recent_feature_trace
                                                    StudioFeature.MEMORY -> Res.string.recent_feature_memory
                                                },
                                                language,
                                            ),
                                            tone = StudioStatusTone.NEUTRAL,
                                        )
                                    }
                                    Box(modifier = Modifier.weight(0.85f), contentAlignment = Alignment.CenterEnd) {
                                        TextButton(onClick = { onOpenRecentArtifact(item) }) {
                                            Text(
                                                text = localizedStringResource(Res.string.open_artifact, language),
                                                color = colors.accent,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
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
                    overview.devices.forEach { device ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = device.displayName,
                                modifier = Modifier.weight(1f),
                                color = colors.primaryText,
                                fontSize = ViewerTypography.body.fontSize,
                                lineHeight = ViewerTypography.body.lineHeight,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            StudioStatusChip(
                                label = localizedStringResource(device.state.labelResource(), language),
                                tone = if (device.online) StudioStatusTone.SUCCESS else StudioStatusTone.WARNING,
                            )
                        }
                    }
                }
            }
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
    val title: String,
    val subtitle: String,
    val description: String,
    val actionLabel: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
)

@Composable
private fun FeatureEntryCard(
    entry: HomeFeatureEntry,
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    val shape = RoundedCornerShape(HOME_CARD_CORNER_RADIUS_DP.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val enabled = entry.enabled
    val containerColor = if (enabled && hovered) colors.selectedRow else colors.panel
    val borderColor =
        if (enabled && hovered) {
            colors.accent.copy(alpha = 0.42f)
        } else {
            colors.border.copy(alpha = 0.72f)
        }
    Column(
        modifier =
            modifier
                .height(HOME_CARD_HEIGHT_DP.dp)
                .shadow(
                    elevation = if (enabled && hovered) 7.dp else 2.dp,
                    shape = shape,
                    clip = false,
                )
                .clip(shape)
                .alpha(if (enabled) 1f else 0.55f)
                .background(containerColor)
                .border(1.dp, borderColor, shape)
                .then(
                    if (enabled) {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = entry.onClick,
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(colors.accent.copy(alpha = if (colors.isDark) 0.24f else 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = entry.title.take(1),
                        color = colors.accent,
                        fontSize = ViewerTypography.cardTitle.fontSize,
                        lineHeight = ViewerTypography.body.lineHeight,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = entry.title,
                        color = colors.primaryText,
                        fontSize = ViewerTypography.metric.fontSize,
                        lineHeight = ViewerTypography.metric.lineHeight,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = entry.subtitle,
                        color = colors.secondaryText,
                        fontSize = ViewerTypography.secondary.fontSize,
                        lineHeight = ViewerTypography.label.lineHeight,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Text(
                text = entry.description,
                color = colors.secondaryText,
                fontSize = ViewerTypography.bodyCompact.fontSize,
                lineHeight = ViewerTypography.bodyCompact.lineHeight,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entry.actionLabel,
                color = colors.accent,
                fontSize = ViewerTypography.bodyCompact.fontSize,
                lineHeight = ViewerTypography.bodyCompact.lineHeight,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "›",
                color = colors.accent,
                fontSize = ViewerTypography.icon.fontSize,
                lineHeight = ViewerTypography.sectionTitle.lineHeight,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
