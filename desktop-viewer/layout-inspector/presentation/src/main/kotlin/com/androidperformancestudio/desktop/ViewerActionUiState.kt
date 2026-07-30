package com.androidperformancestudio.desktop

internal data class ViewerActionUiState(
    val enabled: Boolean,
    val checked: Boolean,
)

internal fun viewerActionUiState(
    action: ViewerAction,
    selectedNodeId: String?,
    autoScanEnabled: Boolean,
    panelVisibility: PanelVisibility,
    viewDisplayOptions: ViewDisplayOptions = ViewDisplayOptions(),
): ViewerActionUiState {
    val treeAction = action == ViewerAction.PREVIOUS_NODE ||
        action == ViewerAction.NEXT_NODE ||
        action == ViewerAction.TOGGLE_SELECTED_NODE
    val checked = when (action) {
        ViewerAction.TOGGLE_AUTO_SCAN -> autoScanEnabled
        ViewerAction.TOGGLE_HIERARCHY -> !panelVisibility.showHierarchy
        ViewerAction.TOGGLE_FINDINGS -> !panelVisibility.showFindings
        ViewerAction.TOGGLE_DETAILS -> !panelVisibility.showDetails
        ViewerAction.TOGGLE_HIERARCHY_IDS -> viewDisplayOptions.showHierarchyIds
        else -> false
    }
    return ViewerActionUiState(
        enabled = !treeAction || selectedNodeId != null,
        checked = checked,
    )
}
