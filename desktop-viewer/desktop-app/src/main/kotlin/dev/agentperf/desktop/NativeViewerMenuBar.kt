package dev.agentperf.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar

internal data class NativeMenuShortcut(
    val key: Key,
    val ctrl: Boolean,
    val meta: Boolean,
)

internal data class NativeActionMenuItem(
    val action: ViewerAction,
    val label: String,
    val group: Int,
    val enabled: Boolean,
    val checked: Boolean,
    val shortcut: NativeMenuShortcut?,
)

internal data class NativeViewMenuItem(
    val option: ViewDisplayOption,
    val label: String,
    val checked: Boolean,
)

internal data class NativeViewerMenuModel(
    val actionsTitle: String,
    val actions: List<NativeActionMenuItem>,
    val viewTitle: String,
    val viewItems: List<NativeViewMenuItem>,
    val advancedTitle: String,
    val exportLabel: String,
    val exportEnabled: Boolean,
) {
    constructor(
        strings: ViewerStrings,
        selectedNodeId: String?,
        autoScanEnabled: Boolean,
        panelVisibility: PanelVisibility,
        viewDisplayOptions: ViewDisplayOptions = ViewDisplayOptions(),
        exportInProgress: Boolean,
        isMacOs: Boolean,
    ) : this(
        actionsTitle = strings.actions,
        actions = ViewerActionMenu.items(strings).map { item ->
            val state = viewerActionUiState(
                action = item.action,
                selectedNodeId = selectedNodeId,
                autoScanEnabled = autoScanEnabled,
                panelVisibility = panelVisibility,
            )
            NativeActionMenuItem(
                action = item.action,
                label = item.label,
                group = item.group,
                enabled = state.enabled,
                checked = state.checked,
                shortcut = viewerActionNativeShortcut(item.action, isMacOs),
            )
        },
        viewTitle = strings.view,
        viewItems = ViewDisplayOption.entries.map { option ->
            NativeViewMenuItem(
                option = option,
                label = strings.viewOptionLabel(option),
                checked = when (option) {
                    ViewDisplayOption.HIDE_INVISIBLE_HIERARCHY_VIEWS ->
                        viewDisplayOptions.hideInvisibleHierarchyViews
                    ViewDisplayOption.HIDE_INVISIBLE_FINDINGS ->
                        viewDisplayOptions.hideInvisibleFindings
                    ViewDisplayOption.HIDE_HIERARCHY_INDICES ->
                        viewDisplayOptions.hideHierarchyIndices
                },
            )
        },
        advancedTitle = strings.advanced,
        exportLabel = strings.exportVisibleWindowViews,
        exportEnabled = !exportInProgress,
    )
}

internal fun viewerActionNativeShortcut(
    action: ViewerAction,
    isMacOs: Boolean,
): NativeMenuShortcut? {
    val key = when (action) {
        ViewerAction.TOGGLE_AUTO_SCAN -> Key.R
        ViewerAction.TOGGLE_HIERARCHY -> Key.One
        ViewerAction.TOGGLE_FINDINGS -> Key.Two
        ViewerAction.TOGGLE_DETAILS -> Key.Three
        ViewerAction.OPEN_SETTINGS -> Key.Comma
        ViewerAction.PREVIOUS_NODE,
        ViewerAction.NEXT_NODE,
        ViewerAction.TOGGLE_SELECTED_NODE,
        -> return null
    }
    return NativeMenuShortcut(
        key = key,
        ctrl = !isMacOs,
        meta = isMacOs,
    )
}

@Composable
internal fun FrameWindowScope.NativeViewerMenuBar(
    model: NativeViewerMenuModel,
    onAction: (ViewerAction) -> Unit,
    onViewOption: (ViewDisplayOption) -> Unit = {},
    onExportVisibleWindowViews: () -> Unit,
) {
    MenuBar {
        Menu(model.actionsTitle) {
            model.actions.forEachIndexed { index, item ->
                if (index > 0 && model.actions[index - 1].group != item.group) {
                    Separator()
                }
                val shortcut = item.shortcut?.let {
                    KeyShortcut(
                        key = it.key,
                        ctrl = it.ctrl,
                        meta = it.meta,
                    )
                }
                if (item.action.isToggleAction()) {
                    CheckboxItem(
                        text = item.label,
                        checked = item.checked,
                        enabled = item.enabled,
                        shortcut = shortcut,
                        onCheckedChange = { onAction(item.action) },
                    )
                } else {
                    Item(
                        text = item.label,
                        enabled = item.enabled,
                        shortcut = shortcut,
                        onClick = { onAction(item.action) },
                    )
                }
            }
        }
        Menu(model.viewTitle) {
            model.viewItems.forEach { item ->
                CheckboxItem(
                    text = item.label,
                    checked = item.checked,
                    onCheckedChange = { onViewOption(item.option) },
                )
            }
        }
        Menu(model.advancedTitle) {
            Item(
                text = model.exportLabel,
                enabled = model.exportEnabled,
                onClick = onExportVisibleWindowViews,
            )
        }
    }
}

private fun ViewerAction.isToggleAction(): Boolean = when (this) {
    ViewerAction.TOGGLE_AUTO_SCAN,
    ViewerAction.TOGGLE_HIERARCHY,
    ViewerAction.TOGGLE_FINDINGS,
    ViewerAction.TOGGLE_DETAILS,
    -> true
    ViewerAction.PREVIOUS_NODE,
    ViewerAction.NEXT_NODE,
    ViewerAction.TOGGLE_SELECTED_NODE,
    ViewerAction.OPEN_SETTINGS,
    -> false
}
