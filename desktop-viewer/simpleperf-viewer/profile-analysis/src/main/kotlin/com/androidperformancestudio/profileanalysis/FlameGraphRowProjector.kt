package com.androidperformancestudio.profileanalysis

import kotlin.math.max
import kotlin.math.min

object FlameGraphRowProjector {
    fun project(
        callNodes: CallNodeTable,
        direction: CallStackDirection = CallStackDirection.FORWARD,
    ): FlameGraphRows {
        val startsAtBottom = direction == CallStackDirection.FORWARD
        val state =
            RowProjectionState(
                parentIndexes = callNodes.parentIndexes,
                depths = callNodes.depths,
                weights = callNodes.inclusiveWeights,
            )
        if (callNodes.size > 0) {
            layoutVisibleRows(state)
        }

        return FlameGraphRows(
            state.rows.map { row -> row.toIntArray() },
            state.starts,
            state.ends,
            startsAtBottom,
        )
    }
}

private class RowProjectionState(
    val parentIndexes: IntArray,
    val depths: IntArray,
    val weights: LongArray,
) {
    val starts = DoubleArray(parentIndexes.size)
    val ends = DoubleArray(parentIndexes.size)
    val rows = ArrayList<MutableList<Int>>()
}

private fun layoutVisibleRows(state: RowProjectionState) {
    val children = childrenByParent(state.parentIndexes)
    val roots =
        state.parentIndexes.indices.filter { index ->
            state.parentIndexes[index] == -1 && state.weights[index] > 0
        }
    layoutRoots(roots, state.weights, state.starts, state.ends)
    val pending = ArrayDeque<Int>()
    roots.asReversed().forEach { root ->
        if (state.ends[root] > state.starts[root]) pending.addLast(root)
    }
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        state.rows.addNode(state.depths[node], node)
        val visibleChildren = children[node].filter { child -> state.weights[child] > 0 }
        layoutChildren(node, visibleChildren, state.weights, state.starts, state.ends)
        visibleChildren.asReversed().forEach { child ->
            if (state.ends[child] > state.starts[child]) pending.addLast(child)
        }
    }
}

private fun childrenByParent(parentIndexes: IntArray): List<List<Int>> {
    val children = List(parentIndexes.size) { ArrayList<Int>() }
    parentIndexes.forEachIndexed { index, parent ->
        require(parent in -1 until index) { "parent indexes must precede their children" }
        if (parent >= 0) children[parent] += index
    }
    return children
}

private fun layoutRoots(
    roots: List<Int>,
    weights: LongArray,
    starts: DoubleArray,
    ends: DoubleArray,
) {
    val total = roots.sumOf { root -> weights[root].toDouble() }
    var cumulative = 0.0
    roots.forEachIndexed { rootPosition, root ->
        val start = normalized(cumulative, total)
        cumulative += weights[root].toDouble()
        val end = if (rootPosition == roots.lastIndex) 1.0 else normalized(cumulative, total)
        if (end > start) {
            starts[root] = start
            ends[root] = end
        }
    }
}

private fun layoutChildren(
    parent: Int,
    children: List<Int>,
    weights: LongArray,
    starts: DoubleArray,
    ends: DoubleArray,
) {
    if (children.isEmpty()) return
    val childWeight = children.sumOf { child -> weights[child].toDouble() }
    val denominator = max(weights[parent].toDouble(), childWeight)
    val parentStart = starts[parent]
    val parentEnd = ends[parent]
    val parentWidth = parentEnd - parentStart
    var cumulative = 0.0
    children.forEach { child ->
        val start = parentStart + parentWidth * normalized(cumulative, denominator)
        cumulative += weights[child].toDouble()
        val end = min(parentEnd, parentStart + parentWidth * normalized(cumulative, denominator))
        if (end > start && start.isFinite() && end.isFinite()) {
            starts[child] = start
            ends[child] = end
        }
    }
}

private fun normalized(
    numerator: Double,
    denominator: Double,
): Double =
    if (denominator > 0.0 && denominator.isFinite()) {
        (numerator / denominator).coerceIn(0.0, 1.0)
    } else {
        0.0
    }

private fun MutableList<MutableList<Int>>.addNode(
    depth: Int,
    node: Int,
) {
    while (size <= depth) add(ArrayList())
    this[depth] += node
}
