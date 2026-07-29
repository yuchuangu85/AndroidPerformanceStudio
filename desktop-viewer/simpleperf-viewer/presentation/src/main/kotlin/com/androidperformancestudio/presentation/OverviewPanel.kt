package com.androidperformancestudio.presentation

import androidx.compose.runtime.Composable
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.ui.ViewerColors

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun OverviewPanel(
    report: ReportData,
    actions: ReportActions,
    style: ViewerColors,
) = OverviewReport(report, actions, style)
