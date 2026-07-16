package com.androidperformancestudio.presentation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
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
                    FlameGraphKeyboardNavigation
                        .commandFor(key, eventType, snapshot.query.direction)
                        ?.let(FlameGraphPanelAction::Navigate)
            }
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
            is FlameGraphPanelAction.OpenContextMenu ->
                FlameGraphUnavailableFeedback.ContextActions(action.nodeId, action.anchor)
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

    data class ContextActions(
        override val nodeId: FlameCallNodeId,
        val anchor: Offset,
    ) : FlameGraphUnavailableFeedback {
        override val message: String = CONTEXT_ACTIONS_UNAVAILABLE
    }

    data class Details(
        override val nodeId: FlameCallNodeId,
    ) : FlameGraphUnavailableFeedback {
        override val message: String = DETAILS_UNAVAILABLE
    }
}

private const val CONTEXT_ACTIONS_UNAVAILABLE =
    "Context actions are not available yet. Press Escape to dismiss."
private const val DETAILS_UNAVAILABLE =
    "Source and disassembly details are not available yet. Press Escape to dismiss."
