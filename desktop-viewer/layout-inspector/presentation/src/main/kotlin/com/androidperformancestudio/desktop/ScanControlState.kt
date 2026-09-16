package com.androidperformancestudio.desktop

internal enum class ScanMode {
    AUTOMATIC,
    MANUAL,
}

internal data class ScanControlState(
    val autoScanEnabled: Boolean,
    val manualRefreshInProgress: Boolean = false,
) {
    val selectedMode: ScanMode
        get() = if (autoScanEnabled) ScanMode.AUTOMATIC else ScanMode.MANUAL

    val autoScanSelected: Boolean
        get() = selectedMode == ScanMode.AUTOMATIC

    val manualRefreshSelected: Boolean
        get() = selectedMode == ScanMode.MANUAL

    val manualRefreshEnabled: Boolean
        get() = !manualRefreshInProgress
}
