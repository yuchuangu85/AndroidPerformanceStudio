package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.profileanalysis.FlameGraphEmptyReason
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.profileanalysis.ImplementationFilter
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.visualization.FirefoxFlameGraphStyle

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

@Suppress("LongMethod")
internal fun flameGraphEmptyStateContent(
    reason: FlameGraphEmptyReason,
    diagnosticDetails: String?,
    language: UiLanguage = UiLanguage.ENGLISH,
): FlameGraphEmptyStateContent =
    when (reason) {
        FlameGraphEmptyReason.THREAD_HAS_NO_SAMPLES ->
            content(
                SimpleperfViewerRes.sp_flame_selected_thread_no_samples,
                SimpleperfViewerRes.sp_flame_show_all_threads,
                FlameGraphRecoveryAction.SELECT_ALL_THREADS,
                diagnosticDetails,
                language,
            )
        FlameGraphEmptyReason.COMMITTED_RANGE_EMPTY ->
            content(
                SimpleperfViewerRes.sp_flame_selected_range_no_samples,
                SimpleperfViewerRes.sp_flame_reset_time_range,
                FlameGraphRecoveryAction.RESET_TIME_RANGE,
                diagnosticDetails,
                language,
            )
        FlameGraphEmptyReason.PREVIEW_RANGE_EMPTY ->
            content(
                SimpleperfViewerRes.sp_flame_preview_range_no_samples,
                SimpleperfViewerRes.sp_flame_cancel_preview,
                FlameGraphRecoveryAction.CANCEL_PREVIEW,
                diagnosticDetails,
                language,
            )
        FlameGraphEmptyReason.SEARCH_FILTERED_ALL ->
            content(
                SimpleperfViewerRes.sp_flame_search_removed_all_samples,
                SimpleperfViewerRes.sp_flame_clear_search,
                FlameGraphRecoveryAction.CLEAR_SEARCH,
                diagnosticDetails,
                language,
            )
        FlameGraphEmptyReason.IMPLEMENTATION_FILTERED_ALL ->
            content(
                SimpleperfViewerRes.sp_flame_implementation_filter_removed_all_samples,
                SimpleperfViewerRes.sp_flame_show_all_implementations,
                FlameGraphRecoveryAction.SHOW_ALL_IMPLEMENTATIONS,
                diagnosticDetails,
                language,
            )
        FlameGraphEmptyReason.TRANSFORMS_FILTERED_ALL ->
            content(
                SimpleperfViewerRes.sp_flame_transforms_removed_all_samples,
                SimpleperfViewerRes.sp_flame_undo_transform,
                FlameGraphRecoveryAction.UNDO_TRANSFORM,
                diagnosticDetails,
                language,
            )
        FlameGraphEmptyReason.PROFILE_INCOMPLETE ->
            content(
                SimpleperfViewerRes.sp_flame_incomplete_call_stacks,
                SimpleperfViewerRes.sp_flame_review_data_quality,
                FlameGraphRecoveryAction.REVIEW_DATA_QUALITY,
                diagnosticDetails,
                language,
            )
        FlameGraphEmptyReason.PROJECTION_FAILED ->
            content(
                SimpleperfViewerRes.sp_flame_projection_failed,
                SimpleperfViewerRes.sp_flame_retry_projection,
                FlameGraphRecoveryAction.RETRY_PROJECTION,
                diagnosticDetails,
                language,
            )
    }

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun FirefoxFlameGraphEmptyState(
    snapshot: FlameGraphSnapshot,
    actions: ReportActions,
    style: FirefoxFlameGraphStyle,
    modifier: Modifier = Modifier,
) {
    val reason = snapshot.emptyReason ?: return
    val content =
        flameGraphEmptyStateContent(
            reason,
            snapshot.diagnosticDetails,
            currentSimpleperfLanguage(),
        )
    Column(
        modifier = modifier.fillMaxWidth().background(style.canvasBackground.toComposeColor()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(content.message, color = style.canvasForeground.toComposeColor(), fontSize = 13.sp)
        content.diagnosticDetails?.takeIf(String::isNotBlank)?.let { details ->
            Text(details, color = style.mutedForeground.toComposeColor(), fontSize = 11.sp)
        }
        Box(
            modifier =
                Modifier
                    .background(style.panelSurface.toComposeColor(), RoundedCornerShape(2.dp))
                    .border(1.dp, style.surfaceBorder.toComposeColor(), RoundedCornerShape(2.dp))
                    .clickable { dispatchRecovery(content.recoveryAction, actions) }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(content.recoveryLabel, color = style.canvasForeground.toComposeColor(), fontSize = 11.sp)
        }
    }
}

private fun content(
    message: org.jetbrains.compose.resources.StringResource,
    recoveryLabel: org.jetbrains.compose.resources.StringResource,
    action: FlameGraphRecoveryAction,
    diagnosticDetails: String?,
    language: UiLanguage,
): FlameGraphEmptyStateContent =
    FlameGraphEmptyStateContent(
        localizedStringResource(message, language),
        localizedStringResource(recoveryLabel, language),
        action,
        diagnosticDetails,
    )

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
        FlameGraphRecoveryAction.REVIEW_DATA_QUALITY -> actions.onSelectTab(ReportTab.OVERVIEW)
        FlameGraphRecoveryAction.RETRY_PROJECTION -> actions.onRetryFlameProjection()
    }
}
