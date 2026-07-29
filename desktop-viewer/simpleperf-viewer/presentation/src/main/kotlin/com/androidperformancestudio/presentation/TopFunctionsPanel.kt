package com.androidperformancestudio.presentation

import androidx.compose.runtime.Composable
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.ui.MacOsDeviceTargetStyle

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun TopFunctionsPanel(
    state: ReportState,
    report: ReportData,
    actions: ReportActions,
    style: MacOsDeviceTargetStyle,
) = TopFunctionsReport(state, report, actions, style)
