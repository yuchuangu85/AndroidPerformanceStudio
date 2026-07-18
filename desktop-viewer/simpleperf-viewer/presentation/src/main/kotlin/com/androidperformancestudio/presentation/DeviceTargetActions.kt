package com.androidperformancestudio.presentation

import com.androidperformancestudio.application.ThreadOption
import com.androidperformancestudio.capture.SamplingParameters
import com.androidperformancestudio.capture.SamplingTemplate

data class DeviceTargetActions(
    val onRefresh: () -> Unit,
    val onSelectDevice: (String) -> Unit,
    val onSearch: (String) -> Unit,
    val onSelectPackage: (String) -> Unit,
    val onSelectProcess: (Int) -> Unit,
    val onSelectThread: (ThreadOption) -> Unit,
    val onContinue: () -> Unit,
    val onBack: () -> Unit,
    val onSelectTemplate: (SamplingTemplate) -> Unit,
    val onUpdateSamplingParameters: (SamplingParameters) -> Unit,
    val onStartCapture: () -> Unit,
    val onStopCapture: () -> Unit,
    val onCancelCapture: () -> Unit,
    val onOpenPerfetto: () -> Unit = {},
)
