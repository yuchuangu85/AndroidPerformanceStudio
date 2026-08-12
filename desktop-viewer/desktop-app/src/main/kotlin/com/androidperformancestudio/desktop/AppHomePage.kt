package com.androidperformancestudio.desktop

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ProfilerCompactButton

internal const val HOME_GRID_COLUMN_COUNT = 4
internal const val HOME_CARD_HEIGHT_DP = 172
internal const val HOME_ITEM_TITLE_FONT_SIZE_SP = 18

@Composable
fun AppHomePage(
    language: UiLanguage,
    onOpenSourceWorkspaces: () -> Unit,
    onOpenLayoutInspector: () -> Unit,
    onOpenSimpleperf: () -> Unit,
    onOpenPerfetto: () -> Unit,
    onOpenWinscope: () -> Unit,
    onOpenMemoryProfiler: () -> Unit,
    onOpenFrameProfiler: () -> Unit,
    onOpenStartupProfiler: () -> Unit,
    onOpenBatteryProfiler: () -> Unit,
    onOpenNetworkProfiler: () -> Unit,
    onOpenGpuInspector: () -> Unit,
    onOpenBenchmarkRegression: () -> Unit,
    onOpenMethodRecording: () -> Unit,
) {
    val entries =
        listOf(
            HomeFeatureEntry(
                title = localizedStringResource(Res.string.source_workspaces, language),
                subtitle = localizedStringResource(Res.string.source_home_subtitle, language),
                description = localizedStringResource(Res.string.source_home_description, language),
                actionLabel = localizedStringResource(Res.string.open, language),
                onClick = onOpenSourceWorkspaces,
            ),
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
                title = localizedStringResource(Res.string.winscope, language),
                subtitle = localizedStringResource(Res.string.window_system_trace, language),
                description = localizedStringResource(Res.string.winscope_home_description, language),
                actionLabel = localizedStringResource(Res.string.open, language),
                onClick = onOpenWinscope,
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
        )

    val colors = LocalViewerColors.current
    Surface(modifier = Modifier.fillMaxSize(), color = colors.canvasBackground) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = localizedStringResource(Res.string.android_performance_studio, language),
                color = colors.primaryText,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = localizedStringResource(Res.string.choose_a_performance_analysis_tool, language),
                color = colors.secondaryText,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(18.dp))

            entries.chunked(HOME_GRID_COLUMN_COUNT).forEachIndexed { rowIndex, rowEntries ->
                Row(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 1200.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rowEntries.forEach { entry ->
                        FeatureEntryCard(
                            entry = entry,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(HOME_GRID_COLUMN_COUNT - rowEntries.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
                if (rowIndex < entries.lastIndex / HOME_GRID_COLUMN_COUNT) {
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
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
    val shape = RoundedCornerShape(8.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val enabled = entry.enabled
    val containerColor = if (enabled && hovered) colors.sectionBackground else colors.panel
    Column(
        modifier =
            modifier
                .height(HOME_CARD_HEIGHT_DP.dp)
                .clip(shape)
                .alpha(if (enabled) 1f else 0.55f)
                .background(containerColor)
                .border(1.dp, colors.border, shape)
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
                .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = entry.title,
                color = colors.primaryText,
                fontSize = HOME_ITEM_TITLE_FONT_SIZE_SP.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = entry.subtitle,
                color = colors.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = entry.description,
                color = colors.secondaryText,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
        ProfilerCompactButton(
            text = entry.actionLabel,
            onClick = entry.onClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        )
    }
}
