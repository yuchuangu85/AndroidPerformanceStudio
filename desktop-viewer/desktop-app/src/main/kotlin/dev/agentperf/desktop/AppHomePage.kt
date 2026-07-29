package dev.agentperf.desktop

import com.androidperformancestudio.ui.localizedStringResource
import dev.agentperf.desktop_app.generated.resources.Res
import dev.agentperf.desktop_app.generated.resources.*

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal const val HOME_GRID_COLUMN_COUNT = 4
internal const val HOME_CARD_HEIGHT_DP = 172
internal const val HOME_ITEM_TITLE_FONT_SIZE_SP = 18

@Composable
fun AppHomePage(
    chinese: Boolean,
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
) {
    val entries =
        listOf(
            HomeFeatureEntry(
                title = localizedStringResource(Res.string.layout_inspector, chinese),
                subtitle = localizedStringResource(Res.string.layout_inspection, chinese),
                description =
                    localizedStringResource(Res.string.inspect_android_view_hierarchies_screenshots_bounds_and_properties, chinese),
                actionLabel = localizedStringResource(Res.string.open, chinese),
                onClick = onOpenLayoutInspector,
            ),
            HomeFeatureEntry(
                title = localizedStringResource(Res.string.cpu_profiler, chinese),
                subtitle = localizedStringResource(Res.string.cpu_profiling, chinese),
                description =
                    localizedStringResource(Res.string.simpleperf_based_cpu_sampling_flame_graphs_call_tree_analysis, chinese),
                actionLabel = localizedStringResource(Res.string.open, chinese),
                onClick = onOpenSimpleperf,
            ),
            HomeFeatureEntry(
                title = localizedStringResource(Res.string.trace_analyzer, chinese),
                subtitle = localizedStringResource(Res.string.system_trace, chinese),
                description =
                    localizedStringResource(Res.string.perfetto_system_level_trace_capture_with_scheduling_binder_and_graphic, chinese),
                actionLabel = localizedStringResource(Res.string.open, chinese),
                onClick = onOpenPerfetto,
            ),
            HomeFeatureEntry(
                title = localizedStringResource(Res.string.memory_profiler, chinese),
                subtitle = localizedStringResource(Res.string.memory, chinese),
                description =
                    localizedStringResource(Res.string.heap_dump_capture_object_statistics_and_class_histogram_analysis, chinese),
                actionLabel = localizedStringResource(Res.string.open_af210e3f, chinese),
                onClick = onOpenMemoryProfiler,
            ),
            HomeFeatureEntry(
                title = localizedStringResource(Res.string.frame_profiler, chinese),
                subtitle = localizedStringResource(Res.string.frame_timing, chinese),
                description =
                    localizedStringResource(Res.string.capture_online_or_import_gfxinfo_framestats_to_analyze_frame_timing, chinese),
                actionLabel = localizedStringResource(Res.string.open_af210e3f, chinese),
                onClick = onOpenFrameProfiler,
            ),
            HomeFeatureEntry(
                title = localizedStringResource(Res.string.startup_profiler, chinese),
                subtitle = localizedStringResource(Res.string.startup, chinese),
                description =
                    localizedStringResource(Res.string.cold_warm_startup_breakdown_and_baseline_profile_support, chinese),
                actionLabel = localizedStringResource(Res.string.open_af210e3f, chinese),
                onClick = onOpenStartupProfiler,
            ),
            HomeFeatureEntry(
                title = localizedStringResource(Res.string.battery_profiler, chinese),
                subtitle = localizedStringResource(Res.string.battery, chinese),
                description =
                    localizedStringResource(Res.string.batterystats_analysis_with_wakelock_alarm_and_network_usage_stats, chinese),
                actionLabel = localizedStringResource(Res.string.open_af210e3f, chinese),
                onClick = onOpenBatteryProfiler,
            ),
            HomeFeatureEntry(
                title = localizedStringResource(Res.string.network_profiler, chinese),
                subtitle = localizedStringResource(Res.string.network, chinese),
                description =
                    localizedStringResource(Res.string.http_https_traffic_capture_and_request_timeline_analysis, chinese),
                actionLabel = localizedStringResource(Res.string.open_af210e3f, chinese),
                onClick = onOpenNetworkProfiler,
            ),
            HomeFeatureEntry(
                title = localizedStringResource(Res.string.gpu_inspector, chinese),
                subtitle = localizedStringResource(Res.string.gpu_agi_integration, chinese),
                description =
                    localizedStringResource(Res.string.discover_and_launch_android_gpu_inspector_then_index_and_verify, chinese),
                actionLabel = localizedStringResource(Res.string.open_af210e3f, chinese),
                onClick = onOpenGpuInspector,
            ),
            HomeFeatureEntry(
                title = localizedStringResource(Res.string.benchmark_regression, chinese),
                subtitle = localizedStringResource(Res.string.macrobenchmark_regression, chinese),
                description =
                    localizedStringResource(Res.string.compare_androidx_benchmark_baselines_and_current_results_with_ci_regre, chinese),
                actionLabel = localizedStringResource(Res.string.open_af210e3f, chinese),
                onClick = onOpenBenchmarkRegression,
            ),
        )

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = localizedStringResource(Res.string.android_performance_studio, chinese),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = localizedStringResource(Res.string.choose_a_performance_analysis_tool, chinese),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
)

@Composable
private fun FeatureEntryCard(
    entry: HomeFeatureEntry,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.height(HOME_CARD_HEIGHT_DP.dp).clickable(onClick = entry.onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontSize = HOME_ITEM_TITLE_FONT_SIZE_SP.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = entry.onClick,
                colors = ButtonDefaults.buttonColors(),
            ) {
                Text(entry.actionLabel)
            }
        }
    }
}
