package com.androidperformancestudio.presentation

import androidx.compose.runtime.Composable
import com.androidperformancestudio.application.ReportData

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun OverviewPanel(
    report: ReportData,
    actions: ReportActions,
    style: MacOsDeviceTargetStyle,
) = OverviewReport(report, actions, style)
