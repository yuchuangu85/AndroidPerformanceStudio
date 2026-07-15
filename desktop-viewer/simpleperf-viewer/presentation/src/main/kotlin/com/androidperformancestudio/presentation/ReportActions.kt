package com.androidperformancestudio.presentation

import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.storage.TopFunctionSort

data class ReportActions(
    val onOpenSession: () -> Unit,
    val onCloseSession: () -> Unit,
    val onSelectTab: (ReportTab) -> Unit,
    val onTimeRange: (Long?, Long?) -> Unit,
    val onThreads: (Set<Int>) -> Unit,
    val onEvents: (Set<String>) -> Unit,
    val onTopFunctions: (String, TopFunctionSort, Boolean) -> Unit,
    val onCallTreeDirection: (CallStackDirection) -> Unit,
    val onFocusCallTreeFunction: (String) -> Unit,
    val onFocusFunction: (String) -> Unit,
    val onExportSession: () -> Unit,
    val onExportReport: () -> Unit,
    val onExportRawProtobuf: () -> Unit,
    val onExportScreenshot: () -> Unit,
    val onGenerateSimpleperfReport: () -> Unit,
    val onGenerateHtmlReport: () -> Unit,
    val onExportExternalGuide: () -> Unit,
)
