package com.androidperformancestudio.application

import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.CallStackTransform
import com.androidperformancestudio.profileanalysis.FlameCallNodeId

data class FlameGraphPanelState(
    val query: CallStackAnalysisQuery = CallStackAnalysisQuery(),
    val selectedNodeId: FlameCallNodeId? = null,
    val hoveredNodeId: FlameCallNodeId? = null,
    val contextNodeId: FlameCallNodeId? = null,
    val invalidTransforms: List<CallStackTransform> = emptyList(),
)
