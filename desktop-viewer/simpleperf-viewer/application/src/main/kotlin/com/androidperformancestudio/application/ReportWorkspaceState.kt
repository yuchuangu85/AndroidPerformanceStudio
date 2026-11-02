package com.androidperformancestudio.application

import com.androidperformancestudio.profileanalysis.CallStackDirection
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
    val stackChartSameWidths: Boolean = false,
    val stackChartShowUserTimings: Boolean = false,
    val invertCallTree: Boolean = false,
    val invertFlameGraph: Boolean = false,
    val selections: ReportPanelSelections = ReportPanelSelections(),
) {
    fun callStackDirection(tab: ReportTab): CallStackDirection {
        val inverted =
            when (tab) {
                ReportTab.CALL_TREE -> invertCallTree
                ReportTab.FLAME_GRAPH -> invertFlameGraph
                else -> false
            }
        return if (inverted) CallStackDirection.INVERTED else CallStackDirection.FORWARD
    }
}

data class ReportPanelSelections(
    val overviewFindingRuleId: String? = null,
    val topFunctionKey: String? = null,
    val callNodeId: FlameCallNodeId? = null,
    val stackChartBlockId: StackChartBlockId? = null,
    val markerId: ProfileMarkerId? = null,
)
