package com.androidperformancestudio.android.view

import com.androidperformancestudio.protocol.AgentCapabilities
import com.androidperformancestudio.protocol.ComposeNode
import com.androidperformancestudio.protocol.DisplayInfo
import com.androidperformancestudio.protocol.LayoutSnapshot
import com.androidperformancestudio.protocol.CURRENT_PROTOCOL_VERSION
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.WindowSnapshot

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
