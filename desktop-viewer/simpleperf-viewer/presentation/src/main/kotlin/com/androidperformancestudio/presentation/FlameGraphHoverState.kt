package com.androidperformancestudio.presentation

import com.androidperformancestudio.profileanalysis.FlameCallNodeId

internal class FlameGraphHoverState private constructor(
    private val layoutToken: Any?,
    private val nodeId: FlameCallNodeId?,
) {
    constructor() : this(null, null)

    fun update(
        layoutToken: Any,
        nodeId: FlameCallNodeId?,
    ): FlameGraphHoverState = FlameGraphHoverState(layoutToken, nodeId)

    fun nodeIdFor(layoutToken: Any): FlameCallNodeId? = nodeId.takeIf { this.layoutToken === layoutToken }
}
