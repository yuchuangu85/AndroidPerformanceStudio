@file:Suppress("FunctionNaming")

package com.androidperformancestudio.desktop.shell

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.desktop.AppDestination
import com.androidperformancestudio.desktop.AppNavigator
import com.androidperformancestudio.desktop.navigationLabelResource
import com.androidperformancestudio.desktop_app.generated.resources.Res
import com.androidperformancestudio.desktop_app.generated.resources.command_palette
import com.androidperformancestudio.desktop_app.generated.resources.no_matching_commands
import com.androidperformancestudio.desktop_app.generated.resources.search_commands
import com.androidperformancestudio.desktop_app.generated.resources.settings
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioSearchField
import com.androidperformancestudio.ui.studio.StudioTokens
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

internal const val NAVIGATION_COLLAPSE_BREAKPOINT_DP: Int = 1280

internal fun navigationRailCollapsedFor(availableWidthDp: Int): Boolean =
    availableWidthDp < NAVIGATION_COLLAPSE_BREAKPOINT_DP

internal fun resolvedNavigationRailCollapsed(availableWidthDp: Int, userCollapsed: Boolean?): Boolean =
    userCollapsed ?: navigationRailCollapsedFor(availableWidthDp)

private data class StudioNavigationItem(
    val destination: AppDestination,
    val label: String,
    val icon: DrawableResource,
)

/**
 * Shell chrome owns app navigation; feature state and task feedback remain in the retained
 * destination composed by [content].
 */
@Composable
internal fun CommercialAppShell(
    navigator: AppNavigator,
    language: UiLanguage,
    navigationRailCollapsed: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var showCommandPalette by remember { mutableStateOf(false) }
    val shellFocusRequester = remember { FocusRequester() }
    LaunchedEffect(navigator.destination) {
        shellFocusRequester.requestFocus()
    }
    val colors = LocalViewerColors.current
    val commandPaletteLabel = localizedStringResource(Res.string.command_palette, language)
    val settingsLabel = localizedStringResource(Res.string.settings, language)
    val navigationItems =
        listOf(
            AppDestination.HOME,
            AppDestination.LAYOUT_INSPECTOR,
            AppDestination.SIMPLEPERF,
            AppDestination.PERFETTO,
            AppDestination.MEMORY_PROFILER,
            AppDestination.FRAME_PROFILER,
            AppDestination.STARTUP_PROFILER,
            AppDestination.BATTERY_PROFILER,
            AppDestination.NETWORK_PROFILER,
            AppDestination.GPU_INSPECTOR,
            AppDestination.BENCHMARK_REGRESSION,
            AppDestination.SOURCE_WORKSPACES,
        ).map {
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
                        else -> false
                    }
                }
                .focusable(),
    ) {
        val collapsed = resolvedNavigationRailCollapsed(maxWidth.value.toInt(), navigationRailCollapsed)
        Row(modifier = Modifier.fillMaxSize().background(colors.toolbar)) {
            StudioNavigationRail(
                items = navigationItems,
                selectedDestination = navigator.destination,
                collapsed = collapsed,
                settingsLabel = settingsLabel,
                settingsSelected = navigator.destination == AppDestination.SETTINGS,
                onDestinationSelected = navigator::open,
                onOpenSettings = { navigator.open(AppDestination.SETTINGS) },
            )
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                content()
            }
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
                navigator.open(destination)
                showCommandPalette = false
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StudioNavigationRail(
    items: List<StudioNavigationItem>,
    selectedDestination: AppDestination,
    collapsed: Boolean,
    settingsLabel: String,
    settingsSelected: Boolean,
    onDestinationSelected: (AppDestination) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = LocalViewerColors.current
    val width = if (collapsed) StudioTokens.navigationCollapsedWidth else StudioTokens.navigationExpandedWidth
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .width(width)
                .testTag("studio-navigation-rail")
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
                onClick = { onDestinationSelected(item.destination) },
            )
        }
        Spacer(Modifier.weight(1f))
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
    onClick: () -> Unit,
) {
    if (collapsed) {
        // The collapsed rail is icon-only, so hovering reveals the destination title.
        TooltipArea(tooltip = { RailTooltip(label) }, delayMillis = TOOLTIP_DELAY_MILLIS) {
            RailEntryButton(icon, label, selected, collapsed, onClick)
        }
    } else {
        RailEntryButton(icon, label, selected, collapsed, onClick)
    }
}

@Composable
private fun RailEntryButton(
    icon: DrawableResource,
    label: String,
    selected: Boolean,
    collapsed: Boolean,
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
            Icon(
                painter = painterResource(icon),
                contentDescription = if (collapsed) label else null,
                modifier = Modifier.size(StudioTokens.navigationIconSize),
                tint = iconTint,
            )
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
private fun StudioCommandPalette(
    title: String,
    searchPlaceholder: String,
    noResultsLabel: String,
    items: List<StudioNavigationItem>,
    onDismiss: () -> Unit,
    onDestinationSelected: (AppDestination) -> Unit,
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
            }
        },
        confirmButton = {},
    )
}
