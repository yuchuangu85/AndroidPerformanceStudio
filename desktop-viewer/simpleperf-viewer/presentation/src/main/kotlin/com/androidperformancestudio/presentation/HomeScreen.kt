package com.androidperformancestudio.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.androidperformancestudio.application.DeviceTargetState
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
    captureSettingsSection: CaptureSettingsSection? = null,
    onCaptureSettingsSectionChange: (CaptureSettingsSection?) -> Unit = {},
) {
    SimpleperfLocalization(language) {
        var localCaptureSettingsSection by remember { mutableStateOf(captureSettingsSection) }
        LaunchedEffect(captureSettingsSection) {
            localCaptureSettingsSection = captureSettingsSection
        }
        val activeCaptureSettingsSection = captureSettingsSection ?: localCaptureSettingsSection
        val updateCaptureSettingsSection: (CaptureSettingsSection?) -> Unit = { next ->
            localCaptureSettingsSection = next
            onCaptureSettingsSectionChange(next)
        }
        MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
            Surface(modifier = Modifier.fillMaxSize()) {
                DeviceTargetPage(
                    state,
                    captureState,
                    reportState,
                    actions,
                    reportActions,
                    darkTheme,
                    activeCaptureSettingsSection,
                    updateCaptureSettingsSection,
                )
            }
        }
    }
}
