package com.androidperformancestudio.compose.inspection.host

import com.androidperformancestudio.compose.inspection.ComposableNode
import com.androidperformancestudio.compose.inspection.ComposeInspectionFrame
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.ViewNode
import com.androidperformancestudio.protocol.effectiveWindows

internal interface ComposeFrameCaptureClient {
    fun captureViews(packageName: String, includeAttributes: Boolean): ViewInspectionCapture

    fun captureTree(rootViewIds: List<Long>, includeDetails: Boolean = true): ComposeInspectionFrame
}

internal data class StableComposeCapture(
    val views: ViewInspectionCapture,
    val compose: ComposeInspectionFrame,
)

/**
 * Obtains a frame using a bounded double-collect protocol.
 *
 * View Inspector and Compose Inspector do not expose a shared transaction or frame token. A
 * single View -> Compose sequence can therefore merge two different UI states. Collecting
 * View-A/Compose-A/View-B/Compose-B/View-C and requiring both structural reads to agree proves
 * that the returned middle view and second Compose tree were surrounded by the same stable state.
 *
 * A [ComposableRoot.viewId] is the id of the view a Compose tree hangs under (the
 * `AndroidComposeView`), not the window root that `GetComposablesCommand` is queried with, so it
 * is validated against the captured view tree instead of being compared with the requested roots.
 */
internal class StableComposeFrameCapture(
    private val client: ComposeFrameCaptureClient,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    fun capture(packageName: String): StableComposeCapture {
        var reason = "no attempt was made"
        var composeUnavailable = false
        repeat(maxAttempts) { attempt ->
            val viewA = client.captureViews(packageName, includeAttributes = false)
            // Only the returned read needs parameters. Keeping two full parameter graphs alive
            // while checking stability multiplies both device work and the desktop heap peak.
            val composeA = client.captureTree(viewA.rootViewIds, includeDetails = false)
            val viewB = client.captureViews(packageName, includeAttributes = false)
            val composeB = client.captureTree(viewB.rootViewIds)
            val viewC = client.captureViews(packageName, includeAttributes = false)

            val stableViews = viewA.structuralSnapshot() == viewB.structuralSnapshot() &&
                viewB.structuralSnapshot() == viewC.structuralSnapshot()
            val stableCompose = composeA.hasSameStructure(composeB)
            val hostViews = viewB.hostViews()
            val hostViewIds = hostViews.mapNotNull { it.numericViewId() }.toSet()
            val anchoredRoots = composeB.roots.all { root -> root.viewId in hostViewIds }
            val hasComposeNodes = composeA.roots.any { it.nodes.isNotEmpty() } &&
                composeB.roots.any { it.nodes.isNotEmpty() }
            val populatedRootIds = composeB.roots.filter { it.nodes.isNotEmpty() }.map { it.viewId }.toSet()
            val missingHostIds = hostViews
                .filter { it.className.substringAfterLast('.') == "AndroidComposeView" }
                .mapNotNull { it.numericViewId() }
                .toSet() - populatedRootIds
            if (stableViews && stableCompose && anchoredRoots && hasComposeNodes && missingHostIds.isEmpty()) {
                return StableComposeCapture(viewB, composeB)
            }
            composeUnavailable = !hasComposeNodes || (anchoredRoots && missingHostIds.isNotEmpty())
            reason =
                when {
                    !hasComposeNodes -> "the Compose inspector returned no Composable nodes in one or both tree reads"
                    !stableViews -> "view hierarchy kept changing"
                    !stableCompose -> "compose tree kept changing"
                    !anchoredRoots ->
                        "compose roots " + composeB.roots.map { it.viewId } +
                            " are not views of the captured window"
                    else -> "AndroidComposeView hosts " + missingHostIds + " have no populated Compose roots"
                } + " (attempt " + (attempt + 1) + " of " + maxAttempts + ")"
        }
        if (composeUnavailable) {
            error(
                "Compose tree unavailable; keep the target Compose screen visible and retry capture. " +
                    "If it persists, verify the target supports Compose inspection. Last attempt: " + reason,
            )
        }
        error("Target changed during Compose frame capture; try again. Last attempt: " + reason)
    }

    private fun ViewInspectionCapture.structuralSnapshot() = snapshot.copy(capturedAtEpochMillis = 0)

    private fun ViewInspectionCapture.hostViews(): List<ViewNode> {
        val collected = mutableListOf<ViewNode>()
        val pending = ArrayDeque<UiNode>()
        pending.addAll(snapshot.effectiveWindows.map { it.root })
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node is ViewNode) collected.add(node)
            pending.addAll(node.children)
        }
        return collected
    }

    private fun ViewNode.numericViewId(): Long? = id.removePrefix("view:").toLongOrNull()

    private fun ComposeInspectionFrame.hasSameStructure(other: ComposeInspectionFrame): Boolean {
        if (roots.size != other.roots.size) return false
        val pending = ArrayDeque<Pair<ComposableNode, ComposableNode>>()
        roots.zip(other.roots).forEach { (left, right) ->
            if (left.viewId != right.viewId || left.viewsToSkip != right.viewsToSkip ||
                left.nodes.size != right.nodes.size
            ) return false
            left.nodes.indices.forEach { pending.addLast(left.nodes[it] to right.nodes[it]) }
        }
        while (pending.isNotEmpty()) {
            val (left, right) = pending.removeLast()
            if (left.id != right.id || left.anchorHash != right.anchorHash ||
                left.name != right.name || left.bounds != right.bounds ||
                left.hostedViewId != right.hostedViewId || left.source != right.source ||
                left.systemCreated != right.systemCreated || left.flags != right.flags ||
                left.children.size != right.children.size
            ) return false
            left.children.indices.forEach { pending.addLast(left.children[it] to right.children[it]) }
        }
        return true
    }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}
