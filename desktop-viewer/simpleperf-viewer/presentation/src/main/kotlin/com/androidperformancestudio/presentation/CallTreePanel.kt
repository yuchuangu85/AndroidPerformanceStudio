package com.androidperformancestudio.presentation

import androidx.compose.runtime.Composable
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.ui.MacOsDeviceTargetStyle

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun CallTreePanel(
    state: ReportState,
    report: ReportData,
    actions: ReportActions,
    style: MacOsDeviceTargetStyle,
) = CallTreeReport(state, report, actions, style)
