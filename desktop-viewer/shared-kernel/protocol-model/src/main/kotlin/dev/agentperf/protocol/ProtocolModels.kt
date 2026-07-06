package dev.agentperf.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProtocolVersion(
    val major: Int,
    val minor: Int,
)

@Serializable
data class AgentCapabilities(
    val viewHierarchy: Boolean = false,
    val composeSemantics: Boolean = false,
    val screenshots: Boolean = false,
    val timeline: Boolean = false,
)

@Serializable
data class DisplayInfo(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
)

@Serializable
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

@Serializable
data class EdgeInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

@Serializable
data class ViewAttributes(
    val visibility: String? = null,
    val elevation: Float? = null,
    val z: Float? = null,
    val translationX: Float? = null,
    val translationY: Float? = null,
    val translationZ: Float? = null,
    val rotation: Float? = null,
    val rotationX: Float? = null,
    val rotationY: Float? = null,
    val scaleX: Float? = null,
    val scaleY: Float? = null,
    val pivotX: Float? = null,
    val pivotY: Float? = null,
    val padding: EdgeInsets? = null,
    val margin: EdgeInsets? = null,
    val layoutWidth: Int? = null,
    val layoutHeight: Int? = null,
    val measuredWidth: Int? = null,
    val measuredHeight: Int? = null,
    val minWidth: Int? = null,
    val minHeight: Int? = null,
    val scrollX: Int? = null,
    val scrollY: Int? = null,
    val clipBounds: Bounds? = null,
    val clipChildren: Boolean? = null,
    val clipToPadding: Boolean? = null,
    val background: String? = null,
    val backgroundColor: String? = null,
    val foreground: String? = null,
    val opaque: Boolean? = null,
    val willNotDraw: Boolean? = null,
    val hardwareAccelerated: Boolean? = null,
    val layerType: String? = null,
    val layoutRequested: Boolean? = null,
    val enabled: Boolean? = null,
    val clickable: Boolean? = null,
    val longClickable: Boolean? = null,
    val focusable: Boolean? = null,
    val focused: Boolean? = null,
    val selected: Boolean? = null,
    val contentDescription: String? = null,
)

@Serializable
sealed interface UiNode {
    val id: String
    val className: String
    val bounds: Bounds
    val visible: Boolean
    val alpha: Float
    val children: List<UiNode>
}

@Serializable
@SerialName("view")
data class ViewNode(
    override val id: String,
    override val className: String,
    override val bounds: Bounds,
    override val visible: Boolean = true,
    override val alpha: Float = 1f,
    override val children: List<UiNode> = emptyList(),
    val resourceName: String? = null,
    val text: String? = null,
    val attributes: ViewAttributes = ViewAttributes(),
) : UiNode

@Serializable
@SerialName("compose")
data class ComposeNode(
    override val id: String,
    override val className: String,
    override val bounds: Bounds,
    override val visible: Boolean = true,
    override val alpha: Float = 1f,
    override val children: List<UiNode> = emptyList(),
    val semanticsRole: String? = null,
    val text: String? = null,
) : UiNode

@Serializable
data class LayoutSnapshot(
    val protocolVersion: ProtocolVersion,
    val packageName: String,
    val capturedAtEpochMillis: Long,
    val display: DisplayInfo,
    val capabilities: AgentCapabilities,
    val root: UiNode,
    val windows: List<WindowSnapshot> = emptyList(),
    val defaultWindowId: String? = null,
)

@Serializable
enum class WindowType {
    ACTIVITY,
    DIALOG,
    POPUP,
    OTHER,
}

@Serializable
data class WindowSnapshot(
    val id: String,
    val title: String,
    val type: WindowType = WindowType.OTHER,
    val bounds: Bounds,
    val root: UiNode,
)

const val LEGACY_WINDOW_ID = "window:legacy"

val LayoutSnapshot.effectiveWindows: List<WindowSnapshot>
    get() = windows.ifEmpty {
        listOf(
            WindowSnapshot(
                id = LEGACY_WINDOW_ID,
                title = packageName.substringAfterLast('.'),
                bounds = root.bounds,
                root = root,
            ),
        )
    }

val LayoutSnapshot.effectiveDefaultWindowId: String
    get() = defaultWindowId
        ?.takeIf { candidate -> effectiveWindows.any { it.id == candidate } }
        ?: effectiveWindows.first().id
