package dev.agentperf.android.view

import dev.agentperf.protocol.AgentCapabilities
import dev.agentperf.protocol.DisplayInfo
import dev.agentperf.protocol.LayoutSnapshot
import dev.agentperf.protocol.ProtocolVersion
import dev.agentperf.protocol.UiNode

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
        root = root,
    )
}
