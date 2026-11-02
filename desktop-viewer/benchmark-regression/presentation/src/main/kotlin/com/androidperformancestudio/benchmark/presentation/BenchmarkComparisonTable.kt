@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber")

package com.androidperformancestudio.benchmark.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.benchmark.model.EvidenceConfidence
import com.androidperformancestudio.benchmark.model.MetricComparison
import com.androidperformancestudio.benchmark.model.RegressionClassification
import com.androidperformancestudio.benchmark.presentation.generated.resources.*
import com.androidperformancestudio.ui.CopyableText
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioDataTable
import com.androidperformancestudio.ui.studio.StudioSearchField
import com.androidperformancestudio.ui.studio.StudioStatusChip
import com.androidperformancestudio.ui.studio.StudioStatusTone
import com.androidperformancestudio.ui.studio.StudioTableColumn
import com.androidperformancestudio.ui.studio.StudioTokens
import java.util.Locale

private val COMPARISON_COLUMN_INDICES = listOf(0 to 2.4f, 1 to 1f, 2 to 1f, 3 to 1.2f, 4 to 1f, 5 to 1f, 6 to 1.2f)

@Composable
internal fun BenchmarkComparisonTable(
    state: BenchmarkReportsState,
    language: UiLanguage,
    onQueryChange: (String) -> Unit,
    onSeverityFilterChange: (RegressionSeverity?) -> Unit,
    onClassificationFilterChange: (RegressionClassification?) -> Unit,
    onSelectComparison: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    val rows = state.visibleComparisons()
    StudioDataTable(
        columns = COMPARISON_COLUMN_INDICES.map { (index, weight) -> StudioTableColumn(columnLabel(index, language), weight) },
        modifier = modifier,
        title = localizedStringResource(Res.string.comparison_results, language),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = StudioTokens.panelGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
        ) {
            StudioSearchField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = localizedStringResource(Res.string.search_benchmarks, language),
                modifier = Modifier.weight(1f),
            )
            SeverityFilter(state, language, onSeverityFilterChange)
            ClassificationFilter(state, language, onClassificationFilterChange)
        }
        if (rows.isEmpty()) {
            CopyableText(
                text = localizedStringResource(Res.string.no_comparisons, language),
                color = colors.secondaryText,
                fontSize = ViewerTypography.bodyCompact.fontSize,
                lineHeight = ViewerTypography.bodyCompact.lineHeight,
                language = language,
            )
            return@StudioDataTable
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            rows.forEachIndexed { index, comparison ->
                val key = comparisonKey(comparison)
                ComparisonRow(
                    comparison = comparison,
                    language = language,
                    selected = key == state.selectedComparisonKey,
                    striped = index % 2 == 1,
                    onClick = { onSelectComparison(if (key == state.selectedComparisonKey) null else key) },
                )
            }
        }
    }
}

@Composable
private fun SeverityFilter(
    state: BenchmarkReportsState,
    language: UiLanguage,
    onSeverityFilterChange: (RegressionSeverity?) -> Unit,
) {
    FilterMenu(
        label = state.severityFilter?.displayName(language) ?: localizedStringResource(Res.string.filter_severity, language),
        options =
            listOf(
                localizedStringResource(Res.string.filter_severity, language) to { onSeverityFilterChange(null) },
                localizedStringResource(Res.string.severity_high, language) to { onSeverityFilterChange(RegressionSeverity.HIGH) },
                localizedStringResource(Res.string.severity_low, language) to { onSeverityFilterChange(RegressionSeverity.LOW) },
                localizedStringResource(Res.string.severity_none, language) to { onSeverityFilterChange(RegressionSeverity.NONE) },
                localizedStringResource(Res.string.severity_unknown, language) to { onSeverityFilterChange(RegressionSeverity.UNKNOWN) },
                localizedStringResource(Res.string.severity_incompatible, language) to {
                    onSeverityFilterChange(RegressionSeverity.INCOMPATIBLE)
                },
            ),
    )
}

@Composable
private fun ClassificationFilter(
    state: BenchmarkReportsState,
    language: UiLanguage,
    onClassificationFilterChange: (RegressionClassification?) -> Unit,
) {
    FilterMenu(
        label = state.classificationFilter?.displayName(language)
            ?: localizedStringResource(Res.string.filter_classification, language),
        options =
            listOf(
                localizedStringResource(Res.string.filter_classification, language) to {
                    onClassificationFilterChange(null)
                },
                localizedStringResource(Res.string.classification_regressed, language) to {
                    onClassificationFilterChange(RegressionClassification.REGRESSED)
                },
                localizedStringResource(Res.string.classification_improved, language) to {
                    onClassificationFilterChange(RegressionClassification.IMPROVED)
                },
                localizedStringResource(Res.string.classification_stable, language) to {
                    onClassificationFilterChange(RegressionClassification.STABLE)
                },
                localizedStringResource(Res.string.classification_inconclusive, language) to {
                    onClassificationFilterChange(RegressionClassification.INCONCLUSIVE)
                },
                localizedStringResource(Res.string.classification_incompatible, language) to {
                    onClassificationFilterChange(RegressionClassification.INCOMPATIBLE)
                },
            ),
    )
}

/** Compact text menu so the table filters reuse the existing surface instead of a new selector style. */
@Composable
private fun FilterMenu(
    label: String,
    options: List<Pair<String, () -> Unit>>,
) {
    val colors = LocalViewerColors.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text(
            text = label,
            modifier =
                Modifier
                    .clickable { expanded = true }
                    .background(colors.sectionBackground)
                    .padding(horizontal = StudioTokens.compactContentPadding, vertical = 6.dp),
            color = colors.secondaryText,
            fontSize = ViewerTypography.label.fontSize,
            lineHeight = ViewerTypography.label.lineHeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (optionLabel, action) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        expanded = false
                        action()
                    },
                )
            }
        }
    }
}

@Composable
private fun ComparisonRow(
    comparison: MetricComparison,
    language: UiLanguage,
    selected: Boolean,
    striped: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalViewerColors.current
    val severity = severityOf(comparison)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    when {
                        selected -> colors.selectedRow
                        striped -> colors.detailRowLight
                        else -> colors.transparent
                    },
                )
                .clickable(onClick = onClick)
                .padding(horizontal = StudioTokens.compactContentPadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cell(2.4f) {
            Column {
                Text(
                    text = comparison.metricName,
                    color = colors.primaryText,
                    fontSize = ViewerTypography.bodyCompact.fontSize,
                    lineHeight = ViewerTypography.bodyCompact.lineHeight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = comparison.caseIdentity,
                    color = colors.mutedText,
                    fontSize = ViewerTypography.label.fontSize,
                    lineHeight = ViewerTypography.label.lineHeight,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Cell(1f) { MetricValue(comparison.baselineValue, comparison.unit, colors.secondaryText) }
        Cell(1f) { MetricValue(comparison.currentValue, comparison.unit, colors.primaryText) }
        Cell(1.2f) { DeltaCell(comparison, colors) }
        Cell(1f) { StudioStatusChip(severity.displayName(language), severity.tone) }
        Cell(1f) {
            Text(
                text = comparison.confidence.displayName(language),
                color = colors.secondaryText,
                fontSize = ViewerTypography.label.fontSize,
                lineHeight = ViewerTypography.label.lineHeight,
            )
        }
        Cell(1.2f) {
            Text(
                text = comparison.classification.displayName(language),
                color = comparison.classification.textColor(colors),
                fontSize = ViewerTypography.label.fontSize,
                lineHeight = ViewerTypography.label.lineHeight,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RowScope.Cell(
    weight: Float,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.weight(weight)) { content() }
}

@Composable
private fun MetricValue(value: Double?, unit: String, color: Color) {
    Text(
        text = formatMetricValue(value, unit),
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = ViewerTypography.bodyCompact.fontSize,
        lineHeight = ViewerTypography.bodyCompact.lineHeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun DeltaCell(comparison: MetricComparison, colors: ViewerColors) {
    val delta = comparison.relativeDeltaPercent
    if (delta == null) {
        Text("—", color = MaterialTheme.colorScheme.outline, fontSize = ViewerTypography.bodyCompact.fontSize)
        return
    }
    val arrow = if (delta >= 0) "↑" else "↓"
    val sign = if (delta > 0) "+" else ""
    Text(
        text = sign + "%.1f".format(Locale.ROOT, delta) + "% " + arrow,
        color = comparison.classification.textColor(colors),
        fontFamily = FontFamily.Monospace,
        fontSize = ViewerTypography.bodyCompact.fontSize,
        lineHeight = ViewerTypography.bodyCompact.lineHeight,
        maxLines = 1,
    )
}

internal fun formatMetricValue(value: Double?, unit: String): String =
    when {
        value == null -> "—"
        unit.isBlank() -> "%.4g".format(Locale.ROOT, value)
        else -> "%.4g %s".format(Locale.ROOT, value, unit)
    }

@Composable
internal fun RegressionSeverity.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            RegressionSeverity.HIGH -> Res.string.severity_high
            RegressionSeverity.LOW -> Res.string.severity_low
            RegressionSeverity.NONE -> Res.string.severity_none
            RegressionSeverity.UNKNOWN -> Res.string.severity_unknown
            RegressionSeverity.INCOMPATIBLE -> Res.string.severity_incompatible
        },
        language,
    )

internal val RegressionSeverity.tone: StudioStatusTone
    get() =
        when (this) {
            RegressionSeverity.HIGH, RegressionSeverity.INCOMPATIBLE -> StudioStatusTone.ERROR
            RegressionSeverity.LOW -> StudioStatusTone.WARNING
            RegressionSeverity.UNKNOWN -> StudioStatusTone.INFO
            RegressionSeverity.NONE -> StudioStatusTone.NEUTRAL
        }

@Composable
internal fun RegressionClassification.displayName(language: UiLanguage): String =
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

@Composable
internal fun EvidenceConfidence.displayName(language: UiLanguage): String =
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

internal fun RegressionClassification.textColor(colors: ViewerColors): Color =
    when (this) {
        RegressionClassification.REGRESSED -> colors.error
        RegressionClassification.IMPROVED -> colors.success
        RegressionClassification.INCOMPATIBLE -> colors.error
        RegressionClassification.INCONCLUSIVE -> colors.warning
        RegressionClassification.STABLE -> colors.secondaryText
    }

@Composable
private fun columnLabel(index: Int, language: UiLanguage): String =
    localizedStringResource(
        when (index) {
            0 -> Res.string.column_benchmark
            1 -> Res.string.column_baseline
            2 -> Res.string.column_current
            3 -> Res.string.column_delta
            4 -> Res.string.column_severity
            5 -> Res.string.column_confidence
            else -> Res.string.column_classification
        },
        language,
    )
