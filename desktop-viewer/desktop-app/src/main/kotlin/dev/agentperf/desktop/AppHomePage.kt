package dev.agentperf.desktop

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

internal const val HOME_GRID_COLUMN_COUNT = 4
internal const val HOME_CARD_HEIGHT_DP = 172

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
                title = "Layout Inspector",
                subtitle = if (chinese) "布局检查" else "Layout Inspection",
                description =
                    if (chinese) {
                        "检查 Android View 层级、截图、边界与属性。"
                    } else {
                        "Inspect Android View hierarchies, screenshots, bounds, and properties."
                    },
                actionLabel = if (chinese) "进入" else "Open",
                onClick = onOpenLayoutInspector,
            ),
            HomeFeatureEntry(
                title = "CPU Profiler",
                subtitle = if (chinese) "CPU 分析" else "CPU Profiling",
                description =
                    if (chinese) {
                        "Simpleperf 采集、FlameGraph、CallTree 分析 CPU 样本。"
                    } else {
                        "Simpleperf-based CPU sampling, flame graphs, call tree analysis."
                    },
                actionLabel = if (chinese) "进入" else "Open",
                onClick = onOpenSimpleperf,
            ),
            HomeFeatureEntry(
                title = "Trace Analyzer",
                subtitle = if (chinese) "系统 Trace" else "System Trace",
                description =
                    if (chinese) {
                        "Perfetto 系统级 Trace 采集、调度/Binder/图形管线分析。"
                    } else {
                        "Perfetto system-level trace capture with scheduling, binder, and graphics analysis."
                    },
                actionLabel = if (chinese) "进入" else "Open",
                onClick = onOpenPerfetto,
            ),
            HomeFeatureEntry(
                title = "Memory Profiler",
                subtitle = if (chinese) "内存分析" else "Memory",
                description =
                    if (chinese) {
                        "堆内存 dump、对象统计与类直方图分析。"
                    } else {
                        "Heap dump capture, object statistics, and class histogram analysis."
                    },
                actionLabel = if (chinese) "打开" else "Open",
                onClick = onOpenMemoryProfiler,
            ),
            HomeFeatureEntry(
                title = "Frame Profiler",
                subtitle = if (chinese) "帧耗时分析" else "Frame Timing",
                description =
                    if (chinese) {
                        "在线采集或导入 gfxinfo FrameStats，分析帧耗时与卡顿区间。"
                    } else {
                        "Capture online or import gfxinfo FrameStats to analyze frame timing and jank clusters."
                    },
                actionLabel = if (chinese) "打开" else "Open",
                onClick = onOpenFrameProfiler,
            ),
            HomeFeatureEntry(
                title = "Startup Profiler",
                subtitle = if (chinese) "启动分析" else "Startup",
                description =
                    if (chinese) {
                        "冷启动/温启动耗时分解、Baseline Profile 支持。"
                    } else {
                        "Cold/warm startup breakdown and Baseline Profile support."
                    },
                actionLabel = if (chinese) "打开" else "Open",
                onClick = onOpenStartupProfiler,
            ),
            HomeFeatureEntry(
                title = "Battery Profiler",
                subtitle = if (chinese) "电量分析" else "Battery",
                description =
                    if (chinese) {
                        "batterystats 解析、wakelock/alarm/network 使用统计。"
                    } else {
                        "batterystats analysis with wakelock, alarm, and network usage stats."
                    },
                actionLabel = if (chinese) "打开" else "Open",
                onClick = onOpenBatteryProfiler,
            ),
            HomeFeatureEntry(
                title = "Network Profiler",
                subtitle = if (chinese) "网络分析" else "Network",
                description =
                    if (chinese) {
                        "HTTP/HTTPS 流量捕获与请求时间线分析。"
                    } else {
                        "HTTP/HTTPS traffic capture and request timeline analysis."
                    },
                actionLabel = if (chinese) "打开" else "Open",
                onClick = onOpenNetworkProfiler,
            ),
            HomeFeatureEntry(
                title = "GPU Inspector",
                subtitle = if (chinese) "GPU / AGI 集成" else "GPU / AGI Integration",
                description =
                    if (chinese) {
                        "探测并启动 Android GPU Inspector，索引和校验 GPU 分析产物。"
                    } else {
                        "Discover and launch Android GPU Inspector, then index and verify GPU artifacts."
                    },
                actionLabel = if (chinese) "打开" else "Open",
                onClick = onOpenGpuInspector,
            ),
            HomeFeatureEntry(
                title = "Benchmark Regression",
                subtitle = if (chinese) "Macrobenchmark 回归" else "Macrobenchmark Regression",
                description =
                    if (chinese) {
                        "比较 AndroidX Benchmark 基线与当前结果，并生成 CI 回归报告。"
                    } else {
                        "Compare AndroidX Benchmark baselines and current results with CI regression reports."
                    },
                actionLabel = if (chinese) "打开" else "Open",
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
                text = "Android Performance Studio",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (chinese) "选择要使用的性能分析工具" else "Choose a performance analysis tool",
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
