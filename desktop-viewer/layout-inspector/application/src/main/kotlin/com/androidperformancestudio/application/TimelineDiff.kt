package com.androidperformancestudio.application

import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.ComposeNode
import com.androidperformancestudio.protocol.LayoutSnapshot
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.ViewNode
import com.androidperformancestudio.protocol.effectiveWindows
import java.util.ArrayDeque

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
    val previousNodes = previous.flattenNodesByAddress()
    val currentNodes = current.flattenNodesByAddress()
    val matches = matchNodes(previous, current)
    val matchedPrevious = matches.mapTo(mutableSetOf()) { it.previous.address }
    val matchedCurrent = matches.mapTo(mutableSetOf()) { it.current.address }

    val added = (currentNodes.keys - matchedCurrent).sortedBy(NodeAddress::key).map { address ->
        currentNodes.getValue(address).toChange(TimelineChangeType.ADDED)
    }
    val removed = (previousNodes.keys - matchedPrevious).sortedBy(NodeAddress::key).map { address ->
        previousNodes.getValue(address).toChange(TimelineChangeType.REMOVED)
    }
    val changed = matches.sortedBy { it.current.nodeKey }.mapNotNull { match ->
        val properties = match.previous.diffProperties(match.current)
        if (properties.isEmpty()) null else match.current.toChange(TimelineChangeType.CHANGED, properties)
    }

    return TimelineDiff(
        previousCapturedAtEpochMillis = previous.capturedAtEpochMillis,
        currentCapturedAtEpochMillis = current.capturedAtEpochMillis,
        addedNodes = added.size,
        removedNodes = removed.size,
        boundsChangedNodes = matches.count { it.previous.bounds != it.current.bounds },
        changes = added + removed + changed,
    )
}

private data class NodeAddress(
    val windowId: String,
    val nodeId: String,
) {
    val key: String get() = "$windowId:$nodeId"
}

private data class TimelineNodeFingerprint(
    val windowId: String,
    val nodeId: String,
    val className: String,
    val bounds: Bounds,
    val visible: Boolean,
    val alpha: Float,
    val text: String?,
    val resourceName: String?,
    val contentDescription: String?,
    val semanticsRole: String?,
    val semanticProperties: Map<String, String>,
    val node: UiNode,
) {
    val address: NodeAddress get() = NodeAddress(windowId, nodeId)
    val nodeKey: String get() = address.key
}

private data class NodeMatch(
    val previous: TimelineNodeFingerprint,
    val current: TimelineNodeFingerprint,
)

private data class IndexedNode(
    val index: Int,
    val node: UiNode,
)

private fun LayoutSnapshot.flattenNodesByAddress(): Map<NodeAddress, TimelineNodeFingerprint> = buildMap {
    effectiveWindows.forEach { window ->
        val pending = ArrayDeque<UiNode>().apply { add(window.root) }
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            val fingerprint = node.toFingerprint(window.id)
            put(fingerprint.address, fingerprint)
            node.children.forEach(pending::addLast)
        }
    }
}

private fun matchNodes(
    previous: LayoutSnapshot,
    current: LayoutSnapshot,
): List<NodeMatch> {
    val previousWindows = previous.effectiveWindows.associateBy { it.id }
    val currentWindows = current.effectiveWindows.associateBy { it.id }
    val pending = ArrayDeque<NodeMatch>()
    (previousWindows.keys intersect currentWindows.keys).sorted().forEach { windowId ->
        pending.addLast(
            NodeMatch(
                previous = previousWindows.getValue(windowId).root.toFingerprint(windowId),
                current = currentWindows.getValue(windowId).root.toFingerprint(windowId),
            ),
        )
    }

    val matches = mutableListOf<NodeMatch>()
    val matchedPrevious = mutableSetOf<NodeAddress>()
    val matchedCurrent = mutableSetOf<NodeAddress>()
    while (pending.isNotEmpty()) {
        val match = pending.removeFirst()
        if (!matchedPrevious.add(match.previous.address) || !matchedCurrent.add(match.current.address)) continue
        matches += match
        matchSiblingNodes(match.previous.node.children, match.current.node.children).forEach { (before, after) ->
            pending.addLast(
                NodeMatch(
                    previous = before.toFingerprint(match.previous.windowId),
                    current = after.toFingerprint(match.current.windowId),
                ),
            )
        }
    }
    return matches
}

private fun matchSiblingNodes(
    previous: List<UiNode>,
    current: List<UiNode>,
): List<Pair<UiNode, UiNode>> {
    val previousNodes = previous.mapIndexed(::IndexedNode)
    val currentNodes = current.mapIndexed(::IndexedNode)
    val matchedPrevious = mutableSetOf<Int>()
    val matchedCurrent = mutableSetOf<Int>()
    val matches = mutableListOf<Pair<IndexedNode, IndexedNode>>()

    fun matchUniqueBy(key: (UiNode) -> String?) {
        val previousByKey = previousNodes
            .filterNot { it.index in matchedPrevious }
            .mapNotNull { indexed -> key(indexed.node)?.let { it to indexed } }
            .groupBy({ it.first }, { it.second })
        val currentByKey = currentNodes
            .filterNot { it.index in matchedCurrent }
            .mapNotNull { indexed -> key(indexed.node)?.let { it to indexed } }
            .groupBy({ it.first }, { it.second })
        (previousByKey.keys intersect currentByKey.keys).sorted().forEach { identity ->
            val before = previousByKey.getValue(identity)
            val after = currentByKey.getValue(identity)
            if (before.size == 1 && after.size == 1) {
                val previousNode = before.single()
                val currentNode = after.single()
                matchedPrevious += previousNode.index
                matchedCurrent += currentNode.index
                matches += previousNode to currentNode
            }
        }
    }

    matchUniqueBy(UiNode::intrinsicIdentity)
    matchUniqueBy(UiNode::declaredIdentity)
    matchUniqueBy(UiNode::disambiguatedIdentity)

    val structureSupportsPositionMatching =
        previous.size == current.size && matches.all { (before, after) -> before.index == after.index }
    if (structureSupportsPositionMatching) {
        previousNodes.indices.forEach { index ->
            if (index !in matchedPrevious && index !in matchedCurrent) {
                val before = previousNodes[index]
                val after = currentNodes[index]
                if (before.node.hasCompatibleWeakIdentity(after.node)) {
                    matchedPrevious += index
                    matchedCurrent += index
                    matches += before to after
                }
            }
        }
    }

    return matches.sortedBy { it.first.index }.map { it.first.node to it.second.node }
}

private fun UiNode.intrinsicIdentity(): String? = when (this) {
    is ViewNode -> explicitNodeId()?.let { "view:node-id:$it" }
    is ComposeNode -> semanticsId()?.let { "compose:semantics-id:$it" }
        ?: explicitNodeId()?.let { "compose:node-id:$it" }
}

private fun UiNode.declaredIdentity(): String? = when (this) {
    is ViewNode -> resourceName?.takeIf(String::isNotBlank)?.let { "view:resource:$it" }
    is ComposeNode -> semanticProperties["TestTag"]
        ?.takeIf(String::isNotBlank)
        ?.let { "compose:test-tag:$it" }
}

private fun UiNode.disambiguatedIdentity(): String? {
    val base = declaredIdentity() ?: return null
    val auxiliaryValues = when (this) {
        is ViewNode -> listOfNotNull(text, attributes.contentDescription)
        is ComposeNode -> listOfNotNull(text, semanticProperties["ContentDescription"])
    }.filter(String::isNotBlank)
    if (auxiliaryValues.isEmpty()) return null
    return buildString {
        append(base)
        append("|class:")
        append(className)
        auxiliaryValues.forEach {
            append("|value:")
            append(it)
        }
    }
}

private fun ComposeNode.semanticsId(): String? =
    id.substringAfterLast('/').takeIf { value -> value.isNotEmpty() && value.all(Char::isDigit) }

private fun UiNode.explicitNodeId(): String? = id.takeIf { value ->
    val finalSegment = value.substringAfterLast('/')
    finalSegment.isNotBlank() &&
        finalSegment != "root" &&
        finalSegment.any { !it.isDigit() }
}

private fun UiNode.hasCompatibleWeakIdentity(current: UiNode): Boolean {
    if (id != current.id || className != current.className) return false
    return when {
        this is ViewNode && current is ViewNode -> resourceName == current.resourceName
        this is ComposeNode && current is ComposeNode ->
            semanticProperties["TestTag"] == current.semanticProperties["TestTag"]
        else -> false
    }
}

private fun UiNode.toFingerprint(windowId: String): TimelineNodeFingerprint = TimelineNodeFingerprint(
    windowId = windowId,
    nodeId = id,
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
    node = this,
)

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
