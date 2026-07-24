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
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 48.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Android Performance Studio",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (chinese) "选择要使用的性能分析工具" else "Choose a performance analysis tool",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))

            Row(
                modifier = Modifier.fillMaxWidth().widthIn(max = 960.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                FeatureEntryCard(
                    title = "Layout Inspector",
                    subtitle = if (chinese) "布局检查" else "Layout Inspection",
                    description = if (chinese)
                        "检查 Android View 层级、截图、边界与属性。" else
                        "Inspect Android View hierarchies, screenshots, bounds, and properties.",
                    actionLabel = if (chinese) "进入" else "Open",
                    enabled = true,
                    onClick = onOpenLayoutInspector,
                    modifier = Modifier.weight(1f),
                )
                FeatureEntryCard(
                    title = "CPU Profiler",
                    subtitle = if (chinese) "CPU 分析" else "CPU Profiling",
                    description = if (chinese)
                        "Simpleperf 采集、FlameGraph、CallTree 分析 CPU 样本。" else
                        "Simpleperf-based CPU sampling, flame graphs, call tree analysis.",
                    actionLabel = if (chinese) "进入" else "Open",
                    enabled = true,
                    onClick = onOpenSimpleperf,
                    modifier = Modifier.weight(1f),
                )
                FeatureEntryCard(
                    title = "Trace Analyzer",
                    subtitle = if (chinese) "系统 Trace" else "System Trace",
                    description = if (chinese)
                        "Perfetto 系统级 Trace 采集、调度/Binder/图形管线分析。" else
                        "Perfetto system-level trace capture with scheduling, binder, and graphics analysis.",
                    actionLabel = if (chinese) "进入" else "Open",
                    enabled = true,
                    onClick = onOpenPerfetto,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth().widthIn(max = 960.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                FeatureEntryCard(
                    title = "Memory Profiler",
                    subtitle = if (chinese) "内存分析" else "Memory",
                    description = if (chinese)
                        "堆内存 dump、对象统计与类直方图分析。" else
                        "Heap dump capture, object statistics, and class histogram analysis.",
                    actionLabel = if (chinese) "打开" else "Open",
                    enabled = true,
                    onClick = onOpenMemoryProfiler,
                    modifier = Modifier.weight(1f),
                )
                FeatureEntryCard(
                    title = "Frame Profiler",
                    subtitle = if (chinese) "帧耗时分析" else "Frame Timing",
                    description = if (chinese)
                        "JankStats、FrameMetrics 帧耗时与卡顿分析。" else
                        "JankStats and FrameMetrics for frame timing and jank analysis.",
                    actionLabel = if (chinese) "即将推出" else "Coming Soon",
                    enabled = false,
                    onClick = onOpenFrameProfiler,
                    modifier = Modifier.weight(1f),
                )
                FeatureEntryCard(
                    title = "Startup Profiler",
                    subtitle = if (chinese) "启动分析" else "Startup",
                    description = if (chinese)
                        "冷启动/温启动耗时分解、Baseline Profile 支持。" else
                        "Cold/warm startup breakdown and Baseline Profile support.",
                    actionLabel = if (chinese) "即将推出" else "Coming Soon",
                    enabled = false,
                    onClick = onOpenStartupProfiler,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                FeatureEntryCard(
                    title = "Battery Profiler",
                    subtitle = if (chinese) "电量分析" else "Battery",
                    description = if (chinese)
                        "batterystats 解析、wakelock/alarm/network 使用统计。" else
                        "batterystats analysis with wakelock, alarm, and network usage stats.",
                    actionLabel = if (chinese) "即将推出" else "Coming Soon",
                    enabled = false,
                    onClick = onOpenBatteryProfiler,
                    modifier = Modifier.weight(1f),
                )
                FeatureEntryCard(
                    title = "Network Profiler",
                    subtitle = if (chinese) "网络分析" else "Network",
                    description = if (chinese)
                        "HTTP/HTTPS 流量捕获与请求时间线分析。" else
                        "HTTP/HTTPS traffic capture and request timeline analysis.",
                    actionLabel = if (chinese) "规划中" else "Roadmap",
                    enabled = false,
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FeatureEntryCard(
    title: String,
    subtitle: String,
    description: String,
    actionLabel: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.height(220.dp).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (enabled) 4.dp else 1.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onClick,
                enabled = enabled,
                colors = if (enabled) ButtonDefaults.buttonColors()
                else ButtonDefaults.outlinedButtonColors(),
            ) {
                Text(actionLabel)
            }
        }
    }
}
