package com.androidperformancestudio.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.presentation.generated.resources.ViewerRes
import com.androidperformancestudio.profileanalysis.StackChartEmptyReason
import com.androidperformancestudio.storage.PanelProjection
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.localizedStringResource

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun StackChartPanel(
    state: ReportState,
    projection: PanelProjection<com.androidperformancestudio.profileanalysis.StackChartSnapshot>,
    actions: ReportActions,
    style: ViewerColors,
) {
    Column(
        modifier = Modifier.fillMaxSize().testTag("stack-chart-panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (projection) {
            is PanelProjection.Failed ->
                StackChartMessage(
                    "${projection.code}: ${projection.message}",
                    style,
                    actions.onRetryFlameProjection,
                )
            is PanelProjection.Ready -> {
                val snapshot = projection.value
                if (snapshot.blocks.isEmpty()) {
                    StackChartMessage(
                        snapshot.emptyReason.message(currentSimpleperfLanguage()),
                        style,
                        actions.onRetryFlameProjection,
                    )
                } else {
                    val start = state.filter.startNanosInclusive ?: requireNotNull(snapshot.startNanos)
                    val end = state.filter.endNanosExclusive ?: requireNotNull(snapshot.endNanosExclusive)
                    StackChartCanvas(
                        snapshot = snapshot,
                        viewport = StackChartViewport(start, end),
                        selectedBlockId = state.workspace.selections.stackChartBlockId,
                        style = style,
                        onSelect = actions.onSelectStackChartBlock,
                        onCommitRange = { rangeStart, rangeEnd -> actions.onTimeRange(rangeStart, rangeEnd) },
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun StackChartMessage(
    message: String,
    style: ViewerColors,
    onRetry: () -> Unit,
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(message, color = style.secondaryText, fontSize = 11.sp)
        MacOsButton("Retry", onRetry, style)
    }
}

private fun StackChartEmptyReason?.message(language: UiLanguage = UiLanguage.ENGLISH): String =
    when (this) {
        StackChartEmptyReason.NO_SAMPLES ->
            localizedStringResource(ViewerRes.sp_stack_no_samples_collected_empty_state, language)
        StackChartEmptyReason.RANGE_EMPTY ->
            localizedStringResource(ViewerRes.sp_stack_no_samples_in_range_empty_state, language)
        StackChartEmptyReason.FILTERED_ALL ->
            localizedStringResource(ViewerRes.sp_stack_filters_removed_all_samples_empty_state, language)
        null -> localizedStringResource(ViewerRes.sp_stack_no_stack_blocks_empty_state, language)
    }
