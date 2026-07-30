package com.androidperformancestudio.desktop

internal data class PanelVisibility(
    val showHierarchy: Boolean = true,
    val showFindings: Boolean = true,
    val showDetails: Boolean = true,
) {
    fun toggleHierarchy(): PanelVisibility = copy(showHierarchy = !showHierarchy)

    fun toggleFindings(): PanelVisibility = copy(showFindings = !showFindings)

    fun toggleDetails(): PanelVisibility = copy(showDetails = !showDetails)
}
