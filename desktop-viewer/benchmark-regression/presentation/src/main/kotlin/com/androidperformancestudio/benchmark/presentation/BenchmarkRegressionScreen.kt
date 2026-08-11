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
import com.androidperformancestudio.benchmark.model.EvidenceConfidence
import com.androidperformancestudio.benchmark.model.RegressionClassification
import com.androidperformancestudio.benchmark.model.RegressionReport
import com.androidperformancestudio.benchmark.presentation.generated.resources.*
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

public data class BenchmarkRegressionState(
    val current: BenchmarkRun? = null,
    val baseline: BenchmarkRun? = null,
    val report: RegressionReport? = null,
    val thresholdPercent: Double? = null,
    val message: String? = null,
    val error: String? = null,
)

@Composable
public fun BenchmarkRegressionScreen(state: BenchmarkRegressionState, language: UiLanguage, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SummaryCard(localizedStringResource(Res.string.current, language), state.current?.sourceFile?.fileName?.toString() ?: "—", Modifier.weight(1f))
            SummaryCard(localizedStringResource(Res.string.baseline, language), state.baseline?.sourceFile?.fileName?.toString() ?: "—", Modifier.weight(1f))
            SummaryCard(localizedStringResource(Res.string.regressions, language), state.report?.regressionCount?.toString() ?: "—", Modifier.weight(1f))
            SummaryCard(
                localizedStringResource(Res.string.threshold, language),
                state.thresholdPercent?.let { "$it%" }
                    ?: localizedStringResource(Res.string.not_configured, language),
                Modifier.weight(1f),
            )
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            localizedStringResource(Res.string.metric_comparisons, language),
            style = MaterialTheme.typography.titleLarge,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.report?.comparisons.orEmpty()) { comparison ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            localizedStringResource(
                                Res.string.text,
                                language,
                                comparison.classification.displayName(language),
                                comparison.caseIdentity,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            localizedStringResource(
                                Res.string.metric_values,
                                language,
                                comparison.metricName,
                                comparison.unit,
                                comparison.baselineValue ?: "—",
                                comparison.currentValue ?: "—",
                            ),
                        )
                        Text(
                            localizedStringResource(
                                Res.string.metric_delta,
                                language,
                                comparison.absoluteDelta ?: "—",
                                comparison.relativeDeltaPercent?.let { "%.2f%%".format(it) } ?: "—",
                                comparison.confidence.displayName(language),
                            ),
                        )
                        comparison.reasons.forEach { reason ->
                            Text(
                                localizedReason(reason, language),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun RegressionClassification.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            RegressionClassification.REGRESSED -> Res.string.classification_regressed
            RegressionClassification.IMPROVED -> Res.string.classification_improved
            RegressionClassification.STABLE -> Res.string.classification_stable
            RegressionClassification.INCONCLUSIVE -> Res.string.classification_inconclusive
            RegressionClassification.INCOMPATIBLE -> Res.string.classification_incompatible
        },
        language,
    )

private fun EvidenceConfidence.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            EvidenceConfidence.EXACT -> Res.string.confidence_exact
            EvidenceConfidence.DERIVED -> Res.string.confidence_derived
            EvidenceConfidence.INFERRED -> Res.string.confidence_inferred
            EvidenceConfidence.PARTIAL -> Res.string.confidence_partial
            EvidenceConfidence.UNKNOWN -> Res.string.confidence_unknown
        },
        language,
    )

private fun localizedReason(
    reason: String,
    language: UiLanguage,
): String =
    when {
        reason.startsWith(INCOMPATIBLE_ENVIRONMENTS_PREFIX) ->
            localizedStringResource(
                Res.string.reason_incompatible_environments,
                language,
                reason.removePrefix(INCOMPATIBLE_ENVIRONMENTS_PREFIX),
            )
        reason == NO_BASELINE_METRIC_REASON ->
            localizedStringResource(Res.string.reason_no_baseline_metric, language)
        reason.startsWith(METRIC_UNITS_DIFFER_PREFIX) && reason.endsWith(").") -> {
            val units = reason.removePrefix(METRIC_UNITS_DIFFER_PREFIX).removeSuffix(").").split(" vs ", limit = 2)
            if (units.size == 2) {
                localizedStringResource(Res.string.reason_metric_units_differ, language, units[0], units[1])
            } else {
                reason
            }
        }
        reason == UNKNOWN_METRIC_DIRECTION_REASON ->
            localizedStringResource(Res.string.reason_unknown_metric_direction, language)
        reason == INSUFFICIENT_SAMPLES_REASON ->
            localizedStringResource(Res.string.reason_insufficient_samples, language)
        else -> reason
    }

private const val INCOMPATIBLE_ENVIRONMENTS_PREFIX = "Run environments are incompatible: "
private const val NO_BASELINE_METRIC_REASON = "No baseline metric with the same case and metric identity."
private const val METRIC_UNITS_DIFFER_PREFIX = "Metric units differ ("
private const val UNKNOWN_METRIC_DIRECTION_REASON = "Metric direction is unknown and requires project configuration."
private const val INSUFFICIENT_SAMPLES_REASON = "Insufficient raw samples for a high-confidence gate."

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}
