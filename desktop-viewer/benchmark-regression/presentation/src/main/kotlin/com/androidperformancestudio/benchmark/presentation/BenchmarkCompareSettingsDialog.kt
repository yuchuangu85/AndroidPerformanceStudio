@file:Suppress("FunctionNaming", "LongMethod")

package com.androidperformancestudio.benchmark.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.benchmark.model.EvidenceConfidence
import com.androidperformancestudio.benchmark.model.ReleaseGatePolicy
import com.androidperformancestudio.benchmark.model.RegressionPolicy
import com.androidperformancestudio.benchmark.presentation.generated.resources.*
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTextField
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioTokens

/**
 * Edits the comparison policy and the versioned release gate policy. Both are exported with the
 * report, so the dialog is the only place where a gate decision can change.
 */
@Composable
public fun BenchmarkCompareSettingsDialog(
    regressionPolicy: RegressionPolicy,
    gatePolicy: ReleaseGatePolicy,
    language: UiLanguage,
    onDismiss: () -> Unit,
    onApply: (RegressionPolicy, ReleaseGatePolicy) -> Unit,
) {
    val colors = LocalViewerColors.current
    var relativeThreshold by remember { mutableStateOf(regressionPolicy.relativeThresholdPercent?.toString().orEmpty()) }
    var absoluteThreshold by remember { mutableStateOf(regressionPolicy.absoluteThreshold?.toString().orEmpty()) }
    var minimumSamples by remember { mutableStateOf(regressionPolicy.minimumSampleCount.toString()) }
    var policyVersion by remember { mutableStateOf(gatePolicy.policyVersion) }
    var maxRegressions by remember { mutableStateOf(gatePolicy.maxAllowedRegressions.toString()) }
    var blockingPatterns by remember { mutableStateOf(gatePolicy.blockingMetricPatterns.joinToString(", ")) }
    var minimumConfidence by remember { mutableStateOf(gatePolicy.minimumConfidence) }
    var failOnRegression by remember { mutableStateOf(gatePolicy.failOnRegression) }
    var allowInconclusive by remember { mutableStateOf(gatePolicy.allowInconclusive) }
    // Resolved in composable context because DropdownSelector's itemLabel is a plain function.
    val confidenceLabels = HashMap<EvidenceConfidence, String>()
    for (confidence in EvidenceConfidence.entries) {
        confidenceLabels[confidence] = confidence.displayName(language)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = localizedStringResource(Res.string.compare_settings, language),
                color = MaterialTheme.colorScheme.primary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap)) {
                ViewerTextField(
                    value = relativeThreshold,
                    onValueChange = { relativeThreshold = it },
                    label = { Text(localizedStringResource(Res.string.settings_threshold_percent, language)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ViewerTextField(
                    value = absoluteThreshold,
                    onValueChange = { absoluteThreshold = it },
                    label = { Text(localizedStringResource(Res.string.settings_absolute_threshold, language)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ViewerTextField(
                    value = maximumOf(minimumSamples.toIntOrNull(), 1).toString(),
                    onValueChange = { minimumSamples = it },
                    label = { Text(localizedStringResource(Res.string.inspector_samples, language, "")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ViewerTextField(
                    value = policyVersion,
                    onValueChange = { policyVersion = it },
                    label = { Text(localizedStringResource(Res.string.policy_version_label, language)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ViewerTextField(
                    value = maxRegressions,
                    onValueChange = { maxRegressions = it },
                    label = { Text(localizedStringResource(Res.string.settings_max_regressions, language)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ViewerTextField(
                    value = blockingPatterns,
                    onValueChange = { blockingPatterns = it },
                    label = { Text(localizedStringResource(Res.string.settings_blocking_metrics, language)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = localizedStringResource(Res.string.settings_min_confidence, language),
                        color = colors.secondaryText,
                        modifier = Modifier.width(180.dp),
                    )
                    DropdownSelector(
                        items = EvidenceConfidence.entries,
                        selectedItem = minimumConfidence,
                        onItemSelected = { minimumConfidence = it },
                        itemLabel = { confidence -> confidenceLabels[confidence].orEmpty() },
                        placeholder = "",
                        modifier = Modifier.weight(1f),
                        fillWidth = true,
                    )
                }
                ToggleRow(
                    label = localizedStringResource(Res.string.settings_fail_on_regression, language),
                    checked = failOnRegression,
                    onCheckedChange = { failOnRegression = it },
                )
                ToggleRow(
                    label = localizedStringResource(Res.string.settings_allow_inconclusive, language),
                    checked = allowInconclusive,
                    onCheckedChange = { allowInconclusive = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(
                        RegressionPolicy(
                            relativeThresholdPercent = relativeThreshold.toDoubleOrNull(),
                            absoluteThreshold = absoluteThreshold.toDoubleOrNull(),
                            minimumSampleCount = maximumOf(minimumSamples.toIntOrNull(), 1),
                        ),
                        ReleaseGatePolicy(
                            policyVersion = policyVersion.ifBlank { "1" },
                            blockingMetricPatterns =
                                blockingPatterns.split(',').map(String::trim).filter(String::isNotEmpty),
                            failOnRegression = failOnRegression,
                            maxAllowedRegressions = maximumOf(maxRegressions.toIntOrNull(), 0),
                            allowInconclusive = allowInconclusive,
                            minimumConfidence = minimumConfidence,
                        ),
                    )
                },
            ) {
                Text(localizedStringResource(Res.string.apply, language))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(localizedStringResource(Res.string.cancel, language))
            }
        },
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = LocalViewerColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = label, color = colors.secondaryText)
    }
}

private fun maximumOf(
    value: Int?,
    minimum: Int,
): Int = maxOf(value ?: minimum, minimum)
