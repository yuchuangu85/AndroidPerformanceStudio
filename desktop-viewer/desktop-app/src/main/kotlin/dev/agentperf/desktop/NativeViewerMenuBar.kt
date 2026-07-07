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
    val group: Int,
    val checked: Boolean,
)

internal data class NativeViewerMenuModel(
    val actionsTitle: String,
    val actions: List<NativeActionMenuItem>,
    val viewTitle: String,
    val viewItems: List<NativeViewMenuItem>,
    val fileTitle: String,
    val importLabel: String,
    val exportLabel: String,
    val importMenuText: String,
    val exportMenuText: String,
    val importShortcut: NativeMenuShortcut,
    val exportShortcut: NativeMenuShortcut,
    val importEnabled: Boolean,
    val exportEnabled: Boolean,
) {
    constructor(
        strings: ViewerStrings,
        selectedNodeId: String?,
        autoScanEnabled: Boolean,
        panelVisibility: PanelVisibility,
        viewDisplayOptions: ViewDisplayOptions = ViewDisplayOptions(),
        archiveOperationInProgress: Boolean,
        canExportArchive: Boolean,
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
                group = if (option == ViewDisplayOption.SHOW_VISIBLE_VIEW_BOUNDS) 1 else 0,
                checked = when (option) {
                    ViewDisplayOption.HIDE_INVISIBLE_HIERARCHY_VIEWS ->
                        viewDisplayOptions.hideInvisibleHierarchyViews
                    ViewDisplayOption.HIDE_INVISIBLE_FINDINGS ->
                        viewDisplayOptions.hideInvisibleFindings
                    ViewDisplayOption.HIDE_HIERARCHY_INDICES ->
                        viewDisplayOptions.hideHierarchyIndices
                    ViewDisplayOption.SHOW_VISIBLE_VIEW_BOUNDS ->
                        viewDisplayOptions.showVisibleViewBounds
                },
            )
        },
        fileTitle = strings.file,
        importLabel = strings.importArchive,
        exportLabel = strings.exportArchive,
        importMenuText = nativeMenuItemText(strings.importArchive),
        exportMenuText = nativeMenuItemText(strings.exportArchive),
        importShortcut = nativePrimaryShortcut(Key.I, isMacOs),
        exportShortcut = nativePrimaryShortcut(Key.E, isMacOs),
        importEnabled = !archiveOperationInProgress,
        exportEnabled = !archiveOperationInProgress && canExportArchive,
    )
}

internal const val NATIVE_MENU_ITEM_MIN_TEXT_COLUMNS = 8

internal fun nativeMenuItemText(label: String): String {
    if (label.length >= NATIVE_MENU_ITEM_MIN_TEXT_COLUMNS) return label
    return label + "\u2003".repeat(NATIVE_MENU_ITEM_MIN_TEXT_COLUMNS - label.length)
}

internal fun nativePrimaryShortcut(key: Key, isMacOs: Boolean): NativeMenuShortcut =
    NativeMenuShortcut(
        key = key,
        ctrl = !isMacOs,
        meta = isMacOs,
    )

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
    return nativePrimaryShortcut(key, isMacOs)
}

private fun NativeMenuShortcut.toKeyShortcut(): KeyShortcut = KeyShortcut(
    key = key,
    ctrl = ctrl,
    meta = meta,
)

@Composable
internal fun FrameWindowScope.NativeViewerMenuBar(
    model: NativeViewerMenuModel,
    onAction: (ViewerAction) -> Unit,
    onViewOption: (ViewDisplayOption) -> Unit = {},
    onImportArchive: () -> Unit,
    onExportArchive: () -> Unit,
) {
    MenuBar {
        Menu(model.fileTitle) {
            Item(
                text = model.importMenuText,
                enabled = model.importEnabled,
                shortcut = model.importShortcut.toKeyShortcut(),
                onClick = onImportArchive,
            )
            Item(
                text = model.exportMenuText,
                enabled = model.exportEnabled,
                shortcut = model.exportShortcut.toKeyShortcut(),
                onClick = onExportArchive,
            )
        }
        Menu(model.actionsTitle) {
            model.actions.forEachIndexed { index, item ->
                if (index > 0 && model.actions[index - 1].group != item.group) {
                    Separator()
                }
                val shortcut = item.shortcut?.toKeyShortcut()
                if (item.action.isToggleAction()) {
                    CheckboxItem(
                        text = nativeMenuItemText(item.label),
                        checked = item.checked,
                        enabled = item.enabled,
                        shortcut = shortcut,
                        onCheckedChange = { onAction(item.action) },
                    )
                } else {
                    Item(
                        text = nativeMenuItemText(item.label),
                        enabled = item.enabled,
                        shortcut = shortcut,
                        onClick = { onAction(item.action) },
                    )
                }
            }
        }
        Menu(model.viewTitle) {
            model.viewItems.forEachIndexed { index, item ->
                if (index > 0 && model.viewItems[index - 1].group != item.group) {
                    Separator()
                }
                CheckboxItem(
                    text = nativeMenuItemText(item.label),
                    checked = item.checked,
                    onCheckedChange = { onViewOption(item.option) },
                )
            }
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
