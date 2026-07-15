package com.androidperformancestudio.presentation

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

    data object DismissTooltip : FlameGraphPanelAction
}

internal object FlameGraphPresenter {
    fun actionFor(intent: FlameGraphIntent): FlameGraphPanelAction =
        when (intent) {
            is FlameGraphIntent.Hover -> FlameGraphPanelAction.Hover(intent.nodeId)
            is FlameGraphIntent.Select -> FlameGraphPanelAction.Select(intent.nodeId)
            is FlameGraphIntent.OpenContextMenu -> FlameGraphPanelAction.OpenContextMenu(intent.nodeId)
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
        controlPressed: Boolean = false,
        metaPressed: Boolean = false,
    ): FlameGraphPanelAction? =
        if (eventType != KeyEventType.KeyDown) {
            null
        } else {
            when {
                key == Key.Escape -> dismissAction(hasContextMenu, hasTooltip)
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
        hasContextMenu: Boolean,
        hasTooltip: Boolean,
    ): FlameGraphPanelAction? =
        when {
            hasContextMenu -> FlameGraphPanelAction.DismissContextMenu
            hasTooltip -> FlameGraphPanelAction.DismissTooltip
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
