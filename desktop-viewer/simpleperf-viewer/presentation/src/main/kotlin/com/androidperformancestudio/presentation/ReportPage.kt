@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("TooManyFunctions", "LongMethod", "MaxLineLength")

package com.androidperformancestudio.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.analysis.DiagnosticFinding
import com.androidperformancestudio.analysis.DiagnosticSeverity
import com.androidperformancestudio.analysis.DiagnosticTarget
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.storage.CallTreeNode
import com.androidperformancestudio.storage.TopFunction
import com.androidperformancestudio.storage.TopFunctionSort
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.ViewerDimensions
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.viewerColors
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
) {
    ReportWorkspace(
        state,
        actions,
        viewerColors(darkTheme),
        Modifier.fillMaxSize(),
        flameTooltipMode,
    )
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun ReportWorkspace(
    state: ReportState,
    actions: ReportActions,
    style: ViewerColors,
    modifier: Modifier = Modifier,
    flameTooltipMode: FlameTooltipMode = FlameTooltipMode.FOLLOW_MOUSE,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(style.workspace)
            .border(ViewerDimensions.hairline, style.border),
    ) {
        ReportResultPane(state, actions, style, Modifier.fillMaxSize(), flameTooltipMode)
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun ReportResultPane(
    state: ReportState,
    actions: ReportActions,
    style: ViewerColors,
    modifier: Modifier,
    flameTooltipMode: FlameTooltipMode,
) {
    Box(modifier.fillMaxHeight().padding(14.dp)) {
        when (val loadState = state.loadState) {
            ReportLoadState.Closed ->
                ReportStatus(
                    localizedStringResource(
                        SimpleperfViewerRes.sp_session_open_session_report_hint,
                        currentSimpleperfLanguage(),
                    ),
                    style,
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
                    actions.onCloseSession,
                )
            is ReportLoadState.Ready ->
                FirefoxReportWorkspace(
                    state = state,
                    report = loadState.report,
                    actions = actions,
                    style = style,
                    flameTooltipMode = flameTooltipMode,
                )
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun ReportStatus(
    message: String,
    style: ViewerColors,
    onClose: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().background(style.panel, RoundedCornerShape(10.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, color = style.text, fontSize = 13.sp)
        onClose?.let {
            MacOsButton(
                localizedStringResource(
                    SimpleperfViewerRes.sp_capture_back,
                    currentSimpleperfLanguage(),
                ),
                it,
                style,
            )
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
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(SimpleperfViewerRes.sp_report_samples, report.overview.sampleCount.toString(), Modifier.weight(1f), style)
                MetricCard(SimpleperfViewerRes.sp_report_event_weight, report.overview.totalEventWeight.toString(), Modifier.weight(1f), style)
                MetricCard(SimpleperfViewerRes.sp_target_threads, report.overview.threadCount.toString(), Modifier.weight(1f), style)
                MetricCard(
                    SimpleperfViewerRes.sp_report_lost_rate,
                    "%.2f%%".format(report.quality.lostRate * PERCENT_MULTIPLIER),
                    Modifier.weight(1f),
                    style,
                )
            }
        }
        item {
            MacOsPanel(Modifier.fillMaxWidth(), style) {
                Text(
                    localizedStringResource(SimpleperfViewerRes.sp_report_data_quality, language),
                    color = style.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    localizedStringResource(SimpleperfViewerRes.sp_diagnostics_lost_samples_value_format, language, report.quality.lostSampleCount),
                    color = style.text,
                    fontSize = 10.sp,
                )
                Text(
                    localizedStringResource(
                        SimpleperfViewerRes.sp_diagnostics_unwind_errors_value_format,
                        language,
                        report.quality.unwindErrorSamples,
                    ),
                    color = style.text,
                    fontSize = 10.sp,
                )
                Text(
                    localizedStringResource(
                        SimpleperfViewerRes.sp_diagnostics_unknown_symbols_value_format,
                        language,
                        report.quality.unknownSymbolSamples,
                    ),
                    color = style.text,
                    fontSize = 10.sp,
                )
                Text(
                    localizedStringResource(SimpleperfViewerRes.sp_diagnostics_empty_stacks_value_format, language, report.quality.emptyStackSamples),
                    color = style.text,
                    fontSize = 10.sp,
                )
            }
        }
        item { SectionTitle(SimpleperfViewerRes.sp_report_top_threads, style) }
        items(report.topThreads.take(OVERVIEW_ITEM_LIMIT), key = { it.threadId }) { thread ->
            Text(
                "${thread.name} · TID ${thread.threadId} · weight ${thread.totalEventCount}",
                color = style.text,
                fontSize = 10.sp,
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
                Text(function.symbolName, modifier = Modifier.weight(1f), color = style.text, fontSize = 10.sp)
                Text(
                    localizedStringResource(
                        SimpleperfViewerRes.sp_report_inclusive_exclusive_summary_format,
                        language,
                        function.inclusiveWeight,
                        function.exclusiveWeight,
                    ),
                    color = style.secondaryText,
                    fontSize = 9.sp,
                )
            }
        }
        item { SectionTitle(SimpleperfViewerRes.sp_report_artifacts, style) }
        items(report.session.artifacts, key = { it.name }) { artifact ->
            Text(
                "${if (artifact.exists) "✓" else "–"} ${artifact.name} · ${artifact.path}",
                color = style.text,
                fontSize = 10.sp,
            )
        }
        item { SectionTitle(SimpleperfViewerRes.sp_diagnostics_diagnostics, style) }
        if (report.diagnostics.isEmpty()) {
            item {
                Text(
                    localizedStringResource(SimpleperfViewerRes.sp_diagnostics_no_findings_empty_state, language),
                    color = style.secondaryText,
                    fontSize = 10.sp,
                )
            }
        } else {
            items(report.diagnostics, key = { it.ruleId }) { finding ->
                DiagnosticCard(finding, actions, style)
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun MetricCard(
    title: StringResource,
    value: String,
    modifier: Modifier,
    style: ViewerColors,
) {
    MacOsPanel(modifier, style) {
        Text(
            localizedStringResource(title, currentSimpleperfLanguage()),
            color = style.secondaryText,
            fontSize = 9.sp,
        )
        Text(value, color = style.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TopFunctionSort.entries.forEach { sort ->
                MacOsChoiceChip(
                    label = sort.displayName(language),
                    selected = state.topSort == sort,
                    enabled = true,
                    style = style,
                ) { actions.onTopFunctionSort(sort, state.topDescending) }
            }
            MacOsButton(
                localizedStringResource(
                    if (state.topDescending) SimpleperfViewerRes.sp_calltree_descending else SimpleperfViewerRes.sp_calltree_ascending,
                    language,
                ),
                { actions.onTopFunctionSort(state.topSort, !state.topDescending) },
                style,
            )
        }
        TopFunctionHeader(style)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            itemsIndexed(report.topFunctions, key = ::topFunctionItemKey) { _, function ->
                TopFunctionRow(
                    function = function,
                    onSelect = { actions.onSelectTopFunction(function.symbolName) },
                    onFocusCallTree = actions.onFocusCallTreeFunction,
                    onFocusFlame = actions.onFocusFunction,
                    style = style,
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun TopFunctionHeader(style: ViewerColors) {
    val language = currentSimpleperfLanguage()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_calltree_function_library, language),
            modifier = Modifier.weight(1f),
            color = style.secondaryText,
            fontSize = 9.sp,
        )
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_calltree_inclusive, language),
            modifier = Modifier.width(90.dp),
            color = style.secondaryText,
            fontSize = 9.sp,
        )
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_calltree_exclusive, language),
            modifier = Modifier.width(90.dp),
            color = style.secondaryText,
            fontSize = 9.sp,
        )
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_report_samples, language),
            modifier = Modifier.width(70.dp),
            color = style.secondaryText,
            fontSize = 9.sp,
        )
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_target_threads, language),
            modifier = Modifier.width(70.dp),
            color = style.secondaryText,
            fontSize = 9.sp,
        )
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_calltree_navigate, language),
            modifier = Modifier.width(180.dp),
            color = style.secondaryText,
            fontSize = 9.sp,
        )
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun TopFunctionRow(
    function: TopFunction,
    onSelect: () -> Unit,
    onFocusCallTree: (String) -> Unit,
    onFocusFlame: (String) -> Unit,
    style: ViewerColors,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("top-function-row-${function.symbolName}")
                .background(style.panel, RoundedCornerShape(9.dp))
                .border(ViewerDimensions.hairline, style.border, RoundedCornerShape(9.dp))
                .clickable(onClick = onSelect)
                .padding(horizontal = 20.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(function.symbolName, color = style.text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Text(function.filePath, color = style.secondaryText, fontSize = 8.sp)
        }
        Text(function.inclusiveWeight.toString(), modifier = Modifier.width(90.dp), color = style.text, fontSize = 10.sp)
        Text(function.exclusiveWeight.toString(), modifier = Modifier.width(90.dp), color = style.text, fontSize = 10.sp)
        Text(function.sampleCount.toString(), modifier = Modifier.width(70.dp), color = style.text, fontSize = 10.sp)
        Text(function.threadCount.toString(), modifier = Modifier.width(70.dp), color = style.text, fontSize = 10.sp)
        Row(modifier = Modifier.width(180.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MacOsButton(
                localizedStringResource(SimpleperfViewerRes.sp_calltree_path, currentSimpleperfLanguage()),
                { onFocusCallTree(function.symbolName) },
                style,
            )
            MacOsButton(
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(style.panel)
                .border(ViewerDimensions.hairline, style.border),
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
                        onSelect = { actions.onSelectCallNode(FlameCallNodeId(node.id)) },
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
        fontSize = 9.sp,
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
        fontSize = 9.sp,
        lineHeight = FIREFOX_CALL_TREE_ROW_HEIGHT.value.sp,
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
        fontSize = 10.sp,
        lineHeight = FIREFOX_CALL_TREE_ROW_HEIGHT.value.sp,
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
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(style.panel, RoundedCornerShape(9.dp))
                .border(ViewerDimensions.hairline, accent, RoundedCornerShape(9.dp))
                .clickable {
                    actions.onSelectOverviewFinding(finding.ruleId)
                    navigation?.invoke()
                }.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                finding.title,
                modifier = Modifier.weight(1f),
                color = style.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                localizedStringResource(finding.severity.resource(), language),
                color = accent,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(finding.conclusion, color = style.text, fontSize = 10.sp)
        finding.evidence.forEach { evidence ->
            Row(Modifier.fillMaxWidth()) {
                Text(evidence.label, modifier = Modifier.weight(1f), color = style.secondaryText, fontSize = 9.sp)
                Text(evidence.value, color = style.text, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (finding.recommendations.isNotEmpty()) {
            Text(
                localizedStringResource(SimpleperfViewerRes.sp_finding_recommendations, language),
                color = style.text,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            finding.recommendations.forEach {
                Text(
                    localizedStringResource(SimpleperfViewerRes.sp_common_bullet_format, language, it),
                    color = style.secondaryText,
                    fontSize = 9.sp,
                )
            }
        }
        if (navigation != null) {
            Text(
                localizedStringResource(SimpleperfViewerRes.sp_finding_inspect_evidence_action, language),
                color = accent,
                fontSize = 9.sp,
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
        fontSize = 12.sp,
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
