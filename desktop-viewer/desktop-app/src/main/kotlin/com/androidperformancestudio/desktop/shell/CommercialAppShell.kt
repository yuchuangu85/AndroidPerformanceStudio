@file:Suppress("FunctionNaming")

package com.androidperformancestudio.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.androidperformancestudio.desktop.titleResource
import com.androidperformancestudio.desktop_app.generated.resources.Res
import com.androidperformancestudio.desktop_app.generated.resources.android_performance_studio
import com.androidperformancestudio.desktop_app.generated.resources.command_palette
import com.androidperformancestudio.desktop_app.generated.resources.collapse_navigation
import com.androidperformancestudio.desktop_app.generated.resources.expand_navigation
import com.androidperformancestudio.desktop_app.generated.resources.no_active_artifact
import com.androidperformancestudio.desktop_app.generated.resources.no_matching_commands
import com.androidperformancestudio.desktop_app.generated.resources.search_commands
import com.androidperformancestudio.desktop_app.generated.resources.workspace_context_unavailable
import com.androidperformancestudio.desktop_app.generated.resources.settings
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioStatusChip
import com.androidperformancestudio.ui.studio.StudioStatusTone
import com.androidperformancestudio.ui.studio.StudioSearchField
import com.androidperformancestudio.ui.studio.StudioTokens

internal const val NAVIGATION_COLLAPSE_BREAKPOINT_DP: Int = 1280

internal fun navigationRailCollapsedFor(availableWidthDp: Int): Boolean =
    availableWidthDp < NAVIGATION_COLLAPSE_BREAKPOINT_DP

internal fun resolvedNavigationRailCollapsed(availableWidthDp: Int, userCollapsed: Boolean?): Boolean =
    userCollapsed ?: navigationRailCollapsedFor(availableWidthDp)

internal data class WorkspaceContext(
    val deviceLabel: String? = null,
    val packageName: String? = null,
    val processName: String? = null,
    val artifactLabel: String? = null,
)

private data class StudioNavigationItem(
    val destination: AppDestination,
    val label: String,
)

/**
 * Phase-2 shell chrome. It intentionally projects only app navigation and read-only context; all
 * feature state remains in the retained destination composed by [content].
 */
@Composable
internal fun CommercialAppShell(
    navigator: AppNavigator,
    language: UiLanguage,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    workspaceContext: WorkspaceContext = WorkspaceContext(),
    content: @Composable BoxScope.() -> Unit,
) {
    var userCollapsed by remember { mutableStateOf<Boolean?>(null) }
    var showCommandPalette by remember { mutableStateOf(false) }
    val shellFocusRequester = remember { FocusRequester() }
    LaunchedEffect(navigator.destination) {
        shellFocusRequester.requestFocus()
    }
    val colors = LocalViewerColors.current
    val destinationTitle = localizedStringResource(navigator.destination.titleResource, language)
    val productName = localizedStringResource(Res.string.android_performance_studio, language)
    val commandPaletteLabel = localizedStringResource(Res.string.command_palette, language)
    val settingsLabel = localizedStringResource(Res.string.settings, language)
    val unavailableContextLabel = localizedStringResource(Res.string.workspace_context_unavailable, language)
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
        ).map { StudioNavigationItem(it, localizedStringResource(it.titleResource, language)) }
    val commandItems =
        (navigationItems +
            listOf(AppDestination.SOURCE_WORKSPACES, AppDestination.METHOD_RECORDING)
                .map { StudioNavigationItem(it, localizedStringResource(it.titleResource, language)) })
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
                            onOpenSettings()
                            true
                        }
                        else -> false
                    }
                }
                .focusable(),
    ) {
        val collapsed = resolvedNavigationRailCollapsed(maxWidth.value.toInt(), userCollapsed)
        Column(modifier = Modifier.fillMaxSize().background(colors.canvasBackground)) {
            StudioTopBar(
                productName = productName,
                destinationTitle = destinationTitle,
                commandPaletteLabel = commandPaletteLabel,
                settingsLabel = settingsLabel,
                toggleNavigationLabel =
                    localizedStringResource(
                        if (collapsed) Res.string.expand_navigation else Res.string.collapse_navigation,
                        language,
                    ),
                workspaceContext = workspaceContext,
                unavailableContextLabel = unavailableContextLabel,
                onToggleNavigation = { userCollapsed = !collapsed },
                onOpenCommandPalette = { showCommandPalette = true },
                onOpenSettings = onOpenSettings,
            )
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                StudioNavigationRail(
                    items = navigationItems,
                    selectedDestination = navigator.destination,
                    collapsed = collapsed,
                    collapseActionLabel =
                        localizedStringResource(
                            if (collapsed) Res.string.expand_navigation else Res.string.collapse_navigation,
                            language,
                        ),
                    onToggleCollapse = { userCollapsed = !collapsed },
                    onDestinationSelected = navigator::open,
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
                navigator.open(destination)
                showCommandPalette = false
            },
        )
    }
}

@Composable
private fun StudioTopBar(
    productName: String,
    destinationTitle: String,
    commandPaletteLabel: String,
    settingsLabel: String,
    toggleNavigationLabel: String,
    workspaceContext: WorkspaceContext,
    unavailableContextLabel: String,
    onToggleNavigation: () -> Unit,
    onOpenCommandPalette: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = LocalViewerColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(StudioTokens.topBarHeight)
                .background(colors.toolbar)
                .border(width = StudioTokens.borderWidth, color = colors.border)
                .padding(horizontal = StudioTokens.contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
    ) {
        TextButton(
            modifier = Modifier.semantics { contentDescription = toggleNavigationLabel },
            onClick = onToggleNavigation,
        ) {
            Text(text = "☰", color = colors.primaryText, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = productName,
                color = colors.primaryText,
                fontSize = ViewerTypography.body.fontSize,
                lineHeight = ViewerTypography.body.lineHeight,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = destinationTitle,
                color = colors.secondaryText,
                fontSize = ViewerTypography.secondary.fontSize,
                lineHeight = ViewerTypography.secondary.lineHeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        workspaceContext.contextLabels().forEach { label ->
            StudioStatusChip(label = label, tone = StudioStatusTone.NEUTRAL)
        }
        workspaceContext.artifactLabel?.let { artifact ->
            StudioStatusChip(label = artifact, tone = StudioStatusTone.INFO)
        } ?: StudioStatusChip(label = unavailableContextLabel, tone = StudioStatusTone.NEUTRAL)
        TextButton(onClick = onOpenCommandPalette) {
            Text(text = commandPaletteLabel, color = colors.accent, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        }
        TextButton(
            modifier = Modifier.semantics { contentDescription = settingsLabel },
            onClick = onOpenSettings,
        ) {
            Text(text = "⚙", color = colors.primaryText, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        }
    }
}

private fun WorkspaceContext.contextLabels(): List<String> =
    listOfNotNull(deviceLabel, packageName, processName)

@Composable
private fun StudioNavigationRail(
    items: List<StudioNavigationItem>,
    selectedDestination: AppDestination,
    collapsed: Boolean,
    collapseActionLabel: String,
    onToggleCollapse: () -> Unit,
    onDestinationSelected: (AppDestination) -> Unit,
) {
    val colors = LocalViewerColors.current
    val width = if (collapsed) StudioTokens.navigationCollapsedWidth else StudioTokens.navigationExpandedWidth
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
            val selected = item.destination == selectedDestination
            TextButton(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(StudioTokens.navigationItemHeight)
                        .clip(RoundedCornerShape(StudioTokens.smallRadius))
                        .background(if (selected) colors.selectedRow else colors.transparent)
                        .semantics { contentDescription = item.label },
                onClick = { onDestinationSelected(item.destination) },
            ) {
                Text(
                    text = if (collapsed) item.label.take(1) else item.label,
                    modifier = Modifier.fillMaxWidth(),
                    color = if (selected) colors.accent else colors.primaryText,
                    fontSize = ViewerTypography.bodyCompact.fontSize,
                    lineHeight = ViewerTypography.bodyCompact.lineHeight,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = collapseActionLabel },
            onClick = onToggleCollapse,
        ) {
            Text(
                text = if (collapsed) "›" else "‹",
                color = colors.secondaryText,
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun StudioStatusBar(
    destinationTitle: String,
    workspaceContext: WorkspaceContext,
    noActiveArtifactLabel: String,
) {
    val colors = LocalViewerColors.current
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
