package com.androidperformancestudio.compose.inspection.host

import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol
import com.androidperformancestudio.protocol.AgentCapabilities
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.CURRENT_PROTOCOL_VERSION
import com.androidperformancestudio.protocol.DisplayInfo
import com.androidperformancestudio.protocol.LayoutSnapshot
import com.androidperformancestudio.protocol.ViewAttributes
import com.androidperformancestudio.protocol.ViewNode
import com.androidperformancestudio.protocol.WindowSnapshot
import com.androidperformancestudio.protocol.WindowType

data class ViewInspectionCapture(
    val snapshot: LayoutSnapshot,
    val rootViewIds: List<Long>,
)

class ViewProtocolAdapter {
    fun convert(
        packageName: String,
        capturedAtEpochMillis: Long,
        response: ViewInspectorProtocol.DumpViewsResponse,
    ): ViewInspectionCapture {
        val strings = response.stringsList.associate { it.id to it.value }
        val roots = response.nodesList.map { it.convert(strings) }
        require(roots.isNotEmpty()) { "View inspector returned no active roots" }
        val display = response.appContext.displayInfoList.firstOrNull()
        val density = response.configuration.density.takeIf { it > 0 }?.div(160f) ?: 1f
        val windows = roots.mapIndexed { index, root ->
            WindowSnapshot(
                id = "window:view:${response.nodesList[index].id}",
                title = root.className.substringAfterLast('.'),
                type = WindowType.OTHER,
                bounds = root.bounds,
                root = root,
            )
        }
        return ViewInspectionCapture(
            snapshot = LayoutSnapshot(
                protocolVersion = CURRENT_PROTOCOL_VERSION,
                packageName = packageName,
                capturedAtEpochMillis = capturedAtEpochMillis,
                display = DisplayInfo(
                    widthPx = display?.widthPx ?: roots.maxOf { it.bounds.right }.coerceAtLeast(1),
                    heightPx = display?.heightPx ?: roots.maxOf { it.bounds.bottom }.coerceAtLeast(1),
                    density = density,
                ),
                capabilities = AgentCapabilities(viewHierarchy = true),
                root = roots.first(),
                windows = windows,
                defaultWindowId = windows.first().id,
            ),
            rootViewIds = response.nodesList.map { it.id },
        )
    }

    private fun ViewInspectorProtocol.ViewNode.convert(strings: Map<Int, String>): ViewNode = ViewNode(
        id = "view:$id",
        className = strings[className] ?: "android.view.View",
        bounds = Bounds(
            left = bounds.x,
            top = bounds.y,
            right = (bounds.x.toLong() + bounds.width).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
            bottom = (bounds.y.toLong() + bounds.height).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
        ),
        children = childrenList.map { it.convert(strings) },
        resourceName = strings[idResource],
        attributes = ViewAttributes(
            rawProperties = attributesList.associate { attribute ->
                (strings[attribute.name] ?: "unknown") to attribute.render(strings)
            },
        ),
    )

    private fun ViewInspectorProtocol.ViewNode.Attribute.render(strings: Map<Int, String>): String = when (type) {
        ViewInspectorProtocol.ViewNode.Attribute.Type.STRING,
        ViewInspectorProtocol.ViewNode.Attribute.Type.OBJECT,
        ViewInspectorProtocol.ViewNode.Attribute.Type.RESOURCE,
        ViewInspectorProtocol.ViewNode.Attribute.Type.DRAWABLE,
        -> strings[int32Value].orEmpty()
        ViewInspectorProtocol.ViewNode.Attribute.Type.BOOLEAN -> (int32Value != 0).toString()
        ViewInspectorProtocol.ViewNode.Attribute.Type.INT64 -> int64Value.toString()
        ViewInspectorProtocol.ViewNode.Attribute.Type.DOUBLE -> doubleValue.toString()
        ViewInspectorProtocol.ViewNode.Attribute.Type.FLOAT,
        ViewInspectorProtocol.ViewNode.Attribute.Type.DIMENSION,
        -> floatValue.toString()
        else -> int32Value.toString()
    }.take(MAX_ATTRIBUTE_CHARS)

    private companion object {
        const val MAX_ATTRIBUTE_CHARS = 64 * 1024
    }
}
