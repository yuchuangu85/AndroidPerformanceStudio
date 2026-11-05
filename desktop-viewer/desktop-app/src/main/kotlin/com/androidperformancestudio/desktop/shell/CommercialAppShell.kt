@file:Suppress("FunctionNaming")

package com.androidperformancestudio.desktop.shell

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.desktop.AppDestination
import com.androidperformancestudio.desktop.AppNavigator
import com.androidperformancestudio.desktop.navigationLabelResource
import com.androidperformancestudio.desktop.titleResource
import com.androidperformancestudio.desktop_app.generated.resources.Res
import com.androidperformancestudio.desktop_app.generated.resources.command_palette
import com.androidperformancestudio.desktop_app.generated.resources.collapse_navigation
import com.androidperformancestudio.desktop_app.generated.resources.expand_navigation
import com.androidperformancestudio.desktop_app.generated.resources.no_active_artifact
import com.androidperformancestudio.desktop_app.generated.resources.no_matching_commands
import com.androidperformancestudio.desktop_app.generated.resources.search_commands
import com.androidperformancestudio.desktop_app.generated.resources.settings
import com.androidperformancestudio.desktop_app.generated.resources.task_manager
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioStatusChip
import com.androidperformancestudio.ui.studio.StudioStatusTone
import com.androidperformancestudio.ui.studio.StudioSearchField
import com.androidperformancestudio.ui.studio.StudioTokens
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import com.androidperformancestudio.ui_components.generated.resources.Res as UiComponentsRes
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_rail_collapse
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_rail_expand
import com.androidperformancestudio.ui_components.generated.resources.ic_task_manager

internal data class WorkspaceContext(
    val deviceLabel: String? = null,
    val packageName: String? = null,
    val processName: String? = null,
    val artifactLabel: String? = null,
)

private data class StudioNavigationItem(
    val destination: AppDestination,
    val label: String,
    val icon: DrawableResource,
)

/**
 * Phase-2 shell chrome. It intentionally projects only app navigation and read-only context; all
 * feature state remains in the retained destination composed by [content].
 */
@Composable
internal fun CommercialAppShell(
    navigator: AppNavigator,
    language: UiLanguage,
    navigationRailCollapsed: Boolean,
    onToggleNavigationRailCollapsed: () -> Unit,
    moduleOrder: List<AppDestination>,
    screencastWindowOpen: Boolean,
    onOpenScreencastWindow: () -> Unit,
    modifier: Modifier = Modifier,
    workspaceContext: WorkspaceContext = WorkspaceContext(),
    content: @Composable BoxScope.() -> Unit,
) {
    var showCommandPalette by remember { mutableStateOf(false) }
    var showTaskManager by remember { mutableStateOf(false) }
    val shellFocusRequester = remember { FocusRequester() }
    LaunchedEffect(navigator.destination) {
        shellFocusRequester.requestFocus()
    }
    val colors = LocalViewerColors.current
    val destinationTitle = localizedStringResource(navigator.destination.titleResource, language)
    val commandPaletteLabel = localizedStringResource(Res.string.command_palette, language)
    val settingsLabel = localizedStringResource(Res.string.settings, language)
    // The collapse/expand toggle's label resolves against the current state; re-resolved on toggle.
    val collapseToggleLabel = localizedStringResource(
        if (navigationRailCollapsed) Res.string.expand_navigation else Res.string.collapse_navigation,
        language,
    )
    val navigationItems =
        // Home is pinned first; the rest follow the user-customized module order shared with the
        // home screen, so reordering modules on the home grid reorders the rail live.
        (listOf(AppDestination.HOME) + moduleOrder).map {
            StudioNavigationItem(
                destination = it,
                label = localizedStringResource(it.navigationLabelResource, language),
                icon = it.navigationIconResource,
            )
        }
    val commandItems =
        (navigationItems +
            listOf(AppDestination.METHOD_RECORDING)
                .map {
                    StudioNavigationItem(
                        destination = it,
                        label = localizedStringResource(it.navigationLabelResource, language),
                        icon = it.navigationIconResource,
                    )
                })
            .distinctBy(StudioNavigationItem::destination)

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .focusRequester(shellFocusRequester)
                .onPreviewKeyEvent { event ->
                    when {
                        event.type == KeyEventType.KeyDown &&
                            event.key == Key.K &&
                            (event.isCtrlPressed || event.isMetaPressed) -> {
                            showCommandPalette = true
                            true
                        }
                        event.type == KeyEventType.KeyDown && event.key == Key.Escape && showCommandPalette -> {
                            showCommandPalette = false
                            true
                        }
                        event.type == KeyEventType.KeyDown &&
                            event.key == Key.Comma &&
                            (event.isCtrlPressed || event.isMetaPressed) -> {
                            navigator.open(AppDestination.SETTINGS)
                            true
                        }
                        event.type == KeyEventType.KeyDown &&
                            event.key == Key.M &&
                            (event.isCtrlPressed || event.isMetaPressed) -> {
                            showTaskManager = true
                            true
                        }
                        else -> false
                    }
                }
                .focusable(),
    ) {
        val collapsed = navigationRailCollapsed
        Column(modifier = Modifier.fillMaxSize().background(colors.toolbar)) {
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                StudioNavigationRail(
                    items = navigationItems,
                    selectedDestination = navigator.destination,
                    collapsed = collapsed,
                    onToggleCollapsed = onToggleNavigationRailCollapsed,
                    collapseToggleLabel = collapseToggleLabel,
                    settingsLabel = settingsLabel,
                    settingsSelected = navigator.destination == AppDestination.SETTINGS,
                    onDestinationSelected = { destination ->
                        // SCREENCAST opens a separate OS window rather than becoming an in-window
                        // destination; everything else switches the navigator as before.
                        if (destination == AppDestination.SCREENCAST) {
                            onOpenScreencastWindow()
                        } else {
                            navigator.open(destination)
                        }
                    },
                    screencastWindowOpen = screencastWindowOpen,
                    onOpenSettings = { navigator.open(AppDestination.SETTINGS) },
                    taskManagerLabel = localizedStringResource(Res.string.task_manager, language),
                    onOpenTaskManager = { showTaskManager = true },
                )
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    content()
                }
            }
            StudioStatusBar(
                destinationTitle = destinationTitle,
                workspaceContext = workspaceContext,
                noActiveArtifactLabel = localizedStringResource(Res.string.no_active_artifact, language),
            )
        }
    }

    if (showCommandPalette) {
        StudioCommandPalette(
            title = commandPaletteLabel,
            searchPlaceholder = localizedStringResource(Res.string.search_commands, language),
            noResultsLabel = localizedStringResource(Res.string.no_matching_commands, language),
            items = commandItems,
            onDismiss = { showCommandPalette = false },
            onDestinationSelected = { destination ->
                if (destination == AppDestination.SCREENCAST) {
                    onOpenScreencastWindow()
                } else {
                    navigator.open(destination)
                }
                showCommandPalette = false
            },
            taskManagerLabel = localizedStringResource(Res.string.task_manager, language),
            onOpenTaskManager = {
                showCommandPalette = false
                showTaskManager = true
            },
        )
    }

    if (showTaskManager) {
        ModuleTaskManagerOverlay(
            navigator = navigator,
            language = language,
            onDismiss = { showTaskManager = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StudioNavigationRail(
    items: List<StudioNavigationItem>,
    selectedDestination: AppDestination,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    collapseToggleLabel: String,
    settingsLabel: String,
    settingsSelected: Boolean,
    onDestinationSelected: (AppDestination) -> Unit,
    screencastWindowOpen: Boolean,
    onOpenSettings: () -> Unit,
    taskManagerLabel: String,
    onOpenTaskManager: () -> Unit,
) {
    val colors = LocalViewerColors.current
    val width by animateDpAsState(
        if (collapsed) StudioTokens.navigationCollapsedWidth else StudioTokens.navigationExpandedWidth,
        label = "navigationRailWidth",
    )
    val toggleIcon =
        if (collapsed) UiComponentsRes.drawable.ic_nav_rail_expand else UiComponentsRes.drawable.ic_nav_rail_collapse
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .width(width)
                .background(colors.toolbar)
                .border(width = StudioTokens.borderWidth, color = colors.border)
                .padding(StudioTokens.compactContentPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEach { item ->
            NavigationRailEntry(
                icon = item.icon,
                label = item.label,
                selected = item.destination == selectedDestination,
                collapsed = collapsed,
                // A "running" dot marks the screencast entry while its window is open, so the user can
                // tell the screencast is live even while looking at another module.
                running = item.destination == AppDestination.SCREENCAST && screencastWindowOpen,
                onClick = { onDestinationSelected(item.destination) },
            )
        }
        Spacer(Modifier.weight(1f))
        // The collapse/expand toggle sits between the destinations and the divider + Settings
        // entry, matching the layout the icon-centering comment anticipated.
        NavigationRailEntry(
            icon = toggleIcon,
            label = collapseToggleLabel,
            selected = false,
            collapsed = collapsed,
            onClick = onToggleCollapsed,
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .height(StudioTokens.borderWidth)
                    .background(colors.border),
        )
        NavigationRailEntry(
            icon = AppDestination.SETTINGS.navigationIconResource,
            label = settingsLabel,
            selected = settingsSelected,
            collapsed = collapsed,
            onClick = onOpenSettings,
        )
        // The task manager sits at the very bottom of the rail: an "open modules" switcher that
        // lists retained modules and lets the user close (evict) them to free their resources.
        NavigationRailEntry(
            icon = UiComponentsRes.drawable.ic_task_manager,
            label = taskManagerLabel,
            selected = false,
            collapsed = collapsed,
            onClick = onOpenTaskManager,
        )
    }
}

private const val TOOLTIP_DELAY_MILLIS = 400

/**
 * One rail row shared by destinations and the settings action, so every entry keeps the same icon
 * offset and collapse behaviour.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NavigationRailEntry(
    icon: DrawableResource,
    label: String,
    selected: Boolean,
    collapsed: Boolean,
    running: Boolean = false,
    onClick: () -> Unit,
) {
    if (collapsed) {
        // The collapsed rail is icon-only, so hovering reveals the destination title.
        TooltipArea(tooltip = { RailTooltip(label) }, delayMillis = TOOLTIP_DELAY_MILLIS) {
            RailEntryButton(icon, label, selected, collapsed, running, onClick)
        }
    } else {
        RailEntryButton(icon, label, selected, collapsed, running, onClick)
    }
}

@Composable
private fun RailEntryButton(
    icon: DrawableResource,
    label: String,
    selected: Boolean,
    collapsed: Boolean,
    running: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalViewerColors.current
    val iconTint = if (selected) colors.accent else colors.secondaryText
    TextButton(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(StudioTokens.navigationItemHeight)
                .clip(RoundedCornerShape(StudioTokens.smallRadius))
                .background(if (selected) colors.selectedRow else colors.transparent)
                .semantics { contentDescription = label },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = if (collapsed) 0.dp else StudioTokens.navigationItemHorizontalPadding),
    ) {
        // Expanded rows start at the shared icon offset; the collapsed rail centres the icon so it
        // lines up with the collapse control below.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement =
                if (collapsed) {
                    Arrangement.Center
                } else {
                    Arrangement.spacedBy(StudioTokens.navigationIconGap)
                },
        ) {
            // The icon sits in a fixed-size box so a "running" badge dot can overlay its top-end
            // corner without shifting the label offset.
            Box(modifier = Modifier.size(StudioTokens.navigationIconSize)) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = if (collapsed) label else null,
                    modifier = Modifier.fillMaxSize(),
                    tint = iconTint,
                )
                if (running) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(colors.accent),
                    )
                }
            }
            if (!collapsed) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    color = if (selected) colors.accent else colors.primaryText,
                    fontSize = ViewerTypography.bodyCompact.fontSize,
                    lineHeight = ViewerTypography.bodyCompact.lineHeight,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Small floating label shown when an icon-only rail control is hovered. */
@Composable
private fun RailTooltip(label: String) {
    val colors = LocalViewerColors.current
    Surface(
        shape = RoundedCornerShape(StudioTokens.smallRadius),
        color = colors.panel,
        contentColor = colors.primaryText,
        border = BorderStroke(StudioTokens.borderWidth, colors.border),
        shadowElevation = 6.dp,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            color = colors.primaryText,
            fontSize = ViewerTypography.secondary.fontSize,
            lineHeight = ViewerTypography.secondary.lineHeight,
            maxLines = 1,
        )
    }
}

@Composable
private fun StudioStatusBar(
    destinationTitle: String,
    workspaceContext: WorkspaceContext,
    noActiveArtifactLabel: String,
) {
    val colors = LocalViewerColors.current
    SelectionContainer {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(StudioTokens.globalStatusBarHeight)
                    .background(colors.toolbar)
                    .border(width = StudioTokens.borderWidth, color = colors.border)
                    .padding(horizontal = StudioTokens.compactContentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
        ) {
            Text(
                text = destinationTitle,
                color = colors.secondaryText,
                fontSize = ViewerTypography.label.fontSize,
                lineHeight = ViewerTypography.label.lineHeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            StudioStatusChip(
                label = workspaceContext.artifactLabel ?: noActiveArtifactLabel,
                tone = if (workspaceContext.artifactLabel == null) StudioStatusTone.NEUTRAL else StudioStatusTone.INFO,
            )
        }
    }
}

@Composable
private fun StudioCommandPalette(
    title: String,
    searchPlaceholder: String,
    noResultsLabel: String,
    items: List<StudioNavigationItem>,
    onDismiss: () -> Unit,
    onDestinationSelected: (AppDestination) -> Unit,
    taskManagerLabel: String,
    onOpenTaskManager: () -> Unit,
) {
    val colors = LocalViewerColors.current
    var query by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocusRequester.requestFocus() }
    val filteredItems = items.filter { it.label.contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, color = colors.primaryText) },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 320.dp, max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
            ) {
                StudioSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = searchPlaceholder,
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                )
                Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (filteredItems.isEmpty()) {
                        Text(text = noResultsLabel, color = colors.secondaryText)
                    }
                    filteredItems.forEach { item ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onDestinationSelected(item.destination) },
                        ) {
                            Text(
                                text = item.label,
                                modifier = Modifier.fillMaxWidth(),
                                color = colors.primaryText,
                                fontSize = ViewerTypography.body.fontSize,
                                lineHeight = ViewerTypography.body.lineHeight,
                            )
                        }
                    }
                }
                // Footer action: open the task manager (open-modules switcher) from the palette.
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOpenTaskManager,
                ) {
                    Text(
                        text = taskManagerLabel,
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.accent,
                        fontSize = ViewerTypography.body.fontSize,
                        lineHeight = ViewerTypography.body.lineHeight,
                    )
                }
            }
        },
        confirmButton = {},
    )
}
