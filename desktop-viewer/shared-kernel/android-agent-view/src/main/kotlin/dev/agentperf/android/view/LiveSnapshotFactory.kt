package dev.agentperf.android.view

import dev.agentperf.protocol.AgentCapabilities
import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.ComposeNode
import dev.agentperf.protocol.DisplayInfo
import dev.agentperf.protocol.LayoutSnapshot
import dev.agentperf.protocol.ProtocolVersion
import dev.agentperf.protocol.UiNode
import dev.agentperf.protocol.ViewNode

object LiveSnapshotFactory {
    fun create(
        packageName: String,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        capturedAtEpochMillis: Long,
        root: UiNode,
    ) = LayoutSnapshot(
        protocolVersion = ProtocolVersion(major = 1, minor = 0),
        packageName = packageName,
        capturedAtEpochMillis = capturedAtEpochMillis,
        display = DisplayInfo(widthPx = widthPx, heightPx = heightPx, density = density),
        capabilities = AgentCapabilities(
            viewHierarchy = true,
            screenshots = true,
        ),
        root = root.translate(
            deltaX = -root.bounds.left,
            deltaY = -root.bounds.top,
        ),
    )

    private fun UiNode.translate(deltaX: Int, deltaY: Int): UiNode {
        val translatedBounds = Bounds(
            left = bounds.left + deltaX,
            top = bounds.top + deltaY,
            right = bounds.right + deltaX,
            bottom = bounds.bottom + deltaY,
        )
        val translatedChildren = children.map { it.translate(deltaX, deltaY) }
        return when (this) {
            is ViewNode -> copy(bounds = translatedBounds, children = translatedChildren)
            is ComposeNode -> copy(bounds = translatedBounds, children = translatedChildren)
        }
    }
}
