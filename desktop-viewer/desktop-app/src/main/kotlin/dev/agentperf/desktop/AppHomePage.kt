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
import androidx.compose.material3.Button
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
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Android Performance Studio",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (chinese) "选择要使用的性能分析工具" else "Choose a performance analysis tool",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
            Row(
                modifier = Modifier.fillMaxWidth().widthIn(max = 960.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                FeatureEntryCard(
                    title = "Layout Inspector",
                    description =
                        if (chinese) {
                            "检查 Android View 层级、截图、边界与属性。"
                        } else {
                            "Inspect Android View hierarchies, screenshots, bounds, and properties."
                        },
                    actionLabel = if (chinese) "进入布局检查" else "Open Layout Inspector",
                    onClick = onOpenLayoutInspector,
                    modifier = Modifier.weight(1f),
                )
                FeatureEntryCard(
                    title = "Simpleperf CPU Profiler",
                    description =
                        if (chinese) {
                            "采集或打开 Simpleperf 会话并分析 CPU 样本。"
                        } else {
                            "Capture or open Simpleperf sessions and analyze CPU samples."
                        },
                    actionLabel = if (chinese) "进入 CPU 分析" else "Open CPU Profiler",
                    onClick = onOpenSimpleperf,
                    modifier = Modifier.weight(1f),
                )
                FeatureEntryCard(
                    title = "Perfetto Trace Analyzer",
                    description =
                        if (chinese) {
                            "在 Perfetto Web UI 中在线打开并分析 Trace 文件。"
                        } else {
                            "Open and analyze trace files online in the Perfetto Web UI."
                        },
                    actionLabel = if (chinese) "打开 Perfetto" else "Open Perfetto",
                    onClick = onOpenPerfetto,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FeatureEntryCard(
    title: String,
    description: String,
    actionLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.height(240.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onClick) {
                Text(actionLabel)
            }
        }
    }
}
