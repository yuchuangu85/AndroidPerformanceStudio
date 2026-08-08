package com.androidperformancestudio.methodrecording.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.arttrace.MethodTopMethod
import com.androidperformancestudio.application.FlameGraphPanelState
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.methodrecording.app.generated.resources.Res
import com.androidperformancestudio.methodrecording.app.generated.resources.calls
import com.androidperformancestudio.methodrecording.app.generated.resources.function
import com.androidperformancestudio.methodrecording.app.generated.resources.no_trace_loaded
import com.androidperformancestudio.methodrecording.app.generated.resources.self_time
import com.androidperformancestudio.methodrecording.app.generated.resources.total_time
import com.androidperformancestudio.methodrecording.app.generated.resources.working
import com.androidperformancestudio.presentation.FlameGraphPanel
import com.androidperformancestudio.presentation.FlameTooltipMode
import com.androidperformancestudio.presentation.ReportActions
import com.androidperformancestudio.profileanalysis.AnalysisTimeRange
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.CallStackTransform
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameGraphNavigationCommand
import com.androidperformancestudio.profileanalysis.ImplementationFilter
import com.androidperformancestudio.profileanalysis.StackChartBlockId
import com.androidperformancestudio.storage.ProfileMarkerId
import com.androidperformancestudio.storage.TopFunctionSort
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

/** The method-recording workspace body: top-methods table plus the shared flame graph. */
@Composable
fun MethodRecordingScreen(
    state: MethodRecordingState,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.error?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.isLoading) {
            Text(
                text = localizedStringResource(Res.string.working, language),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val analysis = state.analysis
        if (analysis == null) {
            Text(
                text = localizedStringResource(Res.string.no_trace_loaded, language),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            )
            return@Column
        }

        state.traceLabel?.let { label ->
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        MethodTopMethodsTable(
            methods = state.topMethods,
            language = language,
            modifier = Modifier.fillMaxWidth(),
        )

        state.flameGraph?.let { snapshot ->
            Box(
                modifier =
                    Modifier
                        .weight(2f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                FlameGraphPanel(
                    state = FlameGraphPanelState(),
                    query = CallStackAnalysisQuery(),
                    snapshot = snapshot,
                    actions = noopReportActions(),
                    tooltipMode = FlameTooltipMode.FOLLOW_MOUSE,
                )
            }
        }
    }
}

@Composable
private fun MethodTopMethodsTable(
    methods: List<MethodTopMethod>,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TableHeader(localizedStringResource(Res.string.function, language), Modifier.weight(3f))
            TableHeader(localizedStringResource(Res.string.self_time, language), Modifier.weight(1f))
            TableHeader(localizedStringResource(Res.string.total_time, language), Modifier.weight(1f))
            TableHeader(localizedStringResource(Res.string.calls, language), Modifier.weight(1f))
        }
        LazyColumn(Modifier.heightIn(max = 220.dp)) {
            items(methods) { method ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = method.symbolName,
                        modifier = Modifier.weight(3f),
                        maxLines = 1,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = formatMicros(method.selfMicros),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = formatMicros(method.totalMicros),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = method.callCount.toString(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun TableHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
    )
}

private fun formatMicros(micros: Long): String =
    when {
        micros >= 1_000_000L -> "%.2f s".format(micros / 1_000_000.0)
        micros >= 1_000L -> "%.2f ms".format(micros / 1_000.0)
        else -> "$micros µs"
    }

/** All flame-graph interactions are inert in this first version; the canvas still renders. */
private fun noopReportActions(): ReportActions =
    ReportActions(
        onOpenSession = {},
        onCloseSession = {},
        onSelectTab = { _: ReportTab -> },
        onTimeRange = { _: Long?, _: Long? -> },
        onThreads = { _: Set<Int> -> },
        onEvents = { _: Set<String> -> },
        onTopFunctionSort = { _: TopFunctionSort, _: Boolean -> },
        onCallTreeDirection = { _: CallStackDirection -> },
        onFlamePreviewRange = { _: AnalysisTimeRange -> },
        onCancelFlamePreview = {},
        onFlameSearch = { _: String -> },
        onFlameImplementation = { _: ImplementationFilter -> },
        onApplyFlameTransform = { _: CallStackTransform -> },
        onUndoFlameTransform = {},
        onClearFlameTransforms = {},
        onRetryFlameProjection = {},
        onSelectCallNode = { _: FlameCallNodeId? -> },
        onHoverFlameNode = { _: FlameCallNodeId? -> },
        onOpenFlameContext = { _: FlameCallNodeId? -> },
        onOpenFlameDetails = { _: FlameCallNodeId -> },
        onCloseFlameDetails = {},
        onCopyFlameFunction = { _: String -> },
        onNavigateFlameNode = { _: FlameGraphNavigationCommand -> null },
        onFocusCallTreeFunction = { _: String -> },
        onFocusFunction = { _: String -> },
        onExportSession = {},
        onExportReport = {},
        onExportRawProtobuf = {},
        onExportScreenshot = {},
        onGenerateSimpleperfReport = {},
        onGenerateHtmlReport = {},
        onExportExternalGuide = {},
        onDetailsVisible = { _: Boolean -> },
        onTimelineHeightDp = { _: Int -> },
        onSelectOverviewFinding = { _: String? -> },
        onSelectTopFunction = { _: String? -> },
        onSelectStackChartBlock = { _: StackChartBlockId? -> },
        onSelectMarker = { _: ProfileMarkerId? -> },
        onMarkerSearch = { _: String -> },
    )
