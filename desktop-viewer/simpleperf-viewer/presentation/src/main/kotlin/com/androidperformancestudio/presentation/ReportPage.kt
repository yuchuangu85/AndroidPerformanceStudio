@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("TooManyFunctions", "LongMethod", "MaxLineLength")

package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.analysis.DiagnosticFinding
import com.androidperformancestudio.analysis.DiagnosticSeverity
import com.androidperformancestudio.analysis.DiagnosticTarget
import com.androidperformancestudio.application.ReportController
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.profileanalysis.AnalysisTimeRange
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.storage.CallTreeNode
import com.androidperformancestudio.storage.TopFunction
import com.androidperformancestudio.storage.TopFunctionSort
import com.androidperformancestudio.visualization.NavigationAction
import com.androidperformancestudio.visualization.TimeViewport
import com.androidperformancestudio.visualization.TimelineCanvas
import com.androidperformancestudio.visualization.TimelineColumn
import com.androidperformancestudio.visualization.TimelineFrame
import com.androidperformancestudio.visualization.navigate

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
fun ReportPage(
    state: ReportState,
    actions: ReportActions,
    darkTheme: Boolean = false,
) {
    ReportWorkspace(state, actions, macOsDeviceTargetStyle(darkTheme), Modifier.fillMaxSize())
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun ReportWorkspace(
    state: ReportState,
    actions: ReportActions,
    style: MacOsDeviceTargetStyle,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxSize()
            .background(style.workspace)
            .border(MacOsDeviceTargetDimensions.hairline, style.border),
    ) {
        ReportNavigation(state.selectedTab, actions.onSelectTab, style)
        ReportResultPane(state, actions, style, Modifier.weight(1f))
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun ReportNavigation(
    selectedTab: ReportTab,
    onSelectTab: (ReportTab) -> Unit,
    style: MacOsDeviceTargetStyle,
) {
    Column(
        Modifier
            .width(180.dp)
            .fillMaxHeight()
            .background(style.toolbar)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("Report", color = style.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("Analysis", color = style.secondaryText, fontSize = 10.sp)
        Spacer(Modifier.height(8.dp))
        ReportTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            val label = tab.displayName()
            val localizedLabel = localizedSimpleperfText(label)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (selected) style.accent.copy(alpha = 0.16f) else style.toolbar,
                        RoundedCornerShape(6.dp),
                    ).clickable { onSelectTab(tab) }
                    .semantics {
                        contentDescription = localizedLabel
                        this.selected = selected
                    }.padding(horizontal = 9.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    color = if (selected) style.accent else style.text,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun ReportResultPane(
    state: ReportState,
    actions: ReportActions,
    style: MacOsDeviceTargetStyle,
    modifier: Modifier,
) {
    Box(modifier.fillMaxHeight().padding(14.dp)) {
        when (val loadState = state.loadState) {
            ReportLoadState.Closed -> ReportStatus("Open a profile session to view its report.", style)
            is ReportLoadState.Loading -> ReportStatus("Loading ${loadState.sessionDirectory.fileName}…", style)
            is ReportLoadState.Failed ->
                ReportStatus(
                    "${loadState.error.code}: ${loadState.error.message}",
                    style,
                    actions.onCloseSession,
                )
            is ReportLoadState.Ready -> ReportContent(state, loadState.report, actions, style)
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun ReportStatus(
    message: String,
    style: MacOsDeviceTargetStyle,
    onClose: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().background(style.panel, RoundedCornerShape(10.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, color = style.text, fontSize = 13.sp)
        onClose?.let { MacOsButton("Back", it, style) }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun ReportContent(
    state: ReportState,
    report: ReportData,
    actions: ReportActions,
    style: MacOsDeviceTargetStyle,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(style.panel, RoundedCornerShape(10.dp))
                .border(MacOsDeviceTargetDimensions.hairline, style.border, RoundedCornerShape(10.dp))
                .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ReportHeader(report, actions, style)
        Box(Modifier.fillMaxWidth().height(MacOsDeviceTargetDimensions.hairline).background(style.border))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (state.selectedTab) {
                ReportTab.OVERVIEW -> OverviewReport(report, actions, style)
                ReportTab.TIMELINE -> TimelineReport(state, report, actions, style)
                ReportTab.TOP_FUNCTIONS -> TopFunctionsReport(state, report, actions, style)
                ReportTab.CALL_TREE -> CallTreeReport(state, report, actions, style)
                ReportTab.FLAME_GRAPH ->
                    FlameGraphPanel(report.session.directory, state.flameGraph, report.flameGraph, actions)
                ReportTab.DIAGNOSTICS -> DiagnosticsReport(report, actions, style)
            }
        }
        Text(ReportController.WEIGHT_SEMANTICS, color = style.secondaryText, fontSize = 9.sp)
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun ReportHeader(
    report: ReportData,
    actions: ReportActions,
    style: MacOsDeviceTargetStyle,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(report.session.name, color = style.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(report.session.directory.toString(), color = style.secondaryText, fontSize = 9.sp)
        }
        MacOsButton("Close report", actions.onCloseSession, style)
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun OverviewReport(
    report: ReportData,
    actions: ReportActions,
    style: MacOsDeviceTargetStyle,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("Samples", report.overview.sampleCount.toString(), Modifier.weight(1f), style)
                MetricCard("Event weight", report.overview.totalEventWeight.toString(), Modifier.weight(1f), style)
                MetricCard("Threads", report.overview.threadCount.toString(), Modifier.weight(1f), style)
                MetricCard(
                    "Lost rate",
                    "%.2f%%".format(report.quality.lostRate * PERCENT_MULTIPLIER),
                    Modifier.weight(1f),
                    style,
                )
            }
        }
        item {
            MacOsPanel(Modifier.fillMaxWidth(), style) {
                Text("Data quality", color = style.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("Lost samples: ${report.quality.lostSampleCount}", color = style.text, fontSize = 10.sp)
                Text("Unwind errors: ${report.quality.unwindErrorSamples}", color = style.text, fontSize = 10.sp)
                Text("Unknown symbols: ${report.quality.unknownSymbolSamples}", color = style.text, fontSize = 10.sp)
                Text("Empty stacks: ${report.quality.emptyStackSamples}", color = style.text, fontSize = 10.sp)
            }
        }
        item { SectionTitle("Top threads", style) }
        items(report.topThreads.take(OVERVIEW_ITEM_LIMIT), key = { it.threadId }) { thread ->
            Text(
                "${thread.name} · TID ${thread.threadId} · weight ${thread.totalEventCount}",
                color = style.text,
                fontSize = 10.sp,
            )
        }
        item { SectionTitle("Top functions", style) }
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
                    "inc ${function.inclusiveWeight} · exc ${function.exclusiveWeight}",
                    color = style.secondaryText,
                    fontSize = 9.sp,
                )
            }
        }
        item { SectionTitle("Artifacts", style) }
        items(report.session.artifacts, key = { it.name }) { artifact ->
            Text(
                "${if (artifact.exists) "✓" else "–"} ${artifact.name} · ${artifact.path}",
                color = style.text,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun MetricCard(
    title: String,
    value: String,
    modifier: Modifier,
    style: MacOsDeviceTargetStyle,
) {
    MacOsPanel(modifier, style) {
        Text(title, color = style.secondaryText, fontSize = 9.sp)
        Text(value, color = style.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
@Suppress("FunctionName", "LongMethod", "ktlint:standard:function-naming")
private fun TimelineReport(
    state: ReportState,
    report: ReportData,
    actions: ReportActions,
    style: MacOsDeviceTargetStyle,
) {
    val fullStart = report.sessionOverview.startNanos ?: 0L
    val fullEnd = (report.sessionOverview.endNanosInclusive ?: fullStart).safeIncrement()
    val bounds = TimeViewport(fullStart, fullEnd.coerceAtLeast(fullStart + 1))
    val viewport =
        TimeViewport(
            state.filter.startNanosInclusive ?: bounds.startNanos,
            state.filter.endNanosExclusive ?: bounds.endNanosExclusive,
        )
    val frame = TimelineFrame(report.timeline.map { TimelineColumn(it.eventWeight) })
    val shortcutFocusRequester = remember { FocusRequester() }

    fun navigate(action: NavigationAction) {
        val next = viewport.navigate(action, bounds)
        actions.onTimeRange(next.startNanos, next.endNanosExclusive)
    }
    LaunchedEffect(shortcutFocusRequester) { shortcutFocusRequester.requestFocus() }
    Column(
        modifier =
            Modifier
                .focusRequester(shortcutFocusRequester)
                .onPreviewKeyEvent { event -> handleKey(event, ::navigate) }
                .focusable(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NavigationButtons(::navigate, style)
            MacOsButton("Reset range", { actions.onTimeRange(null, null) }, style)
            Text("${viewport.startNanos} – ${viewport.endNanosExclusive} ns", color = style.secondaryText, fontSize = 10.sp)
        }
        TimelineCanvas(
            frame = frame,
            viewport = viewport,
            onRangePreview = { preview ->
                actions.onFlamePreviewRange(
                    AnalysisTimeRange(
                        preview.startNanos,
                        preview.endNanosExclusive,
                    ),
                )
            },
            onRangeCommit = { selected ->
                actions.onTimeRange(selected.startNanos, selected.endNanosExclusive)
            },
            onRangeCancel = actions.onCancelFlamePreview,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .onPointerEvent(PointerEventType.Scroll) { event ->
                        val change = event.changes.firstOrNull() ?: return@onPointerEvent
                        if (event.keyboardModifiers.isCtrlPressed) {
                            when {
                                change.scrollDelta.y < 0f -> navigate(NavigationAction.ZOOM_IN)
                                change.scrollDelta.y > 0f -> navigate(NavigationAction.ZOOM_OUT)
                            }
                        }
                    },
        )
        Text(
            "Drag across the timeline to select a range. W/S zoom, A/D pan, Ctrl+wheel zooms.",
            color = style.secondaryText,
            fontSize = 9.sp,
        )
        SectionTitle("Thread filter", style)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            report.sessionThreads.forEach { thread ->
                val selected = thread.threadId in state.filter.threadIds
                MacOsChoiceChip(
                    label = "${thread.name} (${thread.threadId})",
                    selected = selected,
                    enabled = true,
                    style = style,
                ) {
                    val next = state.filter.threadIds.toMutableSet()
                    if (!next.add(thread.threadId)) next.remove(thread.threadId)
                    actions.onThreads(next)
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun NavigationButtons(
    onAction: (NavigationAction) -> Unit,
    style: MacOsDeviceTargetStyle,
) {
    listOf(
        "W +" to NavigationAction.ZOOM_IN,
        "S −" to NavigationAction.ZOOM_OUT,
        "A ←" to NavigationAction.PAN_LEFT,
        "D →" to NavigationAction.PAN_RIGHT,
    ).forEach { (label, action) ->
        MacOsButton(label, { onAction(action) }, style)
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun TopFunctionsReport(
    state: ReportState,
    report: ReportData,
    actions: ReportActions,
    style: MacOsDeviceTargetStyle,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MacOsTextField(
            label = "Search function or library",
            value = state.topSearch,
            enabled = true,
            onValueChange = { actions.onTopFunctions(it, state.topSort, state.topDescending) },
            style = style,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TopFunctionSort.entries.forEach { sort ->
                MacOsChoiceChip(
                    label = sort.displayName(),
                    selected = state.topSort == sort,
                    enabled = true,
                    style = style,
                ) { actions.onTopFunctions(state.topSearch, sort, state.topDescending) }
            }
            MacOsButton(
                if (state.topDescending) "Descending" else "Ascending",
                { actions.onTopFunctions(state.topSearch, state.topSort, !state.topDescending) },
                style,
            )
        }
        TopFunctionHeader(style)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(report.topFunctions, key = ::topFunctionItemKey) { _, function ->
                TopFunctionRow(function, actions.onFocusCallTreeFunction, actions.onFocusFunction, style)
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun TopFunctionHeader(style: MacOsDeviceTargetStyle) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Function / Library", modifier = Modifier.weight(1f), color = style.secondaryText, fontSize = 9.sp)
        Text("Inclusive", modifier = Modifier.width(90.dp), color = style.secondaryText, fontSize = 9.sp)
        Text("Exclusive", modifier = Modifier.width(90.dp), color = style.secondaryText, fontSize = 9.sp)
        Text("Samples", modifier = Modifier.width(70.dp), color = style.secondaryText, fontSize = 9.sp)
        Text("Threads", modifier = Modifier.width(70.dp), color = style.secondaryText, fontSize = 9.sp)
        Text("Navigate", modifier = Modifier.width(180.dp), color = style.secondaryText, fontSize = 9.sp)
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun TopFunctionRow(
    function: TopFunction,
    onFocusCallTree: (String) -> Unit,
    onFocusFlame: (String) -> Unit,
    style: MacOsDeviceTargetStyle,
) {
    MacOsPanel(Modifier.fillMaxWidth(), style) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(function.symbolName, color = style.text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(function.filePath, color = style.secondaryText, fontSize = 8.sp)
            }
            Text(function.inclusiveWeight.toString(), modifier = Modifier.width(90.dp), color = style.text, fontSize = 10.sp)
            Text(function.exclusiveWeight.toString(), modifier = Modifier.width(90.dp), color = style.text, fontSize = 10.sp)
            Text(function.sampleCount.toString(), modifier = Modifier.width(70.dp), color = style.text, fontSize = 10.sp)
            Text(function.threadCount.toString(), modifier = Modifier.width(70.dp), color = style.text, fontSize = 10.sp)
            Row(modifier = Modifier.width(180.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MacOsButton("Path", { onFocusCallTree(function.symbolName) }, style)
                MacOsButton("Flame", { onFocusFlame(function.symbolName) }, style)
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "LongMethod", "ktlint:standard:function-naming")
private fun CallTreeReport(
    state: ReportState,
    report: ReportData,
    actions: ReportActions,
    style: MacOsDeviceTargetStyle,
) {
    var expandedIds by remember(report.callTree) {
        mutableStateOf(report.callTree.filter { it.parentId == null }.mapTo(mutableSetOf(), CallTreeNode::id))
    }
    val listState = rememberLazyListState()
    val selectedNodeId = state.flameGraph.selectedNodeId
    LaunchedEffect(report.callTree, state.callTreeSearch) {
        if (state.callTreeSearch.isNotBlank()) {
            expandedIds = (expandedIds + report.callTree.expandedPathIds(state.callTreeSearch)).toMutableSet()
        }
    }
    LaunchedEffect(report.callTree, selectedNodeId) {
        if (selectedNodeId != null) {
            expandedIds = (expandedIds + report.callTree.selectedPathIds(selectedNodeId)).toMutableSet()
        }
    }
    val visible = remember(report.callTree, expandedIds) { report.callTree.visibleNodes(expandedIds) }
    LaunchedEffect(visible, selectedNodeId) {
        visible.selectedNodeIndex(selectedNodeId).takeIf { index -> index >= 0 }?.let { index ->
            listState.animateScrollToItem(index)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CallStackDirection.entries.forEach { direction ->
                MacOsChoiceChip(
                    label = if (direction == CallStackDirection.FORWARD) "Call Tree" else "Reverse Call Tree",
                    selected = state.callTreeDirection == direction,
                    enabled = true,
                    style = style,
                ) { actions.onCallTreeDirection(direction) }
            }
        }
        MacOsTextField(
            label = "Find function in call paths",
            value = state.callTreeSearch,
            enabled = true,
            onValueChange = actions.onFocusCallTreeFunction,
            style = style,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(visible, key = CallTreeNode::id) { node ->
                val hasChildren = report.callTree.any { it.parentId == node.id }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (selectedNodeId?.value == node.id) {
                                    style.accent.copy(alpha = 0.16f)
                                } else {
                                    style.panel
                                },
                            ).clickable {
                                actions.onSelectCallNode(FlameCallNodeId(node.id))
                                if (hasChildren) {
                                    expandedIds =
                                        expandedIds.toMutableSet().also {
                                            if (!it.add(node.id)) it.remove(node.id)
                                        }
                                }
                            }.padding(start = (node.depth * 18).dp, top = 6.dp, bottom = 6.dp),
                ) {
                    Text(
                        if (hasChildren) {
                            if (node.id in expandedIds) {
                                "▾ "
                            } else {
                                "▸ "
                            }
                        } else {
                            "  "
                        },
                    )
                    Text(node.symbolName, modifier = Modifier.weight(1f), color = style.text, fontSize = 10.sp)
                    if (state.callTreeSearch.isNotBlank() &&
                        node.symbolName.contains(state.callTreeSearch, ignoreCase = true)
                    ) {
                        Text("MATCH", color = style.accent, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                    }
                    Text("inc ${node.inclusiveWeight} · exc ${node.exclusiveWeight}", color = style.secondaryText, fontSize = 9.sp)
                }
            }
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
private fun DiagnosticsReport(
    report: ReportData,
    actions: ReportActions,
    style: MacOsDeviceTargetStyle,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(report.diagnostics, key = { it.ruleId }) { finding ->
            DiagnosticCard(finding, actions, style)
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun DiagnosticCard(
    finding: DiagnosticFinding,
    actions: ReportActions,
    style: MacOsDeviceTargetStyle,
) {
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
                .border(MacOsDeviceTargetDimensions.hairline, accent, RoundedCornerShape(9.dp))
                .then(if (navigation == null) Modifier else Modifier.clickable(onClick = navigation))
                .padding(12.dp),
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
            Text(finding.severity.name, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Text(finding.conclusion, color = style.text, fontSize = 10.sp)
        finding.evidence.forEach { evidence ->
            Row(Modifier.fillMaxWidth()) {
                Text(evidence.label, modifier = Modifier.weight(1f), color = style.secondaryText, fontSize = 9.sp)
                Text(evidence.value, color = style.text, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (finding.recommendations.isNotEmpty()) {
            Text("Recommendations", color = style.text, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            finding.recommendations.forEach { Text("• $it", color = style.secondaryText, fontSize = 9.sp) }
        }
        if (navigation != null) Text("Click to inspect evidence", color = accent, fontSize = 9.sp)
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
                    actions.onSelectTab(ReportTab.TIMELINE)
                }
            }
        null -> null
    }

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun SectionTitle(
    title: String,
    style: MacOsDeviceTargetStyle,
) {
    Text(title, color = style.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

private fun ReportTab.displayName(): String = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

private fun TopFunctionSort.displayName(): String = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

internal fun topFunctionItemKey(
    index: Int,
    function: TopFunction,
): String = "$index:${function.filePath}:${function.symbolName}"

internal fun List<CallTreeNode>.visibleNodes(expandedIds: Set<Long>): List<CallTreeNode> {
    val children = groupBy(CallTreeNode::parentId)
    return buildList {
        fun append(parentId: Long?) {
            children[parentId].orEmpty().forEach { node ->
                add(node)
                if (node.id in expandedIds) append(node.id)
            }
        }
        append(null)
    }
}

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

private fun handleKey(
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

internal fun simpleperfNavigationAction(key: Key): NavigationAction? =
    when (key) {
        Key.W -> NavigationAction.ZOOM_IN
        Key.S -> NavigationAction.ZOOM_OUT
        Key.A -> NavigationAction.PAN_LEFT
        Key.D -> NavigationAction.PAN_RIGHT
        else -> null
    }

private fun Long.safeIncrement(): Long = if (this == Long.MAX_VALUE) this else this + 1

private const val PERCENT_MULTIPLIER = 100
private const val OVERVIEW_ITEM_LIMIT = 8
