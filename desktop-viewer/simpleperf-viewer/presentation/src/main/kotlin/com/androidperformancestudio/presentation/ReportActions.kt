package com.androidperformancestudio.presentation

import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.profileanalysis.AnalysisTimeRange
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.CallStackTransform
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameGraphNavigationCommand
import com.androidperformancestudio.profileanalysis.ImplementationFilter
import com.androidperformancestudio.profileanalysis.StackChartBlockId
import com.androidperformancestudio.storage.ProfileMarkerId
import com.androidperformancestudio.storage.TopFunctionSort

data class ReportActions(
    val onOpenSession: () -> Unit,
    val onCloseSession: () -> Unit,
    val onSelectTab: (ReportTab) -> Unit,
    val onTimeRange: (Long?, Long?) -> Unit,
    val onThreads: (Set<Int>) -> Unit,
    val onEvents: (Set<String>) -> Unit,
    val onTopFunctionSort: (TopFunctionSort, Boolean) -> Unit,
    val onCallTreeDirection: (CallStackDirection) -> Unit,
    val onFlamePreviewRange: (AnalysisTimeRange) -> Unit,
    val onCancelFlamePreview: () -> Unit,
    val onFlameSearch: (String) -> Unit,
    val onFlameImplementation: (ImplementationFilter) -> Unit,
    val onApplyFlameTransform: (CallStackTransform) -> Unit,
    val onUndoFlameTransform: () -> Unit,
    val onClearFlameTransforms: () -> Unit,
    val onRetryFlameProjection: () -> Unit,
    val onSelectCallNode: (FlameCallNodeId?) -> Unit,
    val onHoverFlameNode: (FlameCallNodeId?) -> Unit,
    val onOpenFlameContext: (FlameCallNodeId?) -> Unit,
    val onOpenFlameDetails: (FlameCallNodeId) -> Unit,
    val onCloseFlameDetails: () -> Unit,
    val onCopyFlameFunction: (String) -> Unit,
    val onNavigateFlameNode: (FlameGraphNavigationCommand) -> FlameCallNodeId?,
    val onFocusCallTreeFunction: (String) -> Unit,
    val onFocusFunction: (String) -> Unit,
    val onExportSession: () -> Unit,
    val onExportReport: () -> Unit,
    val onExportRawProtobuf: () -> Unit,
    val onExportScreenshot: () -> Unit,
    val onGenerateSimpleperfReport: () -> Unit,
    val onGenerateHtmlReport: () -> Unit,
    val onExportExternalGuide: () -> Unit,
    val onExportGeckoProfile: () -> Unit = {},
    val onDetailsVisible: (Boolean) -> Unit = {},
    val onTimelineHeightDp: (Int) -> Unit = {},
    val onSelectOverviewFinding: (String?) -> Unit = {},
    val onSelectTopFunction: (String?) -> Unit = {},
    val onSelectStackChartBlock: (StackChartBlockId?) -> Unit = {},
    val onSelectMarker: (ProfileMarkerId?) -> Unit = {},
    val onMarkerSearch: (String) -> Unit = {},
)
