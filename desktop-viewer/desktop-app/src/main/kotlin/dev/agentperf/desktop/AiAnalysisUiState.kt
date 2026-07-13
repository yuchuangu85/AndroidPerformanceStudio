package dev.agentperf.desktop

internal sealed interface AiAnalysisUiState {
    data object Idle : AiAnalysisUiState
    data object Working : AiAnalysisUiState
    data class Failure(val message: String) : AiAnalysisUiState
}
