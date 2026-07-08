package dev.agentperf.application

import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.ComposeNode
import dev.agentperf.protocol.LayoutSnapshot
import dev.agentperf.protocol.UiNode
import dev.agentperf.protocol.ViewNode
import dev.agentperf.protocol.effectiveWindows

enum class TimelineChangeType {
    ADDED,
    REMOVED,
    CHANGED,
}

data class TimelineNodeChange(
    val type: TimelineChangeType,
    val windowId: String,
    val nodeId: String,
    val nodeKey: String,
    val className: String,
    val changedProperties: List<String> = emptyList(),
)

data class TimelineDiff(
    val previousCapturedAtEpochMillis: Long,
    val currentCapturedAtEpochMillis: Long,
    val addedNodes: Int,
    val removedNodes: Int,
    val boundsChangedNodes: Int,
    val changes: List<TimelineNodeChange> = emptyList(),
)

fun diffSnapshots(
    previous: LayoutSnapshot,
    current: LayoutSnapshot,
): TimelineDiff {
    val previousNodes = previous.flattenNodesByKey()
    val currentNodes = current.flattenNodesByKey()
    val previousKeys = previousNodes.keys
    val currentKeys = currentNodes.keys
    val sharedKeys = previousKeys intersect currentKeys
    val added = (currentKeys - previousKeys).sorted().map { key ->
        currentNodes.getValue(key).toChange(TimelineChangeType.ADDED)
    }
    val removed = (previousKeys - currentKeys).sorted().map { key ->
        previousNodes.getValue(key).toChange(TimelineChangeType.REMOVED)
    }
    val changed = sharedKeys.sorted().mapNotNull { key ->
        val before = previousNodes.getValue(key)
        val after = currentNodes.getValue(key)
        val properties = before.diffProperties(after)
        if (properties.isEmpty()) null else after.toChange(TimelineChangeType.CHANGED, properties)
    }
    return TimelineDiff(
        previousCapturedAtEpochMillis = previous.capturedAtEpochMillis,
        currentCapturedAtEpochMillis = current.capturedAtEpochMillis,
        addedNodes = added.size,
        removedNodes = removed.size,
        boundsChangedNodes = sharedKeys.count { key -> previousNodes.getValue(key).bounds != currentNodes.getValue(key).bounds },
        changes = added + removed + changed,
    )
}

private data class TimelineNodeFingerprint(
    val windowId: String,
    val nodeId: String,
    val nodeKey: String,
    val className: String,
    val bounds: Bounds,
    val visible: Boolean,
    val alpha: Float,
    val text: String?,
    val resourceName: String?,
    val contentDescription: String?,
    val semanticsRole: String?,
    val semanticProperties: Map<String, String>,
)

private fun LayoutSnapshot.flattenNodesByKey(): Map<String, TimelineNodeFingerprint> = buildMap {
    effectiveWindows.forEach { window ->
        window.root.appendFingerprint(window.id, this)
    }
}

private fun UiNode.appendFingerprint(
    windowId: String,
    target: MutableMap<String, TimelineNodeFingerprint>,
) {
    val key = "$windowId:$id"
    target[key] = TimelineNodeFingerprint(
        windowId = windowId,
        nodeId = id,
        nodeKey = key,
        className = className,
        bounds = bounds,
        visible = visible,
        alpha = alpha,
        text = when (this) {
            is ViewNode -> text
            is ComposeNode -> text
        },
        resourceName = (this as? ViewNode)?.resourceName,
        contentDescription = (this as? ViewNode)?.attributes?.contentDescription,
        semanticsRole = (this as? ComposeNode)?.semanticsRole,
        semanticProperties = (this as? ComposeNode)?.semanticProperties.orEmpty(),
    )
    children.forEach { child -> child.appendFingerprint(windowId, target) }
}

private fun TimelineNodeFingerprint.diffProperties(
    current: TimelineNodeFingerprint,
): List<String> = buildList {
    if (bounds != current.bounds) add("bounds")
    if (className != current.className) add("className")
    if (visible != current.visible) add("visible")
    if (alpha != current.alpha) add("alpha")
    if (text != current.text) add("text")
    if (resourceName != current.resourceName) add("resourceName")
    if (contentDescription != current.contentDescription) add("contentDescription")
    if (semanticsRole != current.semanticsRole) add("semanticsRole")
    if (semanticProperties != current.semanticProperties) add("semanticProperties")
}.sorted()

private fun TimelineNodeFingerprint.toChange(
    type: TimelineChangeType,
    changedProperties: List<String> = emptyList(),
): TimelineNodeChange = TimelineNodeChange(
    type = type,
    windowId = windowId,
    nodeId = nodeId,
    nodeKey = nodeKey,
    className = className,
    changedProperties = changedProperties,
)
