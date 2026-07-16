package com.androidperformancestudio.presentation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.androidperformancestudio.profileanalysis.CallStackTransform
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameGraphNavigationCommand
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.visualization.FlameGraphIntent
import com.androidperformancestudio.visualization.FlameGraphLayout
import com.androidperformancestudio.visualization.FlameViewport

internal sealed interface FlameGraphPanelAction {
    data class Hover(
        val nodeId: FlameCallNodeId?,
    ) : FlameGraphPanelAction

    data class Select(
        val nodeId: FlameCallNodeId?,
    ) : FlameGraphPanelAction

    data class OpenContextMenu(
        val nodeId: FlameCallNodeId,
        val anchor: Offset,
    ) : FlameGraphPanelAction

    data class OpenDetails(
        val nodeId: FlameCallNodeId,
    ) : FlameGraphPanelAction

    data class Navigate(
        val command: FlameGraphNavigationCommand,
    ) : FlameGraphPanelAction

    data class Copy(
        val text: String,
    ) : FlameGraphPanelAction

    data class ApplyTransform(
        val transform: CallStackTransform,
    ) : FlameGraphPanelAction

    data object DismissContextMenu : FlameGraphPanelAction

    data object DismissUnavailableFeedback : FlameGraphPanelAction

    data object DismissTooltip : FlameGraphPanelAction
}

internal object FlameGraphPresenter {
    fun actionFor(intent: FlameGraphIntent): FlameGraphPanelAction =
        when (intent) {
            is FlameGraphIntent.Hover -> FlameGraphPanelAction.Hover(intent.nodeId)
            is FlameGraphIntent.Select -> FlameGraphPanelAction.Select(intent.nodeId)
            is FlameGraphIntent.OpenContextMenu -> FlameGraphPanelAction.OpenContextMenu(intent.nodeId, intent.position)
            is FlameGraphIntent.OpenDetails -> FlameGraphPanelAction.OpenDetails(intent.nodeId)
        }

    @Suppress("LongParameterList")
    fun keyAction(
        key: Key,
        eventType: KeyEventType,
        snapshot: FlameGraphSnapshot,
        selectedNodeId: FlameCallNodeId?,
        hasContextMenu: Boolean,
        hasTooltip: Boolean,
        hasUnavailableFeedback: Boolean = false,
        controlPressed: Boolean = false,
        metaPressed: Boolean = false,
        altPressed: Boolean = false,
        shiftPressed: Boolean = false,
    ): FlameGraphPanelAction? =
        if (eventType != KeyEventType.KeyDown) {
            null
        } else {
            when {
                key == Key.Escape -> dismissAction(hasUnavailableFeedback, hasContextMenu, hasTooltip)
                key == Key.Enter -> selectedNodeId?.let(FlameGraphPanelAction::OpenDetails)
                key == Key.C && (controlPressed || metaPressed) ->
                    selectedNodeId?.let { nodeId ->
                        copyText(snapshot, nodeId)?.let(FlameGraphPanelAction::Copy)
                    }
                else ->
                    transformAction(
                        key = key,
                        snapshot = snapshot,
                        selectedNodeId = selectedNodeId,
                        modifiersPressed = controlPressed || metaPressed || altPressed,
                        shiftPressed = shiftPressed,
                    ) ?: FlameGraphKeyboardNavigation
                        .commandFor(key, eventType, snapshot.query.direction)
                        ?.let(FlameGraphPanelAction::Navigate)
            }
        }

    private fun transformAction(
        key: Key,
        snapshot: FlameGraphSnapshot,
        selectedNodeId: FlameCallNodeId?,
        modifiersPressed: Boolean,
        shiftPressed: Boolean,
    ): FlameGraphPanelAction.ApplyTransform? {
        if (modifiersPressed || selectedNodeId == null) return null
        val command =
            FlameGraphContextCommands.commandForShortcut(snapshot, selectedNodeId, key, shiftPressed)
                as? FlameGraphContextCommand.ApplyTransform
        return command?.transform?.let(FlameGraphPanelAction::ApplyTransform)
    }

    private fun dismissAction(
        hasUnavailableFeedback: Boolean,
        hasContextMenu: Boolean,
        hasTooltip: Boolean,
    ): FlameGraphPanelAction? =
        when {
            hasUnavailableFeedback -> FlameGraphPanelAction.DismissUnavailableFeedback
            hasContextMenu -> FlameGraphPanelAction.DismissContextMenu
            hasTooltip -> FlameGraphPanelAction.DismissTooltip
            else -> null
        }

    fun unavailableFeedbackFor(action: FlameGraphPanelAction): FlameGraphUnavailableFeedback? =
        when (action) {
            is FlameGraphPanelAction.OpenDetails -> FlameGraphUnavailableFeedback.Details(action.nodeId)
            else -> null
        }

    fun copyText(
        snapshot: FlameGraphSnapshot,
        nodeId: FlameCallNodeId,
    ): String? =
        snapshot.callNodes
            .indexOf(nodeId)
            ?.let(snapshot.callNodes::frameAt)
            ?.symbolName

    fun scrollRowToReveal(
        snapshot: FlameGraphSnapshot,
        nodeId: FlameCallNodeId,
        viewport: FlameViewport,
    ): Int = FlameGraphLayout.scrollRowToReveal(snapshot, nodeId, viewport)
}

internal sealed interface FlameGraphUnavailableFeedback {
    val nodeId: FlameCallNodeId
    val message: String

    data class Details(
        override val nodeId: FlameCallNodeId,
    ) : FlameGraphUnavailableFeedback {
        override val message: String = DETAILS_UNAVAILABLE
    }
}

private const val DETAILS_UNAVAILABLE =
    "Source and disassembly details are not available yet. Press Escape to dismiss."
