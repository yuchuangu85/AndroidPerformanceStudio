package com.androidperformancestudio.application

import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.StackChartBlockId
import com.androidperformancestudio.storage.ProfileMarkerId

enum class ReportTab {
    OVERVIEW,
    TOP_FUNCTIONS,
    CALL_TREE,
    FLAME_GRAPH,
    STACK_CHART,
    MARKER_CHART,
    MARKER_TABLE,
}

data class ReportWorkspaceUiState(
    val detailsVisible: Boolean = true,
    val timelineHeightDp: Int = 220,
    val markerSearchText: String = "",
    val selections: ReportPanelSelections = ReportPanelSelections(),
)

data class ReportPanelSelections(
    val overviewFindingRuleId: String? = null,
    val topFunctionKey: String? = null,
    val callNodeId: FlameCallNodeId? = null,
    val stackChartBlockId: StackChartBlockId? = null,
    val markerId: ProfileMarkerId? = null,
)
