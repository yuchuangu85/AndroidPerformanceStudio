package dev.agentperf.android.view

import dev.agentperf.protocol.AgentCapabilities
import dev.agentperf.protocol.DisplayInfo
import dev.agentperf.protocol.LayoutSnapshot
import dev.agentperf.protocol.ProtocolVersion
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
        protocolVersion = ProtocolVersion(major = 1, minor = 1),
        packageName = packageName,
        capturedAtEpochMillis = capturedAtEpochMillis,
        display = DisplayInfo(widthPx = widthPx, heightPx = heightPx, density = density),
        capabilities = AgentCapabilities(
            viewHierarchy = true,
            screenshots = true,
        ),
        root = windows.first { it.id == defaultWindowId }.root,
        windows = windows,
        defaultWindowId = defaultWindowId,
    )
}
