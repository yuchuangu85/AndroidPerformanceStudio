package com.androidperformancestudio.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import java.nio.file.Path

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

internal data class NativeRecentArchiveMenuItem(
    val label: String,
    val path: Path,
)

internal data class NativeViewerMenuModel(
    val actionsTitle: String,
    val actions: List<NativeActionMenuItem>,
    val viewTitle: String,
    val viewItems: List<NativeViewMenuItem>,
    val fileTitle: String,
    val importLabel: String,
    val importScreenshotLabel: String,
    val exportLabel: String,
    val openRecentTitle: String,
    val noRecentLabel: String,
    val clearRecentLabel: String,
    val recentItems: List<NativeRecentArchiveMenuItem>,
    val settingsLabel: String?,
    val importMenuText: String,
    val importScreenshotMenuText: String,
    val exportMenuText: String,
    val settingsMenuText: String?,
    val importShortcut: NativeMenuShortcut,
    val importScreenshotShortcut: NativeMenuShortcut?,
    val exportShortcut: NativeMenuShortcut,
    val settingsShortcut: NativeMenuShortcut?,
    val importEnabled: Boolean,
    val importScreenshotEnabled: Boolean,
    val exportEnabled: Boolean,
    val recentEnabled: Boolean,
) {
    constructor(
        strings: ViewerStrings,
        selectedNodeId: String?,
        autoScanEnabled: Boolean,
        panelVisibility: PanelVisibility,
        viewDisplayOptions: ViewDisplayOptions = ViewDisplayOptions(),
        archiveOperationInProgress: Boolean,
        canExportArchive: Boolean,
        canImportScreenshot: Boolean,
        recentArchives: List<Path> = emptyList(),
        isMacOs: Boolean,
    ) : this(
        actionsTitle = strings.actions,
        actions = ViewerActionMenu.items(strings).map { item ->
            val state = viewerActionUiState(
                action = item.action,
                selectedNodeId = selectedNodeId,
                autoScanEnabled = autoScanEnabled,
                panelVisibility = panelVisibility,
                viewDisplayOptions = viewDisplayOptions,
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
                group = when (option) {
                    ViewDisplayOption.SHOW_HIERARCHY_LAYER_VISIBILITY_BUTTONS -> 1
                    ViewDisplayOption.SHOW_VISIBLE_VIEW_BOUNDS -> 2
                    else -> 0
                },
                checked = when (option) {
                    ViewDisplayOption.HIDE_INVISIBLE_HIERARCHY_VIEWS ->
                        viewDisplayOptions.hideInvisibleHierarchyViews
                    ViewDisplayOption.HIDE_INVISIBLE_FINDINGS ->
                        viewDisplayOptions.hideInvisibleFindings
                    ViewDisplayOption.HIDE_HIERARCHY_INDICES ->
                        viewDisplayOptions.hideHierarchyIndices
                    ViewDisplayOption.SHOW_HIERARCHY_LAYER_VISIBILITY_BUTTONS ->
                        viewDisplayOptions.showHierarchyLayerVisibilityButtons
                    ViewDisplayOption.SHOW_VISIBLE_VIEW_BOUNDS ->
                        viewDisplayOptions.showVisibleViewBounds
                },
            )
        },
        fileTitle = strings.file,
        importLabel = strings.importArchive,
        importScreenshotLabel = strings.importScreenshot,
        exportLabel = strings.exportArchive,
        openRecentTitle = strings.openRecent,
        noRecentLabel = strings.noRecentArchives,
        clearRecentLabel = strings.clearRecentMenu,
        recentItems = recentArchives.toRecentArchiveMenuItems(),
        settingsLabel = strings.settings.takeUnless { isMacOs },
        importMenuText = nativeMenuItemText(strings.importArchive),
        importScreenshotMenuText = nativeMenuItemText(strings.importScreenshot),
        exportMenuText = nativeMenuItemText(strings.exportArchive),
        settingsMenuText = nativeMenuItemText(strings.settings).takeUnless { isMacOs },
        importShortcut = nativePrimaryShortcut(Key.I, isMacOs),
        importScreenshotShortcut = null,
        exportShortcut = nativePrimaryShortcut(Key.E, isMacOs),
        settingsShortcut = nativePrimaryShortcut(Key.Comma, isMacOs).takeUnless { isMacOs },
        importEnabled = !archiveOperationInProgress,
        importScreenshotEnabled = !archiveOperationInProgress && canImportScreenshot,
        exportEnabled = !archiveOperationInProgress && canExportArchive,
        recentEnabled = !archiveOperationInProgress,
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
        ViewerAction.TOGGLE_HIERARCHY_IDS,
        ViewerAction.PREVIOUS_NODE,
        ViewerAction.NEXT_NODE,
        ViewerAction.TOGGLE_SELECTED_NODE,
        -> return null
    }
    return nativePrimaryShortcut(key, isMacOs)
}

internal fun List<Path>.toRecentArchiveMenuItems(): List<NativeRecentArchiveMenuItem> {
    val normalized = map { it.toAbsolutePath().normalize() }.distinct()
    val duplicateNames =
        normalized
            .groupingBy { it.fileName?.toString().orEmpty() }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    return normalized.map { path ->
        val fileName = path.fileName?.toString().orEmpty()
        NativeRecentArchiveMenuItem(
            label = if (fileName.isBlank() || fileName in duplicateNames) path.toString() else fileName,
            path = path,
        )
    }
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
    onOpenRecentArchive: (Path) -> Unit,
    onClearRecentArchives: () -> Unit,
    onImportScreenshot: () -> Unit,
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
                text = model.importScreenshotMenuText,
                enabled = model.importScreenshotEnabled,
                shortcut = model.importScreenshotShortcut?.toKeyShortcut(),
                onClick = onImportScreenshot,
            )
            Menu(model.openRecentTitle, enabled = model.recentEnabled) {
                if (model.recentItems.isEmpty()) {
                    Item(text = model.noRecentLabel, enabled = false, onClick = {})
                } else {
                    model.recentItems.forEach { item ->
                        Item(
                            text = item.label,
                            onClick = { onOpenRecentArchive(item.path) },
                        )
                    }
                    Separator()
                    Item(
                        text = model.clearRecentLabel,
                        onClick = onClearRecentArchives,
                    )
                }
            }
            Separator()
            Item(
                text = model.exportMenuText,
                enabled = model.exportEnabled,
                shortcut = model.exportShortcut.toKeyShortcut(),
                onClick = onExportArchive,
            )
            val settingsMenuText = model.settingsMenuText
            val settingsShortcut = model.settingsShortcut
            if (settingsMenuText != null && settingsShortcut != null) {
                Separator()
                Item(
                    text = settingsMenuText,
                    shortcut = settingsShortcut.toKeyShortcut(),
                    onClick = { onAction(ViewerAction.OPEN_SETTINGS) },
                )
            }
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
    ViewerAction.TOGGLE_HIERARCHY_IDS,
    -> true
    ViewerAction.PREVIOUS_NODE,
    ViewerAction.NEXT_NODE,
    ViewerAction.TOGGLE_SELECTED_NODE,
    ViewerAction.OPEN_SETTINGS,
    -> false
}
