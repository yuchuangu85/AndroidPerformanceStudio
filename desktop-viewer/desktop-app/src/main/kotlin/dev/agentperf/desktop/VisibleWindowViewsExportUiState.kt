package dev.agentperf.desktop

import java.nio.file.Path

internal sealed interface VisibleWindowViewsExportUiState {
    data object Idle : VisibleWindowViewsExportUiState
    data object Exporting : VisibleWindowViewsExportUiState
    data class Success(val directory: Path) : VisibleWindowViewsExportUiState
    data class Failure(val message: String) : VisibleWindowViewsExportUiState
}
