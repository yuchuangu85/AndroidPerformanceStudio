package dev.agentperf.desktop

import androidx.compose.ui.geometry.Offset
import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.UiNode
import dev.agentperf.protocol.ViewNode
import kotlin.math.max
import kotlin.math.min

internal object CanvasHitTester {
    fun hitPath(root: UiNode, point: Offset): List<String> =
        hit(root, point, root.bounds, ancestorsVisible = true, ancestorAlpha = 1f).orEmpty()

    private fun hit(
        node: UiNode,
        point: Offset,
        inheritedClip: Bounds,
        ancestorsVisible: Boolean,
        ancestorAlpha: Float,
    ): List<String>? {
        val visible = ancestorsVisible && node.visible
        val alpha = ancestorAlpha * node.alpha
        if (!visible || alpha <= 0f || node.bounds.width <= 0 || node.bounds.height <= 0) return null
        val visibleBounds = node.bounds.intersect(inheritedClip) ?: return null
        if (!visibleBounds.contains(point)) return null

        val attributes = (node as? ViewNode)?.attributes
        var childClip = inheritedClip
        if (attributes?.clipChildren == true) {
            childClip = childClip.intersect(node.bounds) ?: return listOf(node.id)
        }
        attributes?.clipBounds?.let { local ->
            val screenClip = Bounds(
                node.bounds.left + local.left,
                node.bounds.top + local.top,
                node.bounds.left + local.right,
                node.bounds.top + local.bottom,
            )
            childClip = childClip.intersect(screenClip) ?: return listOf(node.id)
        }

        val childHit = node.children.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<UiNode>> { it.value.paintZ() }
                    .thenByDescending { it.index },
            )
            .firstNotNullOfOrNull { (_, child) ->
                hit(child, point, childClip, visible, alpha)
            }
        return childHit?.plus(node.id) ?: listOf(node.id)
    }

    private fun UiNode.paintZ(): Float {
        val attributes = (this as? ViewNode)?.attributes
        return attributes?.z ?: attributes?.elevation ?: 0f
    }

    private fun Bounds.contains(point: Offset): Boolean =
        point.x >= left && point.x <= right && point.y >= top && point.y <= bottom

    private fun Bounds.intersect(other: Bounds): Bounds? {
        val intersection = Bounds(
            left = max(left, other.left),
            top = max(top, other.top),
            right = min(right, other.right),
            bottom = min(bottom, other.bottom),
        )
        return intersection.takeIf { it.width > 0 && it.height > 0 }
    }
}
