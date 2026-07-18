package com.androidperformancestudio.presentation

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameGraphNavigationCommand
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.visualization.FlameGraphLayout
import com.androidperformancestudio.visualization.FlameViewport
import com.androidperformancestudio.visualization.NavigationAction

internal object FlameGraphKeyboardNavigation {
    fun horizontalActionFor(
        key: Key,
        eventType: KeyEventType,
        modifiersPressed: Boolean,
    ): NavigationAction? {
        if (eventType != KeyEventType.KeyDown || modifiersPressed) return null
        return when (key) {
            Key.W -> NavigationAction.ZOOM_IN
            Key.S -> NavigationAction.ZOOM_OUT
            Key.A -> NavigationAction.PAN_LEFT
            Key.D -> NavigationAction.PAN_RIGHT
            else -> null
        }
    }

    fun commandFor(
        key: Key,
        eventType: KeyEventType,
        direction: CallStackDirection,
    ): FlameGraphNavigationCommand? {
        if (eventType != KeyEventType.KeyDown) return null
        return when (key) {
            Key.DirectionUp ->
                if (direction == CallStackDirection.FORWARD) {
                    FlameGraphNavigationCommand.WIDEST_CHILD
                } else {
                    FlameGraphNavigationCommand.PARENT
                }
            Key.DirectionDown ->
                if (direction == CallStackDirection.FORWARD) {
                    FlameGraphNavigationCommand.PARENT
                } else {
                    FlameGraphNavigationCommand.WIDEST_CHILD
                }
            Key.DirectionLeft -> FlameGraphNavigationCommand.PREVIOUS_SIBLING
            Key.DirectionRight -> FlameGraphNavigationCommand.NEXT_SIBLING
            else -> null
        }
    }

    fun scrollRowToReveal(
        snapshot: FlameGraphSnapshot,
        targetNodeId: FlameCallNodeId,
        viewport: FlameViewport,
    ): Int = FlameGraphLayout.scrollRowToReveal(snapshot, targetNodeId, viewport)
}
