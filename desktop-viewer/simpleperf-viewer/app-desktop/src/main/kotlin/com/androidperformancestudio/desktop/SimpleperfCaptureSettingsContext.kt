package com.androidperformancestudio.desktop

import com.androidperformancestudio.application.CaptureSetup
import com.androidperformancestudio.capture.SamplingParameters
import com.androidperformancestudio.capture.SamplingTemplate

/** Live capture settings exposed to the unified desktop settings window. */
data class SimpleperfCaptureSettingsContext(
    val setup: CaptureSetup?,
    val availableEvents: List<String>,
    val enabled: Boolean,
    val onSelectTemplate: (SamplingTemplate) -> Unit,
    val onUpdateSamplingParameters: (SamplingParameters) -> Unit,
)
