@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("TooManyFunctions", "LongMethod", "MaxLineLength")

package com.androidperformancestudio.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.analysis.DiagnosticFinding
import com.androidperformancestudio.analysis.DiagnosticSeverity
import com.androidperformancestudio.analysis.DiagnosticTarget
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.capture.CaptureState
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.storage.CallTreeNode
import com.androidperformancestudio.storage.TopFunction
import com.androidperformancestudio.storage.TopFunctionSort
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.ViewerDimensions
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.button.MacOSTextButton
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.radiobutton.MacOSChoiceChip
import com.androidperformancestudio.ui.studio.StudioCard
import com.androidperformancestudio.ui.studio.StudioDataTable
import com.androidperformancestudio.ui.studio.StudioMetricCard
import com.androidperformancestudio.ui.studio.StudioTableColumn
import com.androidperformancestudio.ui.studio.StudioTokens
import com.androidperformancestudio.visualization.NavigationAction
import org.jetbrains.compose.resources.StringResource
import androidx.compose.material3.Text as MaterialText

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
fun ReportPage(
    state: ReportState,
    actions: ReportActions,
    darkTheme: Boolean = false,
    flameTooltipMode: FlameTooltipMode = FlameTooltipMode.FOLLOW_MOUSE,
    onRunAiAnalysis: (() -> Unit)? = null,
) {
    ReportWorkspace(
        state,
        actions,
        currentSimpleperfViewerColors(darkTheme),
        Modifier.fillMaxSize(),
        flameTooltipMode,
        onRunAiAnalysis,
    )
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
internal fun ReportWorkspace(
    state: ReportState,
    actions: ReportActions,
    style: ViewerColors,
    modifier: Modifier = Modifier,
    flameTooltipMode: FlameTooltipMode = FlameTooltipMode.FOLLOW_MOUSE,
    onRunAiAnalysis: (() -> Unit)? = null,
    captureState: CaptureState? = null,
) {
    Box(modifier.fillMaxSize().background(style.workspace)) {
        ReportResultPane(
            state = state,
            actions = actions,
            style = style,
            modifier = Modifier.fillMaxSize().padding(StudioTokens.compactContentPadding),
            flameTooltipMode = flameTooltipMode,
            onRunAiAnalysis = onRunAiAnalysis,
            captureState = captureState,
        )
    }
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun ReportResultPane(
    state: ReportState,
    actions: ReportActions,
    style: ViewerColors,
    modifier: Modifier,
    flameTooltipMode: FlameTooltipMode,
    onRunAiAnalysis: (() -> Unit)?,
    captureState: CaptureState?,
) {
    val loadState = state.loadState
    if (loadState is ReportLoadState.Ready) {
        Box(modifier.fillMaxHeight()) {
            FirefoxReportWorkspace(
                state = state,
                report = loadState.report,
                actions = actions,
                style = style,
                flameTooltipMode = flameTooltipMode,
                onRunAiAnalysis = onRunAiAnalysis,
            )
        }
        return
    }
    // Cards stay on screen in every state; they fill in place when the report becomes Ready.
    Column(
        modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CpuProfilerPendingKpiStrip(pendingKpiText(loadState, captureState), style)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ReportPendingStatus(loadState, actions, style)
        }
    }
}

@Composable
private fun pendingKpiText(
    loadState: ReportLoadState,
    captureState: CaptureState?,
): String {
    val language = currentSimpleperfLanguage()
    return when {
        captureState?.isCaptureActive() == true -> captureState.statusText(language)
        loadState is ReportLoadState.Loading -> localizedStringResource(SimpleperfViewerRes.sp_kpi_pending_loading, language)
        loadState is ReportLoadState.Failed -> localizedStringResource(SimpleperfViewerRes.sp_kpi_pending_failed, language)
        else -> localizedStringResource(SimpleperfViewerRes.sp_kpi_pending_no_session, language)
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun ReportPendingStatus(
    loadState: ReportLoadState,
    actions: ReportActions,
    style: ViewerColors,
) {
    Box(Modifier.fillMaxSize()) {
        when (loadState) {
            ReportLoadState.Closed ->
                ReportStatus(
                    localizedStringResource(
                        SimpleperfViewerRes.sp_session_open_session_report_hint,
                        currentSimpleperfLanguage(),
                    ),
                    style,
                    actionLabel = localizedStringResource(SimpleperfViewerRes.sp_target_open_session, currentSimpleperfLanguage()),
                    onAction = actions.onOpenSession,
                )
            is ReportLoadState.Loading ->
                ReportStatus(
                    localizedStringResource(
                        SimpleperfViewerRes.sp_common_loading_value_format,
                        currentSimpleperfLanguage(),
                        "${loadState.sessionDirectory.fileName}…",
                    ),
                    style,
                )
            is ReportLoadState.Failed ->
                ReportStatus(
                    "${loadState.error.code}: ${loadState.error.message}",
                    style,
                    actionLabel = localizedStringResource(SimpleperfViewerRes.sp_capture_back, currentSimpleperfLanguage()),
                    onAction = actions.onCloseSession,
                )
            is ReportLoadState.Ready -> Unit
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun ReportStatus(
    message: String,
    style: ViewerColors,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
    ) {
        Text(message, color = style.text, fontSize = ViewerTypography.body.fontSize)
        if (actionLabel != null && onAction != null) {
            MacOSTextButton(actionLabel, onAction, style)
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun ReportSelectedPanel(
    state: ReportState,
    report: ReportData,
    actions: ReportActions,
    style: ViewerColors,
    flameTooltipMode: FlameTooltipMode,
) {
    when (state.selectedTab) {
        ReportTab.OVERVIEW -> OverviewPanel(report, actions, style)
        ReportTab.TOP_FUNCTIONS -> TopFunctionsPanel(state, report, actions, style)
        ReportTab.CALL_TREE -> CallTreePanel(state, report, actions, style)
        ReportTab.FLAME_GRAPH ->
            FlameGraphPanel(
                state = state.flameGraph,
                query = state.callStackQuery,
                selectedNodeId = state.workspace.selections.callNodeId,
                snapshot = report.flameGraph,
                actions = actions,
                tooltipMode = flameTooltipMode,
            )
        ReportTab.STACK_CHART -> StackChartPanel(state, report.stackChart, actions, style)
        ReportTab.MARKER_CHART -> MarkerChartPanel(state, report.markers, actions, style)
        ReportTab.MARKER_TABLE -> MarkerTablePanel(state, report.markers, actions, style)
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun OverviewReport(
    report: ReportData,
    actions: ReportActions,
    style: ViewerColors,
) {
    val language = currentSimpleperfLanguage()
    LazyColumn(modifier = Modifier.testTag("overview-report-list"), verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap)) {
        item {
            val metrics =
                listOf(
                    SimpleperfViewerRes.sp_report_samples to report.overview.sampleCount.toString(),
                    SimpleperfViewerRes.sp_report_event_weight to report.overview.totalEventWeight.toString(),
                    SimpleperfViewerRes.sp_target_threads to report.overview.threadCount.toString(),
                    SimpleperfViewerRes.sp_report_lost_rate to "%.2f%%".format(report.quality.lostRate * PERCENT_MULTIPLIER),
                )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = overviewMetricColumns(maxWidth.value.toInt())
                Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap)) {
                    metrics.chunked(columns).forEach { rowMetrics ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.panelGap)) {
                            rowMetrics.forEach { (title, value) ->
                                MetricCard(title, value, Modifier.weight(1f))
                            }
                            repeat(columns - rowMetrics.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        item {
            StudioCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = style.panel,
                borderColor = style.border,
            ) {
                Text(
                    localizedStringResource(SimpleperfViewerRes.sp_report_data_quality, language),
                    color = style.text,
                    fontSize = ViewerTypography.bodyCompact.fontSize,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    localizedStringResource(
                        SimpleperfViewerRes.sp_diagnostics_lost_samples_value_format,
                        language,
                        report.quality.lostSampleCount,
                    ),
                    color = style.text,
                    fontSize = ViewerTypography.label.fontSize,
                )
                Text(
                    localizedStringResource(
                        SimpleperfViewerRes.sp_diagnostics_unwind_errors_value_format,
                        language,
                        report.quality.unwindErrorSamples,
                    ),
                    color = style.text,
                    fontSize = ViewerTypography.label.fontSize,
                )
                Text(
                    localizedStringResource(
                        SimpleperfViewerRes.sp_diagnostics_unknown_symbols_value_format,
                        language,
                        report.quality.unknownSymbolSamples,
                    ),
                    color = style.text,
                    fontSize = ViewerTypography.label.fontSize,
                )
                Text(
                    localizedStringResource(
                        SimpleperfViewerRes.sp_diagnostics_empty_stacks_value_format,
                        language,
                        report.quality.emptyStackSamples,
                    ),
                    color = style.text,
                    fontSize = ViewerTypography.label.fontSize,
                )
            }
        }
        item { SectionTitle(SimpleperfViewerRes.sp_report_top_threads, style) }
        items(report.topThreads.take(OVERVIEW_ITEM_LIMIT), key = { it.threadId }) { thread ->
            Text(
                "${thread.name} · TID ${thread.threadId} · weight ${thread.totalEventCount}",
                color = style.text,
                fontSize = ViewerTypography.label.fontSize,
            )
        }
        item { SectionTitle(SimpleperfViewerRes.sp_report_top_functions, style) }
        itemsIndexed(
            report.topFunctions.take(OVERVIEW_ITEM_LIMIT),
            key = ::topFunctionItemKey,
        ) { _, function ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { actions.onFocusFunction(function.symbolName) }
                        .padding(6.dp),
            ) {
                Text(function.symbolName, modifier = Modifier.weight(1f), color = style.text, fontSize = ViewerTypography.label.fontSize)
                Text(
                    localizedStringResource(
                        SimpleperfViewerRes.sp_report_inclusive_exclusive_summary_format,
                        language,
                        function.inclusiveWeight,
                        function.exclusiveWeight,
                    ),
                    color = style.secondaryText,
                    fontSize = ViewerTypography.dense.fontSize,
                )
            }
        }
        item { SectionTitle(SimpleperfViewerRes.sp_report_artifacts, style) }
        items(report.session.artifacts, key = { it.name }) { artifact ->
            Text(
                "${if (artifact.exists) "✓" else "–"} ${artifact.name} · ${artifact.path}",
                color = style.text,
                fontSize = ViewerTypography.label.fontSize,
            )
        }
        item { SectionTitle(SimpleperfViewerRes.sp_diagnostics_diagnostics, style) }
        if (report.diagnostics.isEmpty()) {
            item {
                Text(
                    localizedStringResource(SimpleperfViewerRes.sp_diagnostics_no_findings_empty_state, language),
                    color = style.secondaryText,
                    fontSize = ViewerTypography.label.fontSize,
                )
            }
        } else {
            items(report.diagnostics, key = { it.ruleId }) { finding ->
                DiagnosticCard(finding, actions, style)
            }
        }
    }
}

internal fun overviewMetricColumns(availableWidthDp: Int): Int =
    when {
        availableWidthDp >= 960 -> 4
        availableWidthDp >= 520 -> 2
        else -> 1
    }

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun MetricCard(
    title: StringResource,
    value: String,
    modifier: Modifier,
) {
    StudioMetricCard(
        label = localizedStringResource(title, currentSimpleperfLanguage()),
        value = value,
        modifier = modifier,
    )
}

@Composable
@Suppress("FunctionName", "LongMethod", "ktlint:standard:function-naming")
internal fun TopFunctionsReport(
    state: ReportState,
    report: ReportData,
    actions: ReportActions,
    style: ViewerColors,
) {
    val language = currentSimpleperfLanguage()
    val color = LocalViewerColors.current
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 10.dp)) {
            TopFunctionSort.entries.forEach { sort ->
                MacOSChoiceChip(
                    label = sort.displayName(language),
                    selected = state.topSort == sort,
                    enabled = true,
                    style = style,
                ) { actions.onTopFunctionSort(sort, state.topDescending) }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            MacOSTextButton(
                localizedStringResource(
                    if (state.topDescending) SimpleperfViewerRes.sp_calltree_descending else SimpleperfViewerRes.sp_calltree_ascending,
                    language,
                ),
                { actions.onTopFunctionSort(state.topSort, !state.topDescending) },
                style,
                height = 20.dp,
            )
        }
        Divider(Modifier.fillMaxWidth(), color = color.border)
        val selectedKey = state.workspace.selections.topFunctionKey
        val topListState = rememberLazyListState()
        val tableFocus = remember { FocusRequester() }
        LaunchedEffect(report.topFunctions, selectedKey) {
            val index = report.topFunctions.indexOfFirst { it.symbolName == selectedKey }
            if (index >= 0 && topListState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                topListState.animateScrollToItem(index)
            }
        }
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .testTag("top-function-table")
                .focusRequester(tableFocus)
                .onKeyEvent { event ->
                    handleTopFunctionKey(event, report.topFunctions, selectedKey, actions)
                }.focusable(),
        ) {
            val tableWidth = maxOf(maxWidth, 980.dp)
            Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).testTag("top-function-table-scroll")) {
                StudioDataTable(
                    columns = topFunctionColumns(language),
                    modifier = Modifier.width(tableWidth).fillMaxHeight(),
                ) {
                    LazyColumn(state = topListState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        itemsIndexed(report.topFunctions, key = ::topFunctionItemKey) { _, function ->
                            TopFunctionRow(
                                function = function,
                                totalWeight = report.overview.totalEventWeight,
                                selected = function.symbolName == selectedKey,
                                onSelect = {
                                    tableFocus.requestFocus()
                                    actions.onSelectTopFunction(function.symbolName)
                                },
                                onFocusCallTree = actions.onFocusCallTreeFunction,
                                onFocusFlame = actions.onFocusFunction,
                                style = style,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Up/Down move the selection, Enter focuses the flame graph and Shift+Enter focuses the call tree. */
internal fun handleTopFunctionKey(
    event: androidx.compose.ui.input.key.KeyEvent,
    functions: List<TopFunction>,
    selectedKey: String?,
    actions: ReportActions,
): Boolean {
    if (event.type != KeyEventType.KeyDown || functions.isEmpty()) return false
    val current = functions.indexOfFirst { it.symbolName == selectedKey }
    return when (event.key) {
        Key.DirectionDown -> {
            actions.onSelectTopFunction(functions[(current + 1).coerceIn(0, functions.lastIndex)].symbolName)
            true
        }
        Key.DirectionUp -> {
            actions.onSelectTopFunction(functions[(current - 1).coerceIn(0, functions.lastIndex)].symbolName)
            true
        }
        Key.Enter, Key.NumPadEnter -> {
            val symbol = functions.getOrNull(current)?.symbolName ?: return false
            if (event.isShiftPressed) actions.onFocusCallTreeFunction(symbol) else actions.onFocusFunction(symbol)
            true
        }
        else -> false
    }
}

@Composable
private fun topFunctionColumns(language: UiLanguage): List<StudioTableColumn> =
    listOf(
        StudioTableColumn(localizedStringResource(SimpleperfViewerRes.sp_workspace_function, language), TOP_FUNCTION_NAME_WEIGHT),
        StudioTableColumn(localizedStringResource(SimpleperfViewerRes.sp_workspace_library_path, language), TOP_FUNCTION_PATH_WEIGHT),
        StudioTableColumn(localizedStringResource(SimpleperfViewerRes.sp_workspace_total_percent, language), TOP_FUNCTION_SHARE_WEIGHT),
        StudioTableColumn(localizedStringResource(SimpleperfViewerRes.sp_calltree_inclusive, language), TOP_FUNCTION_NUMBER_WEIGHT),
        StudioTableColumn(localizedStringResource(SimpleperfViewerRes.sp_calltree_exclusive, language), TOP_FUNCTION_NUMBER_WEIGHT),
        StudioTableColumn(localizedStringResource(SimpleperfViewerRes.sp_report_samples, language), TOP_FUNCTION_COUNT_WEIGHT),
        StudioTableColumn(localizedStringResource(SimpleperfViewerRes.sp_target_threads, language), TOP_FUNCTION_COUNT_WEIGHT),
        StudioTableColumn(localizedStringResource(SimpleperfViewerRes.sp_calltree_navigate, language), TOP_FUNCTION_ACTION_WEIGHT),
    )

private const val TOP_FUNCTION_NAME_WEIGHT = 2.6f
private const val TOP_FUNCTION_PATH_WEIGHT = 2.2f
private const val TOP_FUNCTION_SHARE_WEIGHT = 1.2f
private const val TOP_FUNCTION_NUMBER_WEIGHT = 0.9f
private const val TOP_FUNCTION_COUNT_WEIGHT = 0.7f
private const val TOP_FUNCTION_ACTION_WEIGHT = 1.8f

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun TopFunctionRow(
    function: TopFunction,
    totalWeight: Long,
    selected: Boolean,
    onSelect: () -> Unit,
    onFocusCallTree: (String) -> Unit,
    onFocusFlame: (String) -> Unit,
    style: ViewerColors,
) {
    val share = if (totalWeight > 0L) function.inclusiveWeight.toFloat() / totalWeight else 0f
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("top-function-row-${function.symbolName}")
                .semantics { this.selected = selected }
                .background(if (selected) style.accent.copy(alpha = 0.16f) else style.panel, RoundedCornerShape(9.dp))
                .border(ViewerDimensions.hairline, if (selected) style.accent else style.border, RoundedCornerShape(9.dp))
                .clickable(onClick = onSelect)
                .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            function.symbolName,
            modifier = Modifier.weight(TOP_FUNCTION_NAME_WEIGHT),
            color = style.text,
            fontSize = ViewerTypography.label.fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            function.filePath,
            modifier = Modifier.weight(TOP_FUNCTION_PATH_WEIGHT),
            color = style.secondaryText,
            fontSize = ViewerTypography.dense.fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.weight(TOP_FUNCTION_SHARE_WEIGHT).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                if (totalWeight > 0L) formatCpuPercent(share.toDouble()) else "—",
                modifier = Modifier.width(44.dp),
                color = style.text,
                fontSize = ViewerTypography.label.fontSize,
                maxLines = 1,
            )
            Box(Modifier.weight(1f).height(4.dp).background(style.toolbar, RoundedCornerShape(2.dp))) {
                Box(
                    Modifier
                        .fillMaxWidth(share.coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(style.accent, RoundedCornerShape(2.dp)),
                )
            }
        }
        Text(
            function.inclusiveWeight.toString(),
            modifier = Modifier.weight(TOP_FUNCTION_NUMBER_WEIGHT),
            color = style.text,
            fontSize = ViewerTypography.label.fontSize,
        )
        Text(
            function.exclusiveWeight.toString(),
            modifier = Modifier.weight(TOP_FUNCTION_NUMBER_WEIGHT),
            color = style.text,
            fontSize = ViewerTypography.label.fontSize,
        )
        Text(
            function.sampleCount.toString(),
            modifier = Modifier.weight(TOP_FUNCTION_COUNT_WEIGHT),
            color = style.text,
            fontSize = ViewerTypography.label.fontSize,
        )
        Text(
            function.threadCount.toString(),
            modifier = Modifier.weight(TOP_FUNCTION_COUNT_WEIGHT),
            color = style.text,
            fontSize = ViewerTypography.label.fontSize,
        )
        Row(modifier = Modifier.weight(TOP_FUNCTION_ACTION_WEIGHT), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MacOSTextButton(
                localizedStringResource(SimpleperfViewerRes.sp_calltree_path, currentSimpleperfLanguage()),
                { onFocusCallTree(function.symbolName) },
                style,
            )
            MacOSTextButton(
                localizedStringResource(SimpleperfViewerRes.sp_flame_flame, currentSimpleperfLanguage()),
                { onFocusFlame(function.symbolName) },
                style,
            )
        }
    }
}

@Composable
@Suppress("FunctionName", "LongMethod", "ktlint:standard:function-naming")
internal fun CallTreeReport(
    state: ReportState,
    report: ReportData,
    actions: ReportActions,
    style: ViewerColors,
) {
    var expandedIds by remember(report.callTree) {
        mutableStateOf(report.callTree.firefoxInitialExpandedIds().toMutableSet())
    }
    val listState = rememberLazyListState()
    val selectedNodeId = state.workspace.selections.callNodeId
    var lastAutoPositionedNodeId by remember(report.callTree) { mutableStateOf<FlameCallNodeId?>(null) }
    val childrenByParent = remember(report.callTree) { report.callTree.groupBy(CallTreeNode::parentId) }
    LaunchedEffect(report.callTree) {
        if (selectedNodeId == null) {
            report.callTree.firefoxInitialSelectedId()?.let { actions.onSelectCallNode(FlameCallNodeId(it)) }
        }
    }
    LaunchedEffect(report.callTree, state.callStackQuery.searchText) {
        if (state.callStackQuery.searchText.isNotBlank()) {
            expandedIds =
                (expandedIds + report.callTree.expandedPathIds(state.callStackQuery.searchText)).toMutableSet()
        }
    }
    LaunchedEffect(report.callTree, selectedNodeId) {
        if (selectedNodeId != null) {
            expandedIds = (expandedIds + report.callTree.selectedPathIds(selectedNodeId)).toMutableSet()
        }
    }
    val visible = remember(report.callTree, expandedIds) { report.callTree.visibleNodes(expandedIds) }
    LaunchedEffect(visible, selectedNodeId) {
        if (selectedNodeId != null && selectedNodeId != lastAutoPositionedNodeId) {
            visible.selectedNodeIndex(selectedNodeId).takeIf { index -> index >= 0 }?.let { index ->
                val alreadyVisible = listState.layoutInfo.visibleItemsInfo.any { item -> item.key == selectedNodeId.value }
                if (!alreadyVisible) listState.animateScrollToItem(index)
                lastAutoPositionedNodeId = selectedNodeId
            }
        }
    }
    val treeFocus = remember { FocusRequester() }
    val language = currentSimpleperfLanguage()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp).testTag("call-tree-toolbar"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.testTag("call-tree-expand-all")) {
                MacOSTextButton(
                    localizedStringResource(SimpleperfViewerRes.sp_workspace_expand_all, language),
                    { expandedIds = childrenByParent.keys.filterNotNull().toMutableSet() },
                    style,
                    height = 20.dp,
                )
            }
            Box(Modifier.testTag("call-tree-collapse-all")) {
                MacOSTextButton(
                    localizedStringResource(SimpleperfViewerRes.sp_workspace_collapse_all, language),
                    { expandedIds = mutableSetOf() },
                    style,
                    height = 20.dp,
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(style.panel)
                .border(ViewerDimensions.hairline, style.border)
                .focusRequester(treeFocus)
                .onKeyEvent { event ->
                    handleCallTreeKey(
                        event = event,
                        visible = visible,
                        allNodes = report.callTree,
                        selectedNodeId = selectedNodeId,
                        expandedIds = expandedIds,
                        hasChildren = { id -> childrenByParent[id].orEmpty().isNotEmpty() },
                        onExpandedChange = { expandedIds = it.toMutableSet() },
                        actions = actions,
                    )
                }.focusable(),
        ) {
            FirefoxCallTreeHeader(
                report.overview.eventTypes.firefoxTotalColumnLabel(currentSimpleperfLanguage()),
                style,
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("call-tree-list"),
            ) {
                itemsIndexed(visible, key = { _, node -> node.id }) { index, node ->
                    val hasChildren = childrenByParent[node.id].orEmpty().isNotEmpty()
                    FirefoxCallTreeRow(
                        index = index,
                        node = node,
                        totalWeight = report.overview.totalEventWeight,
                        hasChildren = hasChildren,
                        expanded = node.id in expandedIds,
                        selected = selectedNodeId?.value == node.id,
                        search = state.callStackQuery.searchText,
                        style = style,
                        onSelect = {
                            treeFocus.requestFocus()
                            actions.onSelectCallNode(FlameCallNodeId(node.id))
                        },
                        onToggle = {
                            expandedIds =
                                expandedIds.toMutableSet().also {
                                    if (!it.add(node.id)) it.remove(node.id)
                                }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Firefox-style call tree keys: Up/Down move through visible rows, Right expands or descends, Left collapses or
 * ascends to the parent, `*` expands the selected subtree and Enter focuses the selected function in the flame graph.
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod", "ReturnCount")
internal fun handleCallTreeKey(
    event: androidx.compose.ui.input.key.KeyEvent,
    visible: List<CallTreeNode>,
    allNodes: List<CallTreeNode>,
    selectedNodeId: FlameCallNodeId?,
    expandedIds: Set<Long>,
    hasChildren: (Long) -> Boolean,
    onExpandedChange: (Set<Long>) -> Unit,
    actions: ReportActions,
): Boolean {
    if (event.type != KeyEventType.KeyDown || visible.isEmpty()) return false
    val index = visible.selectedNodeIndex(selectedNodeId)
    val node = visible.getOrNull(index)

    fun select(target: CallTreeNode) = actions.onSelectCallNode(FlameCallNodeId(target.id))
    when (event.key) {
        Key.DirectionDown -> select(visible[(index + 1).coerceIn(0, visible.lastIndex)])
        Key.DirectionUp -> select(visible[(index - 1).coerceIn(0, visible.lastIndex)])
        Key.DirectionRight -> {
            node ?: return false
            when {
                !hasChildren(node.id) -> return false
                node.id !in expandedIds -> onExpandedChange(expandedIds + node.id)
                else -> visible.getOrNull(index + 1)?.takeIf { it.parentId == node.id }?.let(::select)
            }
        }
        Key.DirectionLeft -> {
            node ?: return false
            if (node.id in expandedIds) {
                onExpandedChange(expandedIds - node.id)
            } else {
                visible.firstOrNull { it.id == node.parentId }?.let(::select) ?: return false
            }
        }
        Key.Multiply, Key.NumPadMultiply -> {
            node ?: return false
            onExpandedChange(expandedIds + allNodes.subtreeIds(node.id))
        }
        Key.Enter, Key.NumPadEnter -> {
            node ?: return false
            if (event.isShiftPressed) actions.onFocusCallTreeFunction(node.symbolName) else actions.onFocusFunction(node.symbolName)
        }
        else -> return false
    }
    return true
}

private fun List<CallTreeNode>.subtreeIds(rootId: Long): Set<Long> {
    val children = groupBy(CallTreeNode::parentId)
    return buildSet {
        val pending = ArrayDeque(listOf(rootId))
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            add(id)
            children[id].orEmpty().forEach { pending.addLast(it.id) }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxCallTreeHeader(
    totalLabel: String,
    style: ViewerColors,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(FIREFOX_CALL_TREE_HEADER_HEIGHT)
            .background(style.toolbar)
            .border(ViewerDimensions.hairline, style.border),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FirefoxCallTreeHeaderCell(
            label = totalLabel,
            width = FIREFOX_PERCENT_COLUMN_WIDTH + FIREFOX_TOTAL_COLUMN_WIDTH,
            style = style,
        )
        FirefoxCallTreeDivider(style)
        FirefoxCallTreeHeaderCell(
            label =
                localizedStringResource(
                    SimpleperfViewerRes.sp_report_self_column,
                    currentSimpleperfLanguage(),
                ),
            width = FIREFOX_SELF_COLUMN_WIDTH,
            style = style,
        )
        FirefoxCallTreeDivider(style)
        FirefoxCallTreeHeaderCell(
            label = localizedStringResource(SimpleperfViewerRes.sp_report_samples, currentSimpleperfLanguage()),
            width = CALL_TREE_COUNT_COLUMN_WIDTH,
            style = style,
        )
        FirefoxCallTreeDivider(style)
        FirefoxCallTreeHeaderCell(
            label = localizedStringResource(SimpleperfViewerRes.sp_target_threads, currentSimpleperfLanguage()),
            width = CALL_TREE_COUNT_COLUMN_WIDTH,
            style = style,
        )
        FirefoxCallTreeDivider(style)
        Spacer(Modifier.width(FIREFOX_ICON_COLUMN_WIDTH))
        FirefoxCallTreeDivider(style)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxCallTreeHeaderCell(
    label: String,
    width: androidx.compose.ui.unit.Dp,
    style: ViewerColors,
) {
    val language = currentSimpleperfLanguage()
    Text(
        text = label,
        modifier = Modifier.width(width).padding(horizontal = 5.dp),
        color = style.text,
        fontSize = ViewerTypography.dense.fontSize,
        textAlign = TextAlign.End,
        maxLines = 1,
    )
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun FirefoxCallTreeRow(
    index: Int,
    node: CallTreeNode,
    totalWeight: Long,
    hasChildren: Boolean,
    expanded: Boolean,
    selected: Boolean,
    search: String,
    style: ViewerColors,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
) {
    val foreground = if (selected) style.accentText else style.text
    val secondary = if (selected) style.accentText.copy(alpha = 0.9f) else style.secondaryText
    val background =
        when {
            selected -> style.accent
            index % 2 == 1 -> style.toolbar.copy(alpha = 0.62f)
            else -> style.panel
        }
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("call-tree-row-${node.id}")
            .height(FIREFOX_CALL_TREE_ROW_HEIGHT)
            .background(background)
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FirefoxCallTreeValue(node.firefoxTotalPercent(totalWeight), FIREFOX_PERCENT_COLUMN_WIDTH, foreground)
        FirefoxCallTreeValue(node.inclusiveWeight.firefoxWeight(), FIREFOX_TOTAL_COLUMN_WIDTH, foreground)
        FirefoxCallTreeDivider(style)
        FirefoxCallTreeValue(node.exclusiveWeight.firefoxWeight(), FIREFOX_SELF_COLUMN_WIDTH, foreground)
        FirefoxCallTreeDivider(style)
        FirefoxCallTreeValue(node.sampleCount.firefoxWeight(), CALL_TREE_COUNT_COLUMN_WIDTH, foreground)
        FirefoxCallTreeDivider(style)
        FirefoxCallTreeValue(node.threadCount.firefoxWeight(), CALL_TREE_COUNT_COLUMN_WIDTH, foreground)
        FirefoxCallTreeDivider(style)
        Spacer(Modifier.width(FIREFOX_ICON_COLUMN_WIDTH))
        FirefoxCallTreeDivider(style)
        Row(
            Modifier.weight(1f).fillMaxHeight().padding(end = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width((node.depth * FIREFOX_CALL_TREE_INDENT).dp))
            Box(
                Modifier
                    .width(FIREFOX_TOGGLE_COLUMN_WIDTH)
                    .fillMaxHeight()
                    .then(
                        if (hasChildren) {
                            Modifier
                                .clickable(onClick = onToggle)
                                .semantics {
                                    contentDescription =
                                        if (expanded) "Collapse ${node.symbolName}" else "Expand ${node.symbolName}"
                                }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (hasChildren) {
                    Canvas(Modifier.width(8.dp).height(10.dp)) {
                        val triangle = Path()
                        if (expanded) {
                            triangle.moveTo(0f, 2f)
                            triangle.lineTo(size.width, 2f)
                            triangle.lineTo(size.width / 2f, size.height)
                        } else {
                            triangle.moveTo(0f, 0f)
                            triangle.lineTo(size.width, size.height / 2f)
                            triangle.lineTo(0f, size.height)
                        }
                        triangle.close()
                        drawPath(triangle, secondary)
                    }
                }
            }
            FirefoxHighlightedText(node.symbolName, search, foreground, style)
            if (node.filePath.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                FirefoxHighlightedText(
                    text = node.filePath,
                    search = search,
                    color = secondary,
                    style = style,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxCallTreeValue(
    value: String,
    width: androidx.compose.ui.unit.Dp,
    color: androidx.compose.ui.graphics.Color,
) {
    MaterialText(
        text = value,
        modifier = Modifier.width(width).padding(horizontal = 5.dp),
        color = color,
        fontSize = ViewerTypography.dense.fontSize,
        lineHeight = ViewerTypography.dense.lineHeight,
        textAlign = TextAlign.End,
        maxLines = 1,
    )
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxCallTreeDivider(style: ViewerColors) {
    Spacer(Modifier.width(1.dp).fillMaxHeight().background(style.border))
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxHighlightedText(
    text: String,
    search: String,
    color: androidx.compose.ui.graphics.Color,
    style: ViewerColors,
    modifier: Modifier = Modifier,
) {
    MaterialText(
        text = text.firefoxHighlight(search, style),
        modifier = modifier,
        color = color,
        fontSize = ViewerTypography.label.fontSize,
        lineHeight = ViewerTypography.dense.lineHeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
    )
}

private fun String.firefoxHighlight(
    search: String,
    style: ViewerColors,
): AnnotatedString {
    val matchStart = if (search.isBlank()) -1 else indexOf(search, ignoreCase = true)
    return if (matchStart < 0) {
        AnnotatedString(this)
    } else {
        buildAnnotatedString {
            append(this@firefoxHighlight.substring(0, matchStart))
            withStyle(SpanStyle(color = style.text, background = style.accent.copy(alpha = 0.28f))) {
                append(this@firefoxHighlight.substring(matchStart, matchStart + search.length))
            }
            append(this@firefoxHighlight.substring(matchStart + search.length))
        }
    }
}

private fun List<CallTreeNode>.expandedPathIds(search: String): Set<Long> {
    val byId = associateBy(CallTreeNode::id)
    return buildSet {
        this@expandedPathIds.filter { it.symbolName.contains(search, ignoreCase = true) }.forEach { match ->
            var node: CallTreeNode? = match
            while (node != null) {
                add(node.id)
                node = node.parentId?.let { parentId -> byId[parentId] }
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun DiagnosticCard(
    finding: DiagnosticFinding,
    actions: ReportActions,
    style: ViewerColors,
) {
    val language = currentSimpleperfLanguage()
    val accent =
        when (finding.severity) {
            DiagnosticSeverity.INFO -> style.accent
            DiagnosticSeverity.WARNING -> style.warning
            DiagnosticSeverity.CRITICAL -> style.error
        }
    val navigation = finding.navigation(actions)
    StudioCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable {
                    actions.onSelectOverviewFinding(finding.ruleId)
                    navigation?.invoke()
                },
        containerColor = style.panel,
        borderColor = accent,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                finding.title,
                modifier = Modifier.weight(1f),
                color = style.text,
                fontSize = ViewerTypography.bodyCompact.fontSize,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                localizedStringResource(finding.severity.resource(), language),
                color = accent,
                fontSize = ViewerTypography.dense.fontSize,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(finding.conclusion, color = style.text, fontSize = ViewerTypography.label.fontSize)
        finding.evidence.forEach { evidence ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    evidence.label,
                    modifier = Modifier.weight(1f),
                    color = style.secondaryText,
                    fontSize = ViewerTypography.dense.fontSize,
                )
                Text(evidence.value, color = style.text, fontSize = ViewerTypography.dense.fontSize, fontWeight = FontWeight.SemiBold)
            }
        }
        if (finding.recommendations.isNotEmpty()) {
            Text(
                localizedStringResource(SimpleperfViewerRes.sp_finding_recommendations, language),
                color = style.text,
                fontSize = ViewerTypography.label.fontSize,
                fontWeight = FontWeight.Bold,
            )
            finding.recommendations.forEach {
                Text(
                    localizedStringResource(SimpleperfViewerRes.sp_common_bullet_format, language, it),
                    color = style.secondaryText,
                    fontSize = ViewerTypography.dense.fontSize,
                )
            }
        }
        if (navigation != null) {
            Text(
                localizedStringResource(SimpleperfViewerRes.sp_finding_inspect_evidence_action, language),
                color = accent,
                fontSize = ViewerTypography.dense.fontSize,
            )
        }
    }
}

private fun DiagnosticFinding.navigation(actions: ReportActions): (() -> Unit)? =
    when (val destination = target) {
        is DiagnosticTarget.Function -> {
            { actions.onFocusFunction(destination.symbolName) }
        }
        is DiagnosticTarget.Thread ->
            {
                {
                    actions.onThreads(setOf(destination.threadId))
                    actions.onSelectTab(ReportTab.OVERVIEW)
                }
            }
        null -> null
    }

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun SectionTitle(
    title: StringResource,
    style: ViewerColors,
) {
    Text(
        localizedStringResource(title, currentSimpleperfLanguage()),
        color = style.text,
        fontSize = ViewerTypography.bodyCompact.fontSize,
        fontWeight = FontWeight.SemiBold,
    )
}

private fun TopFunctionSort.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            TopFunctionSort.INCLUSIVE_WEIGHT -> SimpleperfViewerRes.sp_diagnostics_inclusive_share
            TopFunctionSort.EXCLUSIVE_WEIGHT -> SimpleperfViewerRes.sp_diagnostics_exclusive_weight
            TopFunctionSort.SAMPLE_COUNT -> SimpleperfViewerRes.sp_report_samples
            TopFunctionSort.THREAD_COUNT -> SimpleperfViewerRes.sp_target_threads
            TopFunctionSort.SYMBOL_NAME -> SimpleperfViewerRes.sp_diagnostics_function
            TopFunctionSort.FILE_PATH -> SimpleperfViewerRes.sp_calltree_path
        },
        language,
    )

private fun DiagnosticSeverity.resource(): StringResource =
    when (this) {
        DiagnosticSeverity.INFO -> SimpleperfViewerRes.sp_diagnostics_info
        DiagnosticSeverity.WARNING -> SimpleperfViewerRes.sp_diagnostics_warning
        DiagnosticSeverity.CRITICAL -> SimpleperfViewerRes.sp_diagnostics_critical
    }

internal fun topFunctionItemKey(
    index: Int,
    function: TopFunction,
): String = "$index:${function.filePath}:${function.symbolName}"

internal fun List<CallTreeNode>.firefoxInitialExpandedIds(maxDepth: Int = FIREFOX_INITIAL_EXPANSION_DEPTH): Set<Long> =
    firefoxInitialPath(maxDepth).toSet()

internal fun List<CallTreeNode>.firefoxInitialSelectedId(maxDepth: Int = FIREFOX_INITIAL_EXPANSION_DEPTH): Long? =
    firefoxInitialPath(maxDepth).lastOrNull()

private fun List<CallTreeNode>.firefoxInitialPath(maxDepth: Int): List<Long> {
    if (isEmpty() || maxDepth <= 0) return emptyList()
    val children = groupBy(CallTreeNode::parentId)
    return buildList {
        var current = children[null].orEmpty().firefoxSorted().firstOrNull()
        repeat(maxDepth) {
            val node = current ?: return@buildList
            add(node.id)
            current = children[node.id].orEmpty().firefoxSorted().firstOrNull()
        }
    }
}

internal fun List<String>.firefoxTotalColumnLabel(language: UiLanguage = UiLanguage.ENGLISH): String =
    localizedStringResource(
        if (size == 1 && single().equals("samples", ignoreCase = true)) {
            SimpleperfViewerRes.sp_calltree_total_samples
        } else {
            SimpleperfViewerRes.sp_calltree_total
        },
        language,
    )

internal fun List<CallTreeNode>.visibleNodes(expandedIds: Set<Long>): List<CallTreeNode> {
    val children = groupBy(CallTreeNode::parentId)
    return buildList {
        fun append(parentId: Long?) {
            children[parentId].orEmpty().firefoxSorted().forEach { node ->
                add(node)
                if (node.id in expandedIds) append(node.id)
            }
        }
        append(null)
    }
}

private fun List<CallTreeNode>.firefoxSorted(): List<CallTreeNode> = sortedByDescending(CallTreeNode::inclusiveWeight)

internal fun CallTreeNode.firefoxTotalPercent(totalWeight: Long): String {
    if (totalWeight <= 0L) return "0%"
    val percent = inclusiveWeight.toDouble() * PERCENT_MULTIPLIER / totalWeight.toDouble()
    val decimals = if (percent == percent.toLong().toDouble()) 0 else 1
    return if (decimals == 0) "${percent.toLong()}%" else "%.1f%%".format(percent)
}

internal fun Long.firefoxWeight(): String = if (this == 0L) "—" else "%,d".format(this)

internal fun List<CallTreeNode>.selectedPathIds(selectedNodeId: FlameCallNodeId?): Set<Long> {
    val byId = associateBy(CallTreeNode::id)
    return buildSet {
        var node = selectedNodeId?.value?.let(byId::get)
        while (node != null) {
            add(node.id)
            node = node.parentId?.let(byId::get)
        }
    }
}

internal fun List<CallTreeNode>.selectedNodeIndex(selectedNodeId: FlameCallNodeId?): Int =
    indexOfFirst { node -> node.id == selectedNodeId?.value }

internal fun handleKey(
    event: androidx.compose.ui.input.key.KeyEvent,
    onAction: (NavigationAction) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val action = simpleperfNavigationAction(event.key)
    return if (action == null) {
        false
    } else {
        onAction(action)
        true
    }
}

private val FIREFOX_CALL_TREE_HEADER_HEIGHT = 23.dp
private val FIREFOX_CALL_TREE_ROW_HEIGHT = 16.dp
private val FIREFOX_PERCENT_COLUMN_WIDTH = 55.dp
private val FIREFOX_TOTAL_COLUMN_WIDTH = 70.dp
private val FIREFOX_SELF_COLUMN_WIDTH = 80.dp
private val CALL_TREE_COUNT_COLUMN_WIDTH = 64.dp
private val FIREFOX_ICON_COLUMN_WIDTH = 20.dp
private val FIREFOX_TOGGLE_COLUMN_WIDTH = 18.dp
private const val FIREFOX_CALL_TREE_INDENT = 10
private const val FIREFOX_INITIAL_EXPANSION_DEPTH = 18

internal fun simpleperfNavigationAction(key: Key): NavigationAction? =
    when (key) {
        Key.W -> NavigationAction.ZOOM_IN
        Key.S -> NavigationAction.ZOOM_OUT
        Key.A -> NavigationAction.PAN_LEFT
        Key.D -> NavigationAction.PAN_RIGHT
        else -> null
    }

private const val PERCENT_MULTIPLIER = 100
private const val OVERVIEW_ITEM_LIMIT = 8
