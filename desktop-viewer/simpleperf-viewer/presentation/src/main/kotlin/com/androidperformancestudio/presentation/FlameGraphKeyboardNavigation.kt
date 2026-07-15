package com.androidperformancestudio.presentation

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameGraphNavigator
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.visualization.FlameGraphLayout
import com.androidperformancestudio.visualization.FlameViewport

internal enum class FlameGraphNavigationCommand {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

internal data class FlameGraphNavigationResult(
    val targetNodeId: FlameCallNodeId,
    val scrollRow: Int,
)

internal object FlameGraphKeyboardNavigation {
    fun commandFor(
        key: Key,
        eventType: KeyEventType,
    ): FlameGraphNavigationCommand? {
        if (eventType != KeyEventType.KeyDown) return null
        return when (key) {
            Key.DirectionUp -> FlameGraphNavigationCommand.UP
            Key.DirectionDown -> FlameGraphNavigationCommand.DOWN
            Key.DirectionLeft -> FlameGraphNavigationCommand.LEFT
            Key.DirectionRight -> FlameGraphNavigationCommand.RIGHT
            else -> null
        }
    }

    fun navigate(
        snapshot: FlameGraphSnapshot,
        selectedNodeId: FlameCallNodeId?,
        command: FlameGraphNavigationCommand,
        viewport: FlameViewport,
    ): FlameGraphNavigationResult? {
        val target = selectedNodeId?.let { selected -> snapshot.targetFor(selected, command) }
        return target?.let { targetNodeId ->
            FlameGraphNavigationResult(
                targetNodeId = targetNodeId,
                scrollRow = FlameGraphLayout.scrollRowToReveal(snapshot, targetNodeId, viewport),
            )
        }
    }
}

private fun FlameGraphSnapshot.targetFor(
    selectedNodeId: FlameCallNodeId,
    command: FlameGraphNavigationCommand,
): FlameCallNodeId? =
    when (command) {
        FlameGraphNavigationCommand.LEFT -> FlameGraphNavigator.previousSibling(this, selectedNodeId)
        FlameGraphNavigationCommand.RIGHT -> FlameGraphNavigator.nextSibling(this, selectedNodeId)
        FlameGraphNavigationCommand.UP ->
            if (query.direction == CallStackDirection.FORWARD) {
                FlameGraphNavigator.widestChild(this, selectedNodeId)
            } else {
                FlameGraphNavigator.parent(this, selectedNodeId)
            }
        FlameGraphNavigationCommand.DOWN ->
            if (query.direction == CallStackDirection.FORWARD) {
                FlameGraphNavigator.parent(this, selectedNodeId)
            } else {
                FlameGraphNavigator.widestChild(this, selectedNodeId)
            }
    }
