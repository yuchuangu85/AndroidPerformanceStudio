package com.androidperformancestudio.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuScope
import com.androidperformancestudio.presentation.generated.resources.Res
import com.androidperformancestudio.presentation.generated.resources.*
import com.androidperformancestudio.ui.ActivePageMenu
import com.androidperformancestudio.ui.MenuCommand
import com.androidperformancestudio.ui.MenuShortcuts
import com.androidperformancestudio.ui.PageMenu
import com.androidperformancestudio.ui.RecentMenu
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
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
    val actions: List<NativeActionMenuItem>,
    val viewItems: List<NativeViewMenuItem>,
    val importScreenshotLabel: String,
    val exportArchiveLabel: String,
    val recentItems: List<NativeRecentArchiveMenuItem>,
    val openEnabled: Boolean,
    val importScreenshotEnabled: Boolean,
    val exportEnabled: Boolean,
    val recentEnabled: Boolean,
) {
    constructor(
        language: UiLanguage,
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
        actions = ViewerActionMenu.items(language).map { item ->
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
        viewItems = ViewDisplayOption.entries.map { option ->
            NativeViewMenuItem(
                option = option,
                label = localizedStringResource(
                    when (option) {
                        ViewDisplayOption.HIDE_INVISIBLE_HIERARCHY_VIEWS -> Res.string.hide_invisible_hierarchy_views
                        ViewDisplayOption.HIDE_INVISIBLE_FINDINGS -> Res.string.hide_invisible_findings
                        ViewDisplayOption.HIDE_HIERARCHY_INDICES -> Res.string.hide_hierarchy_indices
                        ViewDisplayOption.SHOW_HIERARCHY_LAYER_VISIBILITY_BUTTONS ->
                            Res.string.show_hierarchy_layer_visibility_buttons
                        ViewDisplayOption.SHOW_VISIBLE_VIEW_BOUNDS -> Res.string.show_visible_view_bounds
                    },
                    language,
                ),
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
        importScreenshotLabel = localizedStringResource(Res.string.menu_import_screenshot, language),
        exportArchiveLabel = localizedStringResource(Res.string.menu_export_capture_archive, language),
        recentItems = recentArchives.toRecentArchiveMenuItems(),
        openEnabled = !archiveOperationInProgress,
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
    val panelActions = model.actions.filter { it.action.isViewAction() }
    val toolActions = model.actions.filter { !it.action.isViewAction() && it.action != ViewerAction.OPEN_SETTINGS }
    ActivePageMenu(
        PageMenu(
            onOpen = onImportArchive,
            openEnabled = model.openEnabled,
            imports =
                listOf(
                    MenuCommand(model.importScreenshotLabel, enabled = model.importScreenshotEnabled, onClick = onImportScreenshot),
                ),
            recent =
                RecentMenu(
                    items = model.recentItems.map { item -> MenuCommand(item.label) { onOpenRecentArchive(item.path) } },
                    enabled = model.recentEnabled,
                    onClear = onClearRecentArchives,
                ),
            exportGroups =
                listOf(
                    listOf(
                        MenuCommand(
                            model.exportArchiveLabel,
                            enabled = model.exportEnabled,
                            shortcut = MenuShortcuts.export(),
                            onClick = onExportArchive,
                        ),
                    ),
                ),
            onOpenSettings = { onAction(ViewerAction.OPEN_SETTINGS) },
            view = {
                panelActions.forEach { item -> ActionMenuItem(item, onAction) }
                Separator()
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
            },
            tools = {
                toolActions.forEachIndexed { index, item ->
                    if (index > 0 && toolActions[index - 1].group != item.group) {
                        Separator()
                    }
                    ActionMenuItem(item, onAction)
                }
            },
        ),
    )
}

@Composable
private fun MenuScope.ActionMenuItem(
    item: NativeActionMenuItem,
    onAction: (ViewerAction) -> Unit,
) {
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

/** Panel and display toggles belong to View; the remaining actions are inspector tools. */
private fun ViewerAction.isViewAction(): Boolean =
    this == ViewerAction.TOGGLE_HIERARCHY ||
        this == ViewerAction.TOGGLE_FINDINGS ||
        this == ViewerAction.TOGGLE_DETAILS ||
        this == ViewerAction.TOGGLE_HIERARCHY_IDS

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
