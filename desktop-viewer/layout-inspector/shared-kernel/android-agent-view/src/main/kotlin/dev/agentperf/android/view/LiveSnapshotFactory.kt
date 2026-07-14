package dev.agentperf.android.view

import dev.agentperf.protocol.AgentCapabilities
import dev.agentperf.protocol.ComposeNode
import dev.agentperf.protocol.DisplayInfo
import dev.agentperf.protocol.LayoutSnapshot
import dev.agentperf.protocol.CURRENT_PROTOCOL_VERSION
import dev.agentperf.protocol.UiNode
import dev.agentperf.protocol.WindowSnapshot

object LiveSnapshotFactory {
    fun create(
        packageName: String,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        capturedAtEpochMillis: Long,
        windows: List<WindowSnapshot>,
        defaultWindowId: String,
    ) = LayoutSnapshot(
        protocolVersion = CURRENT_PROTOCOL_VERSION,
        packageName = packageName,
        capturedAtEpochMillis = capturedAtEpochMillis,
        display = DisplayInfo(widthPx = widthPx, heightPx = heightPx, density = density),
        capabilities = AgentCapabilities(
            viewHierarchy = true,
            composeSemantics = windows.any { it.root.containsComposeNode() },
            screenshots = true,
        ),
        root = windows.first { it.id == defaultWindowId }.root,
        windows = windows,
        defaultWindowId = defaultWindowId,
    )
}


private fun UiNode.containsComposeNode(): Boolean =
    this is ComposeNode || children.any { it.containsComposeNode() }
