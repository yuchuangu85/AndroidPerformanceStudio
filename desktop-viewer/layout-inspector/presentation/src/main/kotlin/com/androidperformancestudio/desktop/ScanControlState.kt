package com.androidperformancestudio.desktop

internal data class ScanControlState(
    val autoScanEnabled: Boolean,
    val manualRefreshInProgress: Boolean = false,
) {
    val showManualRefresh: Boolean
        get() = !autoScanEnabled

    val manualRefreshEnabled: Boolean
        get() = showManualRefresh && !manualRefreshInProgress
}
