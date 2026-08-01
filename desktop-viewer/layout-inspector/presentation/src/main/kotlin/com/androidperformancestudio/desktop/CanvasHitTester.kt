package com.androidperformancestudio.desktop

import androidx.compose.ui.geometry.Offset
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.ViewNode
import kotlin.math.max
import kotlin.math.min

internal enum class CanvasHitTestOrder {
    SMALL_AREA_FIRST,
    Z_ORDER,
}

internal object CanvasHitTester {
    fun hitPath(root: UiNode, point: Offset): List<String> =
        hit(root, point, root.bounds, ancestorsVisible = true, ancestorAlpha = 1f).orEmpty()

    fun hitCandidates(
        root: UiNode,
        point: Offset,
        hiddenNodeIds: Set<String> = emptySet(),
        order: CanvasHitTestOrder = CanvasHitTestOrder.SMALL_AREA_FIRST,
    ): List<String> {
        val zOrdered = buildList<HitCandidate> {
            collectHits(
                node = root,
                point = point,
                inheritedClip = root.bounds,
                ancestorsVisible = true,
                ancestorAlpha = 1f,
                hiddenNodeIds = hiddenNodeIds,
                target = this,
            )
        }
        return when (order) {
            CanvasHitTestOrder.Z_ORDER -> zOrdered.map { it.id }
            CanvasHitTestOrder.SMALL_AREA_FIRST -> zOrdered
                .withIndex()
                .sortedWith(
                    compareBy<IndexedValue<HitCandidate>> { it.value.area }
                        .thenBy { it.index },
                )
                .map { it.value.id }
        }
    }

    private fun collectHits(
        node: UiNode,
        point: Offset,
        inheritedClip: Bounds,
        ancestorsVisible: Boolean,
        ancestorAlpha: Float,
        hiddenNodeIds: Set<String>,
        target: MutableList<HitCandidate>,
    ) {
        if (node.id in hiddenNodeIds) return
        val visible = ancestorsVisible && node.visible
        val alpha = ancestorAlpha * node.alpha
        if (!visible || alpha <= 0f || node.bounds.width <= 0 || node.bounds.height <= 0) return
        val visibleBounds = node.bounds.intersect(inheritedClip) ?: return
        if (!visibleBounds.contains(point)) return

        val attributes = (node as? ViewNode)?.attributes
        var childClip = inheritedClip
        if (attributes?.clipChildren == true) {
            childClip = childClip.intersect(node.bounds) ?: run {
                target += node.toHitCandidate()
                return
            }
        }
        attributes?.clipBounds?.let { local ->
            val screenClip = local.offsetBy(node.bounds.left, node.bounds.top)
            childClip = childClip.intersect(screenClip) ?: run {
                target += node.toHitCandidate()
                return
            }
        }

        node.children.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<UiNode>> { it.value.paintZ() }
                    .thenByDescending { it.index },
            )
            .forEach { (_, child) ->
                collectHits(child, point, childClip, visible, alpha, hiddenNodeIds, target)
            }
        target += node.toHitCandidate()
    }

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
            val screenClip = local.offsetBy(node.bounds.left, node.bounds.top)
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

    private data class HitCandidate(
        val id: String,
        val area: Long,
    )

    private fun UiNode.toHitCandidate(): HitCandidate = HitCandidate(id, bounds.area)

    private fun UiNode.paintZ(): Float {
        val attributes = (this as? ViewNode)?.attributes
        return attributes?.z ?: attributes?.elevation ?: 0f
    }

    private val Bounds.area: Long
        get() = width.toLong() * height.toLong()

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

    private fun Bounds.offsetBy(dx: Int, dy: Int): Bounds = Bounds(
        left = left.saturatingAdd(dx),
        top = top.saturatingAdd(dy),
        right = right.saturatingAdd(dx),
        bottom = bottom.saturatingAdd(dy),
    )

    private fun Int.saturatingAdd(other: Int): Int =
        (toLong() + other.toLong()).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}
