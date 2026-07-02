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
)
