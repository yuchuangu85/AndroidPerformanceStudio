@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("TooManyFunctions")

package com.androidperformancestudio.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.analysis.DiagnosticFinding
import com.androidperformancestudio.analysis.DiagnosticSeverity
import com.androidperformancestudio.analysis.DiagnosticTarget
import com.androidperformancestudio.application.ReportController
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.storage.CallTreeNode
import com.androidperformancestudio.storage.TopFunction
import com.androidperformancestudio.storage.TopFunctionSort
import com.androidperformancestudio.visualization.FlameGraphCanvas
import com.androidperformancestudio.visualization.FlameGraphIntent
import com.androidperformancestudio.visualization.FlameGraphLayout
import com.androidperformancestudio.visualization.FlameViewport
import com.androidperformancestudio.visualization.NavigationAction
import com.androidperformancestudio.visualization.PerfettoNavigationBindings
import com.androidperformancestudio.visualization.TimeViewport
import com.androidperformancestudio.visualization.TimelineCanvas
import com.androidperformancestudio.visualization.TimelineColumn
import com.androidperformancestudio.visualization.TimelineFrame
import com.androidperformancestudio.visualization.navigate
import com.androidperformancestudio.visualization.selection

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
fun ReportPage(
    state: ReportState,
    actions: ReportActions,
) {
    when (val loadState = state.loadState) {
        ReportLoadState.Closed -> Unit
        is ReportLoadState.Loading -> StatusPage("Loading ${loadState.sessionDirectory.fileName}…")
        is ReportLoadState.Failed ->
            StatusPage(
                "${loadState.error.code}: ${loadState.error.message}",
                onClose = actions.onCloseSession,
            )
        is ReportLoadState.Ready -> ReportContent(state, loadState.report, actions)
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun StatusPage(
    message: String,
    onClose: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, style = MaterialTheme.typography.headlineSmall)
        onClose?.let { OutlinedButton(onClick = it) { Text("Back") } }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun ReportContent(
    state: ReportState,
    report: ReportData,
    actions: ReportActions,
) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReportHeader(state, report, actions)
        HorizontalDivider()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (state.selectedTab) {
                ReportTab.OVERVIEW -> OverviewReport(report, actions)
                ReportTab.TIMELINE -> TimelineReport(state, report, actions)
                ReportTab.TOP_FUNCTIONS -> TopFunctionsReport(state, report, actions)
                ReportTab.CALL_TREE -> CallTreeReport(state, report, actions)
                ReportTab.FLAME_GRAPH -> FlameGraphReport(state, report, actions)
                ReportTab.DIAGNOSTICS -> DiagnosticsReport(report, actions)
            }
        }
        Text(ReportController.WEIGHT_SEMANTICS, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun ReportHeader(
    state: ReportState,
    report: ReportData,
    actions: ReportActions,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(report.session.name, style = MaterialTheme.typography.headlineSmall)
            Text(report.session.directory.toString(), style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = actions.onCloseSession) { Text("Close report") }
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReportTab.entries.forEach { tab ->
            FilterChip(
                selected = state.selectedTab == tab,
                onClick = { actions.onSelectTab(tab) },
                label = { Text(tab.displayName()) },
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = actions.onExportSession) { Text("Session package") }
        OutlinedButton(onClick = actions.onExportReport) { Text("JSON + CSV") }
        OutlinedButton(onClick = actions.onExportRawProtobuf) { Text("Raw protobuf") }
        OutlinedButton(onClick = actions.onExportScreenshot) { Text("Screenshot") }
        OutlinedButton(onClick = actions.onGenerateSimpleperfReport) { Text("simpleperf report") }
        OutlinedButton(onClick = actions.onGenerateHtmlReport) { Text("report_html.py") }
        OutlinedButton(onClick = actions.onExportExternalGuide) { Text("External open guide") }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun OverviewReport(
    report: ReportData,
    actions: ReportActions,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("Samples", report.overview.sampleCount.toString(), Modifier.weight(1f))
                MetricCard("Event weight", report.overview.totalEventWeight.toString(), Modifier.weight(1f))
                MetricCard("Threads", report.overview.threadCount.toString(), Modifier.weight(1f))
                MetricCard(
                    "Lost rate",
                    "%.2f%%".format(report.quality.lostRate * PERCENT_MULTIPLIER),
                    Modifier.weight(1f),
                )
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Data quality", style = MaterialTheme.typography.titleMedium)
                    Text("Lost samples: ${report.quality.lostSampleCount}")
                    Text("Unwind errors: ${report.quality.unwindErrorSamples}")
                    Text("Unknown symbols: ${report.quality.unknownSymbolSamples}")
                    Text("Empty stacks: ${report.quality.emptyStackSamples}")
                }
            }
        }
        item { SectionTitle("Top threads") }
        items(report.topThreads.take(OVERVIEW_ITEM_LIMIT), key = { it.threadId }) { thread ->
            Text("${thread.name} · TID ${thread.threadId} · weight ${thread.totalEventCount}")
        }
        item { SectionTitle("Top functions") }
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
                Text(function.symbolName, modifier = Modifier.weight(1f))
                Text("inc ${function.inclusiveWeight} · exc ${function.exclusiveWeight}")
            }
        }
        item { SectionTitle("Artifacts") }
        items(report.session.artifacts, key = { it.name }) { artifact ->
            Text("${if (artifact.exists) "✓" else "–"} ${artifact.name} · ${artifact.path}")
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun MetricCard(
    title: String,
    value: String,
    modifier: Modifier,
) {
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
@Suppress("FunctionName", "LongMethod", "ktlint:standard:function-naming")
private fun TimelineReport(
    state: ReportState,
    report: ReportData,
    actions: ReportActions,
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
    var widthPixels by remember { mutableIntStateOf(1) }
    var dragStart by remember { mutableFloatStateOf(0f) }
    var dragEnd by remember { mutableFloatStateOf(0f) }

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
            NavigationButtons(::navigate)
            OutlinedButton(onClick = { actions.onTimeRange(null, null) }) { Text("Reset range") }
            Text("${viewport.startNanos} – ${viewport.endNanosExclusive} ns")
        }
        TimelineCanvas(
            frame = frame,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .onSizeChanged { widthPixels = it.width.coerceAtLeast(1) }
                    .onPointerEvent(PointerEventType.Scroll) { event ->
                        val change = event.changes.firstOrNull() ?: return@onPointerEvent
                        PerfettoNavigationBindings
                            .actionForScroll(change.scrollDelta.y, event.keyboardModifiers.isCtrlPressed)
                            ?.let(::navigate)
                    }.pointerInput(viewport, widthPixels) {
                        detectDragGestures(
                            onDragStart = {
                                dragStart = it.x
                                dragEnd = it.x
                            },
                            onDragEnd = {
                                val selected = viewport.selection(dragStart, dragEnd, widthPixels.toFloat())
                                actions.onTimeRange(selected.startNanos, selected.endNanosExclusive)
                            },
                        ) { change, _ -> dragEnd = change.position.x }
                    },
        )
        Text("Drag across the timeline to select a range. W/S zoom, A/D pan, Ctrl+wheel zooms.")
        SectionTitle("Thread filter")
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            report.sessionThreads.forEach { thread ->
                val selected = thread.threadId in state.filter.threadIds
                FilterChip(
                    selected = selected,
                    onClick = {
                        val next = state.filter.threadIds.toMutableSet()
                        if (!next.add(thread.threadId)) next.remove(thread.threadId)
                        actions.onThreads(next)
                    },
                    label = { Text("${thread.name} (${thread.threadId})") },
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun NavigationButtons(onAction: (NavigationAction) -> Unit) {
    listOf(
        "W +" to NavigationAction.ZOOM_IN,
        "S −" to NavigationAction.ZOOM_OUT,
        "A ←" to NavigationAction.PAN_LEFT,
        "D →" to NavigationAction.PAN_RIGHT,
    ).forEach { (label, action) ->
        OutlinedButton(onClick = { onAction(action) }) { Text(label) }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun TopFunctionsReport(
    state: ReportState,
    report: ReportData,
    actions: ReportActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = state.topSearch,
            onValueChange = { actions.onTopFunctions(it, state.topSort, state.topDescending) },
            label = { Text("Search function or library") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TopFunctionSort.entries.forEach { sort ->
                FilterChip(
                    selected = state.topSort == sort,
                    onClick = { actions.onTopFunctions(state.topSearch, sort, state.topDescending) },
                    label = { Text(sort.displayName()) },
                )
            }
            OutlinedButton(
                onClick = { actions.onTopFunctions(state.topSearch, state.topSort, !state.topDescending) },
            ) { Text(if (state.topDescending) "Descending" else "Ascending") }
        }
        TopFunctionHeader()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(report.topFunctions, key = ::topFunctionItemKey) { _, function ->
                TopFunctionRow(function, actions.onFocusCallTreeFunction, actions.onFocusFunction)
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun TopFunctionHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Function / Library", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Text("Inclusive", modifier = Modifier.width(90.dp), fontWeight = FontWeight.Bold)
        Text("Exclusive", modifier = Modifier.width(90.dp), fontWeight = FontWeight.Bold)
        Text("Samples", modifier = Modifier.width(70.dp), fontWeight = FontWeight.Bold)
        Text("Threads", modifier = Modifier.width(70.dp), fontWeight = FontWeight.Bold)
        Text("Navigate", modifier = Modifier.width(180.dp), fontWeight = FontWeight.Bold)
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun TopFunctionRow(
    function: TopFunction,
    onFocusCallTree: (String) -> Unit,
    onFocusFlame: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(function.symbolName, fontWeight = FontWeight.SemiBold)
                Text(function.filePath, style = MaterialTheme.typography.bodySmall)
            }
            Text(function.inclusiveWeight.toString(), modifier = Modifier.width(90.dp))
            Text(function.exclusiveWeight.toString(), modifier = Modifier.width(90.dp))
            Text(function.sampleCount.toString(), modifier = Modifier.width(70.dp))
            Text(function.threadCount.toString(), modifier = Modifier.width(70.dp))
            Row(modifier = Modifier.width(180.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { onFocusCallTree(function.symbolName) }) { Text("Path") }
                OutlinedButton(onClick = { onFocusFlame(function.symbolName) }) { Text("Flame") }
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
) {
    var expandedIds by remember(report.callTree) {
        mutableStateOf(report.callTree.filter { it.parentId == null }.mapTo(mutableSetOf(), CallTreeNode::id))
    }
    LaunchedEffect(report.callTree, state.callTreeSearch) {
        if (state.callTreeSearch.isNotBlank()) {
            expandedIds = (expandedIds + report.callTree.expandedPathIds(state.callTreeSearch)).toMutableSet()
        }
    }
    val visible = remember(report.callTree, expandedIds) { report.callTree.visibleNodes(expandedIds) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CallStackDirection.entries.forEach { direction ->
                FilterChip(
                    selected = state.callTreeDirection == direction,
                    onClick = { actions.onCallTreeDirection(direction) },
                    label = { Text(if (direction == CallStackDirection.FORWARD) "Call Tree" else "Reverse Call Tree") },
                )
            }
        }
        OutlinedTextField(
            value = state.callTreeSearch,
            onValueChange = actions.onFocusCallTreeFunction,
            label = { Text("Find function in call paths") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(visible, key = CallTreeNode::id) { node ->
                val hasChildren = report.callTree.any { it.parentId == node.id }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (hasChildren) {
                                    expandedIds =
                                        expandedIds.toMutableSet().also {
                                            if (!it.add(node.id)) it.remove(node.id)
                                        }
                                } else {
                                    actions.onFocusFunction(node.symbolName)
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
                    Text(node.symbolName, modifier = Modifier.weight(1f))
                    if (state.callTreeSearch.isNotBlank() &&
                        node.symbolName.contains(state.callTreeSearch, ignoreCase = true)
                    ) {
                        Text("MATCH", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    Text("inc ${node.inclusiveWeight} · exc ${node.exclusiveWeight}")
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
@Suppress("FunctionName", "LongMethod", "ktlint:standard:function-naming")
private fun FlameGraphReport(
    state: ReportState,
    report: ReportData,
    actions: ReportActions,
) {
    var hoveredId by remember(report.flameGraph) { mutableStateOf<FlameCallNodeId?>(null) }
    var contextId by remember(report.flameGraph) { mutableStateOf<FlameCallNodeId?>(null) }
    var widthPixels by remember { mutableIntStateOf(0) }
    var heightPixels by remember { mutableIntStateOf(0) }
    var scrollRow by remember(report.flameGraph) { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val requestedViewport =
        FlameViewport(
            widthPx = widthPixels,
            heightPx = heightPixels,
            scrollRow = scrollRow,
            rowHeightPx = FLAME_ROW_HEIGHT,
        )
    val clampedScrollRow = FlameGraphLayout.clampScrollRow(report.flameGraph, requestedViewport)
    val viewport = requestedViewport.copy(scrollRow = clampedScrollRow)
    val layout =
        remember(report.flameGraph, viewport) {
            FlameGraphLayout.layout(report.flameGraph, viewport)
        }
    val selected =
        remember(report.flameGraph, state.flameGraph.selectedNodeId) {
            state.flameGraph.selectedNodeId?.let(report.flameGraph::resolveLegacyNode)
        }

    LaunchedEffect(report.flameGraph, state.flameGraph.selectedNodeId, heightPixels) {
        val revealedScrollRow =
            state.flameGraph.selectedNodeId?.let { selectedNodeId ->
                FlameGraphLayout.scrollRowToReveal(report.flameGraph, selectedNodeId, viewport)
            } ?: clampedScrollRow
        if (scrollRow != revealedScrollRow) scrollRow = revealedScrollRow
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlameGraphCanvas(
            layout = layout,
            selectedNodeId = state.flameGraph.selectedNodeId,
            hoveredNodeId = hoveredId,
            contextNodeId = contextId,
            labelForNode = { node ->
                report.flameGraph.callNodes
                    .frameAt(node.nodeIndex)
                    ?.symbolName
                    .orEmpty()
            },
            onIntent = { intent ->
                when (intent) {
                    is FlameGraphIntent.Hover -> hoveredId = intent.nodeId
                    is FlameGraphIntent.Select -> {
                        actions.onSelectFlameNode(intent.nodeId)
                        contextId = null
                    }
                    is FlameGraphIntent.OpenContextMenu -> contextId = intent.nodeId
                    is FlameGraphIntent.OpenDetails -> actions.onSelectFlameNode(intent.nodeId)
                }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 520.dp)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        val command = FlameGraphKeyboardNavigation.commandFor(event.key, event.type)
                        val navigation =
                            command?.let { navigationCommand ->
                                FlameGraphKeyboardNavigation.navigate(
                                    report.flameGraph,
                                    state.flameGraph.selectedNodeId,
                                    navigationCommand,
                                    viewport,
                                )
                            }
                        if (navigation == null) {
                            false
                        } else {
                            actions.onSelectFlameNode(navigation.targetNodeId)
                            scrollRow = navigation.scrollRow
                            true
                        }
                    }.focusable()
                    .onPointerEvent(PointerEventType.Press) { focusRequester.requestFocus() }
                    .onSizeChanged { size ->
                        widthPixels = size.width
                        heightPixels = size.height
                    },
        )
        selected?.let { node ->
            Card(modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) {
                Column(Modifier.padding(12.dp)) {
                    Text(node.symbolName, fontWeight = FontWeight.Bold)
                    Text(node.filePath)
                    Text("Inclusive ${node.inclusiveWeight} · Exclusive ${node.exclusiveWeight}")
                    Text(node.path.joinToString(" › "))
                }
            }
        }
        Text("Click a frame to select it. Flame widths always represent the full analyzed sample set.")
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun DiagnosticsReport(
    report: ReportData,
    actions: ReportActions,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(report.diagnostics, key = { it.ruleId }) { finding ->
            DiagnosticCard(finding, actions)
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun DiagnosticCard(
    finding: DiagnosticFinding,
    actions: ReportActions,
) {
    val accent =
        when (finding.severity) {
            DiagnosticSeverity.INFO -> MaterialTheme.colorScheme.primary
            DiagnosticSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
            DiagnosticSeverity.CRITICAL -> MaterialTheme.colorScheme.error
        }
    val navigation = finding.navigation(actions)
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (navigation == null) Modifier else Modifier.clickable(onClick = navigation)),
        border = BorderStroke(1.dp, accent),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(finding.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(finding.severity.name, color = accent, fontWeight = FontWeight.Bold)
            }
            Text(finding.conclusion)
            finding.evidence.forEach { evidence ->
                Row(Modifier.fillMaxWidth()) {
                    Text(evidence.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(evidence.value, fontWeight = FontWeight.SemiBold)
                }
            }
            if (finding.recommendations.isNotEmpty()) {
                Text("Recommendations", fontWeight = FontWeight.Bold)
                finding.recommendations.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
            if (navigation != null) Text("Click to inspect evidence", color = accent)
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
                    actions.onSelectTab(ReportTab.TIMELINE)
                }
            }
        null -> null
    }

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

private fun ReportTab.displayName(): String = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

private fun TopFunctionSort.displayName(): String = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

internal fun topFunctionItemKey(
    index: Int,
    function: TopFunction,
): String = "$index:${function.filePath}:${function.symbolName}"

private fun List<CallTreeNode>.visibleNodes(expandedIds: Set<Long>): List<CallTreeNode> {
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

private const val FLAME_ROW_HEIGHT = 22f
private const val PERCENT_MULTIPLIER = 100
private const val OVERVIEW_ITEM_LIMIT = 8
