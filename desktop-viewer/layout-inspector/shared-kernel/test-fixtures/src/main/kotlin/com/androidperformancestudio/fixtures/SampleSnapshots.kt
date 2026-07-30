package com.androidperformancestudio.fixtures

import com.androidperformancestudio.protocol.AgentCapabilities
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.DisplayInfo
import com.androidperformancestudio.protocol.LayoutSnapshot
import com.androidperformancestudio.protocol.ProtocolVersion
import com.androidperformancestudio.protocol.ViewNode

object SampleSnapshots {
    val dashboard = LayoutSnapshot(
        protocolVersion = ProtocolVersion(1, 0),
        packageName = "com.androidperformancestudio.sample",
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
