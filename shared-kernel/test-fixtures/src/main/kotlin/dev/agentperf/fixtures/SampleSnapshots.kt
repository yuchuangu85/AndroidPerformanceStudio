package dev.agentperf.fixtures

import dev.agentperf.protocol.AgentCapabilities
import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.DisplayInfo
import dev.agentperf.protocol.LayoutSnapshot
import dev.agentperf.protocol.ProtocolVersion
import dev.agentperf.protocol.ViewNode

object SampleSnapshots {
    val dashboard = LayoutSnapshot(
        protocolVersion = ProtocolVersion(1, 0),
        packageName = "dev.agentperf.sample",
        capturedAtEpochMillis = 1_750_000_000_000,
        display = DisplayInfo(widthPx = 1080, heightPx = 2400, density = 3f),
        capabilities = AgentCapabilities(viewHierarchy = true, screenshots = false),
        root = ViewNode(
            id = "root",
            className = "android.widget.FrameLayout",
            resourceName = "content",
            bounds = Bounds(0, 0, 1080, 2400),
            children = listOf(
                ViewNode(
                    id = "title",
                    className = "android.widget.TextView",
                    resourceName = "title",
                    text = "Dashboard",
                    bounds = Bounds(48, 72, 600, 180),
                ),
                ViewNode(
                    id = "cards",
                    className = "android.widget.LinearLayout",
                    resourceName = "cards",
                    bounds = Bounds(32, 220, 1048, 1300),
                    children = listOf(
                        ViewNode(
                            id = "score",
                            className = "android.widget.TextView",
                            text = "Layout score: 82",
                            bounds = Bounds(64, 260, 680, 390),
                        ),
                        ViewNode(
                            id = "legacy-placeholder",
                            className = "android.view.ViewStub",
                            bounds = Bounds(0, 0, 0, 0),
                            visible = false,
                        ),
                    ),
                ),
            ),
        ),
    )
}
