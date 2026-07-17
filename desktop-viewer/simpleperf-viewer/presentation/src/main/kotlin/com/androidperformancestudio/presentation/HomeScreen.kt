package com.androidperformancestudio.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.androidperformancestudio.application.DeviceTargetState
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.capture.CaptureState

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
fun HomeScreen(
    state: DeviceTargetState,
    captureState: CaptureState,
    reportState: ReportState,
    actions: DeviceTargetActions,
    reportActions: ReportActions,
    darkTheme: Boolean = false,
    language: SimpleperfLanguage = SimpleperfLanguage.ENGLISH,
) {
    SimpleperfLocalization(language) {
        MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
            Surface(modifier = Modifier.fillMaxSize()) {
                if (reportState.loadState != ReportLoadState.Closed) {
                    ReportPage(reportState, reportActions)
                } else {
                    DeviceTargetPage(state, captureState, actions, darkTheme)
                }
            }
        }
    }
}
