@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod", "MagicNumber")

package com.androidperformancestudio.benchmark.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.benchmark.model.BenchmarkRun
import com.androidperformancestudio.benchmark.model.RegressionReport

public data class BenchmarkRegressionState(
    val current: BenchmarkRun? = null,
    val baseline: BenchmarkRun? = null,
    val report: RegressionReport? = null,
    val thresholdPercent: Double? = null,
    val message: String? = null,
    val error: String? = null,
)

@Composable
public fun BenchmarkRegressionScreen(state: BenchmarkRegressionState, chinese: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SummaryCard(if (chinese) "当前结果" else "Current", state.current?.sourceFile?.fileName?.toString() ?: "—", Modifier.weight(1f))
            SummaryCard(if (chinese) "基线" else "Baseline", state.baseline?.sourceFile?.fileName?.toString() ?: "—", Modifier.weight(1f))
            SummaryCard(if (chinese) "回归数" else "Regressions", state.report?.regressionCount?.toString() ?: "—", Modifier.weight(1f))
            SummaryCard(if (chinese) "阈值" else "Threshold", state.thresholdPercent?.let { "$it%" } ?: if (chinese) "未配置" else "Not configured", Modifier.weight(1f))
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(if (chinese) "指标比较" else "Metric comparisons", style = MaterialTheme.typography.titleLarge)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.report?.comparisons.orEmpty()) { comparison ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${comparison.classification} · ${comparison.caseIdentity}", style = MaterialTheme.typography.titleMedium)
                        Text("${comparison.metricName} (${comparison.unit}) · ${comparison.baselineValue ?: "—"} → ${comparison.currentValue ?: "—"}")
                        Text("Δ ${comparison.absoluteDelta ?: "—"} · ${comparison.relativeDeltaPercent?.let { "%.2f%%".format(it) } ?: "—"} · ${comparison.confidence}")
                        comparison.reasons.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}
