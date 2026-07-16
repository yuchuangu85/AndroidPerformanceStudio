package com.androidperformancestudio.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.profileanalysis.FlameGraphEmptyReason
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.profileanalysis.ImplementationFilter

internal enum class FlameGraphRecoveryAction {
    SELECT_ALL_THREADS,
    RESET_TIME_RANGE,
    CANCEL_PREVIEW,
    CLEAR_SEARCH,
    SHOW_ALL_IMPLEMENTATIONS,
    UNDO_TRANSFORM,
    REVIEW_DATA_QUALITY,
    RETRY_PROJECTION,
}

internal data class FlameGraphEmptyStateContent(
    val message: String,
    val recoveryLabel: String,
    val recoveryAction: FlameGraphRecoveryAction,
    val diagnosticDetails: String?,
)

internal fun flameGraphEmptyStateContent(
    reason: FlameGraphEmptyReason,
    diagnosticDetails: String?,
): FlameGraphEmptyStateContent =
    when (reason) {
        FlameGraphEmptyReason.THREAD_HAS_NO_SAMPLES ->
            content(
                "The selected thread has no samples.",
                "Show all threads",
                FlameGraphRecoveryAction.SELECT_ALL_THREADS,
                diagnosticDetails,
            )
        FlameGraphEmptyReason.COMMITTED_RANGE_EMPTY ->
            content(
                "The selected time range contains no samples.",
                "Reset time range",
                FlameGraphRecoveryAction.RESET_TIME_RANGE,
                diagnosticDetails,
            )
        FlameGraphEmptyReason.PREVIEW_RANGE_EMPTY ->
            content(
                "The preview range contains no samples.",
                "Cancel preview",
                FlameGraphRecoveryAction.CANCEL_PREVIEW,
                diagnosticDetails,
            )
        FlameGraphEmptyReason.SEARCH_FILTERED_ALL ->
            content(
                "Search removed all samples.",
                "Clear search",
                FlameGraphRecoveryAction.CLEAR_SEARCH,
                diagnosticDetails,
            )
        FlameGraphEmptyReason.IMPLEMENTATION_FILTERED_ALL ->
            content(
                "The implementation filter removed all samples.",
                "Show all implementations",
                FlameGraphRecoveryAction.SHOW_ALL_IMPLEMENTATIONS,
                diagnosticDetails,
            )
        FlameGraphEmptyReason.TRANSFORMS_FILTERED_ALL ->
            content(
                "Stack transforms removed all samples.",
                "Undo transform",
                FlameGraphRecoveryAction.UNDO_TRANSFORM,
                diagnosticDetails,
            )
        FlameGraphEmptyReason.PROFILE_INCOMPLETE ->
            content(
                "The profile does not contain complete call stacks.",
                "Review data quality",
                FlameGraphRecoveryAction.REVIEW_DATA_QUALITY,
                diagnosticDetails,
            )
        FlameGraphEmptyReason.PROJECTION_FAILED ->
            content(
                "The flame graph could not be projected.",
                "Retry projection",
                FlameGraphRecoveryAction.RETRY_PROJECTION,
                diagnosticDetails,
            )
    }

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun FlameGraphEmptyState(
    snapshot: FlameGraphSnapshot,
    actions: ReportActions,
    modifier: Modifier = Modifier,
) {
    val reason = snapshot.emptyReason ?: return
    val content = flameGraphEmptyStateContent(reason, snapshot.diagnosticDetails)
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(content.message, style = MaterialTheme.typography.titleMedium)
        content.diagnosticDetails?.takeIf(String::isNotBlank)?.let { details ->
            Text(details, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { dispatchRecovery(content.recoveryAction, actions) }) {
            Text(content.recoveryLabel)
        }
    }
}

private fun content(
    message: String,
    recoveryLabel: String,
    action: FlameGraphRecoveryAction,
    diagnosticDetails: String?,
): FlameGraphEmptyStateContent = FlameGraphEmptyStateContent(message, recoveryLabel, action, diagnosticDetails)

private fun dispatchRecovery(
    action: FlameGraphRecoveryAction,
    actions: ReportActions,
) {
    when (action) {
        FlameGraphRecoveryAction.SELECT_ALL_THREADS -> actions.onThreads(emptySet())
        FlameGraphRecoveryAction.RESET_TIME_RANGE -> actions.onTimeRange(null, null)
        FlameGraphRecoveryAction.CANCEL_PREVIEW -> actions.onCancelFlamePreview()
        FlameGraphRecoveryAction.CLEAR_SEARCH -> actions.onFlameSearch("")
        FlameGraphRecoveryAction.SHOW_ALL_IMPLEMENTATIONS -> actions.onFlameImplementation(ImplementationFilter.ALL)
        FlameGraphRecoveryAction.UNDO_TRANSFORM -> actions.onUndoFlameTransform()
        FlameGraphRecoveryAction.REVIEW_DATA_QUALITY -> actions.onSelectTab(ReportTab.DIAGNOSTICS)
        FlameGraphRecoveryAction.RETRY_PROJECTION -> actions.onRetryFlameProjection()
    }
}
