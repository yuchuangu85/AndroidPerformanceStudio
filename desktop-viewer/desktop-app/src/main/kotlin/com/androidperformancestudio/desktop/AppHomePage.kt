package com.androidperformancestudio.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import com.androidperformancestudio.desktop.dashboard.DeviceOverviewSource
import com.androidperformancestudio.desktop.dashboard.StudioDeviceOverview
import com.androidperformancestudio.desktop.dashboard.StudioDeviceSummary
import com.androidperformancestudio.desktop.dashboard.StudioFeature
import com.androidperformancestudio.desktop.dashboard.StudioRecentItem
import com.androidperformancestudio.desktop.dashboard.WorkspaceOverviewRepository
import com.androidperformancestudio.desktop.dashboard.WorkspaceOverviewSnapshot
import com.androidperformancestudio.desktop.shell.navigationIconResource
import com.androidperformancestudio.desktop_app.generated.resources.Res
import com.androidperformancestudio.desktop_app.generated.resources.*
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioCard
import com.androidperformancestudio.ui.studio.StudioDataTable
import com.androidperformancestudio.ui.studio.StudioEmptyState
import com.androidperformancestudio.ui.studio.StudioErrorState
import com.androidperformancestudio.ui.studio.StudioLoadingState
import com.androidperformancestudio.ui.studio.StudioMetricCard
import com.androidperformancestudio.ui.studio.StudioPanel
import com.androidperformancestudio.ui.studio.StudioPanelHeader
import com.androidperformancestudio.ui.studio.StudioStatusChip
import com.androidperformancestudio.ui.studio.StudioStatusTone
import com.androidperformancestudio.ui.studio.StudioTableColumn
import com.androidperformancestudio.ui.studio.StudioTokens
import com.androidperformancestudio.ui_components.generated.resources.Res as UiComponentsRes
import com.androidperformancestudio.ui_components.generated.resources.ic_stat_device
import com.androidperformancestudio.ui_components.generated.resources.ic_stat_history
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource

internal const val HOME_MODULE_CARD_HEIGHT_DP = 78
internal const val HOME_RAIL_WIDTH_DP = 320
internal const val HOME_TWO_COLUMN_MIN_WIDTH_DP = 900

internal fun homeGridColumnCount(availableWidthDp: Int): Int =
    when {
        availableWidthDp >= 820 -> 3
        availableWidthDp >= 560 -> 2
        else -> 1
    }

private val sessionTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/** Phases of a card drag, used to select the lift/settle scale mapping. */
private enum class DragPhase { Idle, Lifting, Dragging, Dropping }

/** Scale the floating card shrinks to at the start of the lift (anticipation). */
private const val LIFT_DIP_SCALE = 0.92f

/** Scale the floating card grows to once lifted (raised above the grid). */
private const val LIFT_RAISED_SCALE = 1.05f

/** Resting shadow elevation (dp) of a card sitting in the grid. */
private const val CARD_REST_SHADOW_DP = 1f

/** Lifted shadow elevation (dp) of the floating card. */
private const val CARD_LIFTED_SHADOW_DP = 12f

private val LiftSpring = spring<Float>(dampingRatio = 0.6f, stiffness = 400f)
private val DropSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 350f)

/**
 * Maps the drag's lift progress (0..1) to the floating card's scale. While lifting/dragging the
 * card grows from [LIFT_DIP_SCALE] to [LIFT_RAISED_SCALE]; while dropping it settles from
 * [LIFT_RAISED_SCALE] back to the resting 1.0 (without re-dipping through the anticipation scale).
 */
private fun liftScaleFor(progress: Float, dropping: Boolean): Float =
    if (dropping) {
        LIFT_RAISED_SCALE + (1f - progress) * (1f - LIFT_RAISED_SCALE)
    } else {
        LIFT_DIP_SCALE + progress * (LIFT_RAISED_SCALE - LIFT_DIP_SCALE)
    }

/** Maps the drag's lift progress (0..1) to the floating card's shadow elevation in dp. */
private fun liftShadowDp(progress: Float): Float =
    CARD_REST_SHADOW_DP + progress * (CARD_LIFTED_SHADOW_DP - CARD_REST_SHADOW_DP)

private fun Instant.sessionLabel(): String = sessionTimestampFormatter.format(this)

private fun homeDatePattern(language: UiLanguage): String =
    if (language == UiLanguage.SIMPLIFIED_CHINESE) "M月d日 EEE" else "MMM d, EEE"

private fun homeDateLocale(language: UiLanguage): Locale =
    if (language == UiLanguage.SIMPLIFIED_CHINESE) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH

/** The description string shown under each module card on the home grid and in the rail tooltip. */
private fun moduleDescriptionResource(destination: AppDestination): StringResource =
    when (destination) {
        AppDestination.LAYOUT_INSPECTOR -> Res.string.inspect_android_view_hierarchies_screenshots_bounds_and_properties
        AppDestination.SIMPLEPERF -> Res.string.simpleperf_based_cpu_sampling_flame_graphs_call_tree_analysis
        AppDestination.PERFETTO -> Res.string.perfetto_system_level_trace_capture_with_scheduling_binder_and_graphic
        AppDestination.MEMORY_PROFILER -> Res.string.heap_dump_capture_object_statistics_and_class_histogram_analysis
        AppDestination.FRAME_PROFILER -> Res.string.capture_online_or_import_gfxinfo_framestats_to_analyze_frame_timing
        AppDestination.STARTUP_PROFILER -> Res.string.cold_warm_startup_breakdown_and_baseline_profile_support
        AppDestination.BATTERY_NETWORK -> Res.string.battery_network_description
        AppDestination.GPU_INSPECTOR -> Res.string.discover_and_launch_android_gpu_inspector_then_index_and_verify
        AppDestination.BENCHMARK_REGRESSION -> Res.string.compare_androidx_benchmark_baselines_and_current_results_with_ci_regre
        AppDestination.SOURCE_WORKSPACES -> Res.string.source_home_description
        AppDestination.REVERSE_NET -> Res.string.reverse_net_home_description
        AppDestination.SCREENCAST -> Res.string.device_screencast_description
        // Non-module destinations have no home card description; fall back to the title resource.
        else -> destination.titleResource
    }

@Composable
internal fun AppHomePage(
    language: UiLanguage,
    homeLayout: HomeLayoutController,
    onOpenSourceWorkspaces: () -> Unit,
    onOpenLayoutInspector: () -> Unit,
    onOpenSimpleperf: () -> Unit,
    onOpenPerfetto: () -> Unit,
    onOpenMemoryProfiler: () -> Unit,
    onOpenFrameProfiler: () -> Unit,
    onOpenStartupProfiler: () -> Unit,
    onOpenBatteryNetwork: () -> Unit,
    onOpenReverseNet: () -> Unit,
    onOpenGpuInspector: () -> Unit,
    onOpenBenchmarkRegression: () -> Unit,
    onOpenScreencast: () -> Unit,
    onOpenRecentArtifact: (StudioRecentItem) -> Unit,
    androidSdkPath: String? = null,
    active: Boolean = true,
) {
    val overviewRepository = remember { WorkspaceOverviewRepository.desktop() }
    val deviceOverviewSource = remember(androidSdkPath) { DeviceOverviewSource(androidSdkPath) }
    var recentSnapshot by remember { mutableStateOf<WorkspaceOverviewSnapshot?>(null) }
    var loadingRecentItems by remember { mutableStateOf(true) }
    var deviceOverview by remember { mutableStateOf<StudioDeviceOverview?>(null) }
    var deviceRefreshRevision by remember { mutableStateOf(0) }
    LaunchedEffect(active) {
        if (active) {
            loadingRecentItems = true
            recentSnapshot = overviewRepository.loadRecent(limit = 6)
            loadingRecentItems = false
        }
    }
    LaunchedEffect(active, deviceOverviewSource, deviceRefreshRevision) {
        if (active) {
            // Keep the current projection visible while refreshing so pressing refresh does not
            // swap the panel to the loading state and back. The first load still shows loading
            // because the state starts as null.
            deviceOverview = deviceOverviewSource.load()
        }
    }
    val openDestination: (AppDestination) -> Unit = { destination ->
        when (destination) {
            AppDestination.SOURCE_WORKSPACES -> onOpenSourceWorkspaces()
            AppDestination.LAYOUT_INSPECTOR -> onOpenLayoutInspector()
            AppDestination.SIMPLEPERF -> onOpenSimpleperf()
            AppDestination.PERFETTO -> onOpenPerfetto()
            AppDestination.MEMORY_PROFILER -> onOpenMemoryProfiler()
            AppDestination.FRAME_PROFILER -> onOpenFrameProfiler()
            AppDestination.STARTUP_PROFILER -> onOpenStartupProfiler()
            AppDestination.BATTERY_NETWORK -> onOpenBatteryNetwork()
            AppDestination.REVERSE_NET -> onOpenReverseNet()
            AppDestination.GPU_INSPECTOR -> onOpenGpuInspector()
            AppDestination.BENCHMARK_REGRESSION -> onOpenBenchmarkRegression()
            AppDestination.SCREENCAST -> onOpenScreencast()
            // Home/Settings/MethodRecording are not home cards.
            else -> {}
        }
    }
    val entries =
        homeLayout.moduleOrder.map { destination ->
            HomeFeatureEntry(
                destination = destination,
                title = localizedStringResource(destination.titleResource, language),
                description = localizedStringResource(moduleDescriptionResource(destination), language),
                onClick = { openDestination(destination) },
            )
        }

    val colors = LocalViewerColors.current
    Surface(modifier = Modifier.fillMaxSize(), color = colors.workspace) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(StudioTokens.compactContentPadding),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.compactContentPadding),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth.value >= HOME_TWO_COLUMN_MIN_WIDTH_DP) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(StudioTokens.compactContentPadding),
                        verticalAlignment = Alignment.Top,
                    ) {
                        HomeMainColumn(
                            homeLayout = homeLayout,
                            entries = entries,
                            deviceOverview = deviceOverview,
                            recentSnapshot = recentSnapshot,
                            loadingRecentItems = loadingRecentItems,
                            language = language,
                            onOpenRecentArtifact = onOpenRecentArtifact,
                            modifier = Modifier.weight(1f),
                        )
                        HomeRailColumn(
                            deviceOverview = deviceOverview,
                            language = language,
                            onRefreshDevices = { deviceRefreshRevision++ },
                            modifier = Modifier.width(HOME_RAIL_WIDTH_DP.dp),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(StudioTokens.compactContentPadding),
                    ) {
                        HomeMainColumn(
                            homeLayout = homeLayout,
                            entries = entries,
                            deviceOverview = deviceOverview,
                            recentSnapshot = recentSnapshot,
                            loadingRecentItems = loadingRecentItems,
                            language = language,
                            onOpenRecentArtifact = onOpenRecentArtifact,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        HomeRailColumn(
                            deviceOverview = deviceOverview,
                            language = language,
                            onRefreshDevices = { deviceRefreshRevision++ },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeMainColumn(
    homeLayout: HomeLayoutController,
    entries: List<HomeFeatureEntry>,
    deviceOverview: StudioDeviceOverview?,
    recentSnapshot: WorkspaceOverviewSnapshot?,
    loadingRecentItems: Boolean,
    language: UiLanguage,
    onOpenRecentArtifact: (StudioRecentItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(StudioTokens.compactContentPadding),
    ) {
        HomeMetrics(
            deviceOverview = deviceOverview,
            recentSnapshot = recentSnapshot,
            loadingRecentItems = loadingRecentItems,
            language = language,
        )
        HomeSection(title = localizedStringResource(Res.string.home_analysis_modules, language)) {
            HomeModuleGrid(
                controller = homeLayout,
                entries = entries,
                language = language,
            )
        }
        HomeRecentSessions(
            recentSnapshot = recentSnapshot,
            loadingRecentItems = loadingRecentItems,
            language = language,
            onOpenRecentArtifact = onOpenRecentArtifact,
        )
    }
}

@Composable
private fun HomeRailColumn(
    deviceOverview: StudioDeviceOverview?,
    language: UiLanguage,
    onRefreshDevices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(StudioTokens.compactContentPadding),
    ) {
        HomeDateCard(language)
        HomeDeviceSummary(
            overview = deviceOverview,
            language = language,
            onRefresh = onRefreshDevices,
        )
    }
}

@Composable
private fun HomeMetrics(
    deviceOverview: StudioDeviceOverview?,
    recentSnapshot: WorkspaceOverviewSnapshot?,
    loadingRecentItems: Boolean,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    val devicesValue = (deviceOverview as? StudioDeviceOverview.Available)?.connectedCount?.toString() ?: "—"
    val recentValue =
        recentSnapshot
            ?.takeIf { !loadingRecentItems && it.unavailableSourceCount < it.sourceCount }
            ?.items
            ?.size
            ?.toString()
            ?: "—"
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(StudioTokens.compactContentPadding),
    ) {
        StudioMetricCard(
            label = localizedStringResource(Res.string.connected_devices, language),
            value = devicesValue,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            icon = UiComponentsRes.drawable.ic_stat_device,
            tone = colors.info,
        )
        StudioMetricCard(
            label = localizedStringResource(Res.string.recent_artifacts_shown, language),
            value = recentValue,
            supportingText = localizedStringResource(Res.string.recent_artifacts_scope, language),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            icon = UiComponentsRes.drawable.ic_stat_history,
            tone = colors.accent,
        )
    }
}

@Composable
private fun HomeSection(
    title: String,
    content: @Composable () -> Unit,
) {
    val colors = LocalViewerColors.current
    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap)) {
        Text(
            text = title,
            color = colors.primaryText,
            fontSize = ViewerTypography.subsectionTitle.fontSize,
            lineHeight = ViewerTypography.subsectionTitle.lineHeight,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

@Composable
private fun HomeModuleGrid(
    controller: HomeLayoutController,
    entries: List<HomeFeatureEntry>,
    language: UiLanguage,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columnCount = homeGridColumnCount(maxWidth.value.toInt())
        ReorderableGrid(
            items = controller.moduleOrder,
            key = { destination -> destination.name },
            columnCount = columnCount,
            cardHeightDp = HOME_MODULE_CARD_HEIGHT_DP,
            onMove = { from, to -> controller.moveModule(from, to) },
            onTap = { index -> entries.getOrNull(index)?.onClick?.invoke() },
            itemContent = { index, _ ->
                FeatureEntryCard(
                    entry = entries[index],
                    modifier = Modifier.fillMaxWidth().height(HOME_MODULE_CARD_HEIGHT_DP.dp),
                )
            },
        )
    }
}

/**
 * A responsive grid whose items the user can long-press-drag to reorder, phone-style: as the
 * dragged card moves, the others shuffle aside in real time via [Modifier.animateItem].
 *
 * Pointer handling lives on a transparent overlay (a stable coordinate space), so the dragged
 * card follows the pointer across reorders without node-local-coordinate drift. Taps route to
 * [onTap]. The column count is frozen for the duration of a drag so the grid does not reflow
 * mid-gesture.
 *
 * While a drag is in progress the lifted card is rendered as a floating overlay above the grid
 * (raised scale + elevated shadow, tracking the pointer), and its original grid slot shows a
 * dashed [DraggedSlotPlaceholder] that rides [Modifier.animateItem] as the grid shuffles. A lift
 * animation plays on long-press (anticipation shrink then grow, in parallel with pointer-follow)
 * and a settle animation plays on drop.
 */
@Composable
private fun <T> ReorderableGrid(
    items: SnapshotStateList<T>,
    key: (T) -> Any,
    columnCount: Int,
    cardHeightDp: Int,
    onMove: (from: Int, to: Int) -> Unit,
    onTap: (index: Int) -> Unit,
    itemContent: @Composable (index: Int, item: T) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (columnCount <= 0) return
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    var grabLocal by remember { mutableStateOf(Offset.Zero) }
    var frozenColumns by remember { mutableStateOf<Int?>(null) }
    var phase by remember { mutableStateOf(DragPhase.Idle) }
    val liftProgress = remember { Animatable(0f) }
    // The pointerInput blocks below are keyed on stable values (the items list instance and the
    // column count), so their coroutines launch once and would otherwise capture the first
    // onTap/onMove closures. After a drag mutates `items`, the cards re-render with the new order
    // but the tap handler would still call the stale closure — opening the module that *used to*
    // be at the tapped slot. rememberUpdatedState keeps the handlers live across recompositions.
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnMove by rememberUpdatedState(onMove)
    val cols = frozenColumns ?: columnCount
    val gapDp = StudioTokens.compactContentPadding
    val totalSlots = items.size
    val rows = if (cols <= 0) 0 else (totalSlots + cols - 1) / cols
    val gridHeightDp: Dp = (cardHeightDp * rows).dp + gapDp * (rows - 1)

    fun slotAt(position: Offset) =
        gridState.layoutInfo.visibleItemsInfo.firstOrNull { slot ->
            position.x >= slot.offset.x &&
                position.x <= slot.offset.x + slot.size.width &&
                position.y >= slot.offset.y &&
                position.y <= slot.offset.y + slot.size.height
        }

    fun reorderableRects() =
        gridState.layoutInfo.visibleItemsInfo
            .map { IntRect(it.offset.x, it.offset.y, it.offset.x + it.size.width, it.offset.y + it.size.height) }

    Box(modifier = modifier.fillMaxWidth().height(gridHeightDp)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(gapDp),
            verticalArrangement = Arrangement.spacedBy(gapDp),
            userScrollEnabled = false,
        ) {
            itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
                // The dragged slot keeps its place in the grid (so drop-index math over
                // visibleItemsInfo stays valid) but renders a dashed placeholder instead of the
                // card; the lifted card is drawn as an overlay above. Both the placeholder and the
                // non-dragged cards use animateItem() so the placeholder shuffles along with the rest.
                val dragged = draggedIndex == index
                Box(modifier = Modifier.animateItem()) {
                    if (dragged) {
                        DraggedSlotPlaceholder(
                            modifier = Modifier.fillMaxWidth().height(cardHeightDp.dp),
                        )
                    } else {
                        itemContent(index, item)
                    }
                }
            }
        }

        // Floating lifted card: rendered above the grid only while a drag is in progress. It is
        // sized to the dragged slot's width and positioned at dragPosition - grabLocal (the outer
        // Box, the grid, and the pointer overlay share one coordinate origin since the grid is not
        // scrolled and exactly fills the Box). graphicsLayer drives the lift/settle scale + shadow.
        val liftedIndex = draggedIndex
        if (liftedIndex != null) {
            val slot =
                gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == liftedIndex }
            if (slot != null) {
                val slotWidthDp = with(density) { slot.size.width.toDp() }
                val dropping = phase == DragPhase.Dropping
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (dragPosition.x - grabLocal.x).roundToInt(),
                                (dragPosition.y - grabLocal.y).roundToInt(),
                            )
                        }
                        .width(slotWidthDp)
                        .height(cardHeightDp.dp)
                        .graphicsLayer {
                            val scale = liftScaleFor(liftProgress.value, dropping)
                            scaleX = scale
                            scaleY = scale
                            shadowElevation = with(density) { liftShadowDp(liftProgress.value).dp.toPx() }
                        }
                        .zIndex(1f),
                ) {
                    itemContent(liftedIndex, items[liftedIndex])
                }
            }
        }

        // Transparent overlay: single pointer authority over the grid. Taps open the item under the
        // pointer; a long-press starts a drag. Cards themselves carry no click handler, so the
        // overlay never has to compete with them for pointer events.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(items, columnCount) {
                        detectTapGestures { offset -> slotAt(offset)?.let { currentOnTap(it.index) } }
                    }
                    .pointerInput(items, columnCount) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                val slot = slotAt(offset)
                                if (slot != null) {
                                    draggedIndex = slot.index
                                    frozenColumns = columnCount
                                    grabLocal = Offset(
                                        offset.x - slot.offset.x,
                                        offset.y - slot.offset.y,
                                    )
                                    dragPosition = offset
                                    // Lift runs in parallel with pointer-follow: the card begins
                                    // tracking immediately while the shrink-then-grow plays over
                                    // the first ~200ms, so there is no dead-time after the press.
                                    phase = DragPhase.Lifting
                                    scope.launch {
                                        liftProgress.animateTo(1f, LiftSpring)
                                        if (phase == DragPhase.Lifting) phase = DragPhase.Dragging
                                    }
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                dragPosition = change.position
                                if (phase == DragPhase.Lifting) phase = DragPhase.Dragging
                                val di = draggedIndex
                                if (di != null) {
                                    val target =
                                        dropIndexForGrid(
                                            reorderableRects(),
                                            change.position.x.toInt(),
                                            change.position.y.toInt(),
                                            cols,
                                        )
                                    if (target != di && target in items.indices) {
                                        currentOnMove(di, target)
                                        draggedIndex = target
                                    }
                                }
                            },
                            onDragEnd = {
                                phase = DragPhase.Dropping
                                scope.launch {
                                    liftProgress.animateTo(0f, DropSpring)
                                    draggedIndex = null
                                    frozenColumns = null
                                    phase = DragPhase.Idle
                                }
                            },
                            onDragCancel = {
                                phase = DragPhase.Dropping
                                scope.launch {
                                    liftProgress.animateTo(0f, DropSpring)
                                    draggedIndex = null
                                    frozenColumns = null
                                    phase = DragPhase.Idle
                                }
                            },
                        )
                    },
        )
    }
}

/**
 * The dashed, faintly-filled placeholder shown in a card's grid slot while that card is being
 * dragged. Same size as the card; rides [Modifier.animateItem] so it shuffles with the grid.
 */
@Composable
private fun DraggedSlotPlaceholder(modifier: Modifier = Modifier) {
    val colors = LocalViewerColors.current
    val accent = colors.accent
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(StudioTokens.cardRadius))
            .background(accent.copy(alpha = 0.06f))
            .drawBehind {
                drawRoundRect(
                    color = accent.copy(alpha = 0.5f),
                    cornerRadius = CornerRadius(StudioTokens.cardRadius.toPx(), StudioTokens.cardRadius.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f),
                    ),
                )
            },
    )
}

@Composable
private fun HomeRecentSessions(
    recentSnapshot: WorkspaceOverviewSnapshot?,
    loadingRecentItems: Boolean,
    language: UiLanguage,
    onOpenRecentArtifact: (StudioRecentItem) -> Unit,
) {
    val colors = LocalViewerColors.current
    StudioDataTable(
        columns =
            listOf(
                StudioTableColumn(localizedStringResource(Res.string.recent_column_name, language), 2.2f),
                StudioTableColumn(localizedStringResource(Res.string.recent_column_package, language), 1.6f),
                StudioTableColumn(localizedStringResource(Res.string.recent_column_type, language), 0.85f),
                StudioTableColumn(localizedStringResource(Res.string.recent_column_started, language), 1.3f),
                StudioTableColumn(localizedStringResource(Res.string.recent_column_action, language), 0.8f),
            ),
        title = localizedStringResource(Res.string.home_recent_sessions, language),
        modifier = Modifier.fillMaxWidth(),
    ) {
        when {
            loadingRecentItems -> StudioLoadingState(
                title = localizedStringResource(Res.string.home_recent_sessions, language),
                detail = localizedStringResource(Res.string.loading_recent_artifacts, language),
            )
            recentSnapshot != null && recentSnapshot.unavailableSourceCount == recentSnapshot.sourceCount -> StudioErrorState(
                title = localizedStringResource(Res.string.recent_indexes_unavailable, language),
                detail = localizedStringResource(Res.string.recent_indexes_unavailable_hint, language),
            )
            recentSnapshot?.items?.isEmpty() == true && recentSnapshot?.unavailableSourceCount == 0 -> StudioEmptyState(
                title = localizedStringResource(Res.string.no_recent_artifacts, language),
                detail = localizedStringResource(Res.string.recent_artifacts_hint, language),
            )
            else -> {
                if ((recentSnapshot?.unavailableSourceCount ?: 0) > 0) {
                    Text(
                        text = localizedStringResource(Res.string.some_recent_indexes_unavailable, language),
                        color = colors.secondaryText,
                        fontSize = ViewerTypography.secondary.fontSize,
                        lineHeight = ViewerTypography.secondary.lineHeight,
                    )
                }
                recentSnapshot?.items.orEmpty().forEach { item ->
                    RecentSessionRow(item = item, language = language, onOpen = { onOpenRecentArtifact(item) })
                }
            }
        }
    }
}

@Composable
private fun RecentSessionRow(
    item: StudioRecentItem,
    language: UiLanguage,
    onOpen: () -> Unit,
) {
    val colors = LocalViewerColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = StudioTokens.compactContentPadding, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.title,
            modifier = Modifier.weight(2.2f),
            color = colors.primaryText,
            fontSize = ViewerTypography.body.fontSize,
            lineHeight = ViewerTypography.body.lineHeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.packageName ?: "—",
            modifier = Modifier.weight(1.6f),
            color = colors.secondaryText,
            fontSize = ViewerTypography.bodyCompact.fontSize,
            lineHeight = ViewerTypography.bodyCompact.lineHeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(modifier = Modifier.weight(0.85f)) {
            StudioStatusChip(label = item.featureLabel(language), tone = StudioStatusTone.NEUTRAL)
        }
        Text(
            text = item.capturedAt?.sessionLabel() ?: "—",
            modifier = Modifier.weight(1.3f),
            color = colors.secondaryText,
            fontSize = ViewerTypography.bodyCompact.fontSize,
            lineHeight = ViewerTypography.bodyCompact.lineHeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(modifier = Modifier.weight(0.8f), contentAlignment = Alignment.CenterEnd) {
            // A plain clickable label instead of a TextButton: Material's 40 dp minimum height was
            // what made every session row so tall.
            Text(
                text = localizedStringResource(Res.string.open_artifact, language),
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(StudioTokens.smallRadius))
                        .clickable(onClick = onOpen)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                color = colors.accent,
                fontSize = ViewerTypography.bodyCompact.fontSize,
                lineHeight = ViewerTypography.bodyCompact.lineHeight,
            )
        }
    }
}

@Composable
private fun StudioRecentItem.featureLabel(language: UiLanguage): String =
    localizedStringResource(
        when (feature) {
            StudioFeature.LAYOUT -> Res.string.recent_feature_layout
            StudioFeature.CPU -> Res.string.recent_feature_cpu
            StudioFeature.TRACE -> Res.string.recent_feature_trace
            StudioFeature.MEMORY -> Res.string.recent_feature_memory
        },
        language,
    )

@Composable
private fun HomeDateCard(language: UiLanguage) {
    val colors = LocalViewerColors.current
    val locale = remember(language) { homeDateLocale(language) }
    val formatter = remember(locale) { DateTimeFormatter.ofPattern(homeDatePattern(language), locale) }
    StudioPanel(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = LocalDate.now().format(formatter),
            color = colors.primaryText,
            fontSize = ViewerTypography.sectionTitle.fontSize,
            lineHeight = ViewerTypography.sectionTitle.lineHeight,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = localizedStringResource(Res.string.home_focus_on_performance, language),
            color = colors.secondaryText,
            fontSize = ViewerTypography.secondary.fontSize,
            lineHeight = ViewerTypography.secondary.lineHeight,
        )
    }
}

@Composable
private fun HomeDeviceSummary(
    overview: StudioDeviceOverview?,
    language: UiLanguage,
    onRefresh: () -> Unit,
) {
    val colors = LocalViewerColors.current
    val title = localizedStringResource(Res.string.connected_devices, language)
    when (overview) {
        null -> StudioLoadingState(
            title = title,
            detail = localizedStringResource(Res.string.loading_devices, language),
        )
        StudioDeviceOverview.Unavailable -> StudioErrorState(
            title = title,
            detail = localizedStringResource(Res.string.device_discovery_unavailable, language),
            retryLabel = localizedStringResource(Res.string.refresh_devices, language),
            onRetry = onRefresh,
        )
        is StudioDeviceOverview.Available ->
            StudioPanel(modifier = Modifier.fillMaxWidth()) {
                StudioPanelHeader(title = title) {
                    TextButton(onClick = onRefresh) {
                        Text(
                            text = localizedStringResource(Res.string.refresh_devices, language),
                            color = colors.accent,
                            fontSize = ViewerTypography.bodyCompact.fontSize,
                            lineHeight = ViewerTypography.bodyCompact.lineHeight,
                        )
                    }
                }
                if (overview.devices.isEmpty()) {
                    Text(
                        text = localizedStringResource(Res.string.no_connected_devices, language),
                        color = colors.secondaryText,
                        fontSize = ViewerTypography.body.fontSize,
                        lineHeight = ViewerTypography.body.lineHeight,
                    )
                } else {
                    overview.devices.forEach { device -> DeviceRow(device = device, language = language) }
                }
            }
    }
}

@Composable
private fun DeviceRow(
    device: StudioDeviceSummary,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
    ) {
        Icon(
            painter = painterResource(UiComponentsRes.drawable.ic_stat_device),
            contentDescription = null,
            modifier = Modifier.size(StudioTokens.navigationIconSize),
            tint = if (device.online) colors.accent else colors.mutedText,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.displayName,
                color = colors.primaryText,
                fontSize = ViewerTypography.body.fontSize,
                lineHeight = ViewerTypography.body.lineHeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = device.serial,
                color = colors.secondaryText,
                fontSize = ViewerTypography.secondary.fontSize,
                lineHeight = ViewerTypography.secondary.lineHeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StudioStatusChip(
            label = localizedStringResource(device.state.labelResource(), language),
            tone = if (device.online) StudioStatusTone.SUCCESS else StudioStatusTone.WARNING,
        )
    }
}

private fun AdbDeviceState.labelResource() =
    when (this) {
        AdbDeviceState.ONLINE -> Res.string.device_online
        AdbDeviceState.OFFLINE -> Res.string.device_offline
        AdbDeviceState.UNAUTHORIZED -> Res.string.device_unauthorized
        AdbDeviceState.NO_PERMISSIONS -> Res.string.device_no_permissions
        AdbDeviceState.UNKNOWN -> Res.string.device_unknown
    }

private data class HomeFeatureEntry(
    val destination: AppDestination,
    val title: String,
    val description: String,
    val onClick: () -> Unit,
)

@Composable
private fun FeatureEntryCard(
    entry: HomeFeatureEntry,
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    val shape = RoundedCornerShape(StudioTokens.cardRadius)
    StudioCard(
        modifier = modifier.shadow(elevation = 1.dp, shape = shape, clip = false),
        containerColor = colors.panel,
        borderColor = colors.border.copy(alpha = 0.72f),
        contentPadding = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(colors.accent.copy(alpha = if (colors.isDark) 0.22f else 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(entry.destination.navigationIconResource),
                    contentDescription = null,
                    modifier = Modifier.size(StudioTokens.navigationIconSize),
                    tint = colors.accent,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = entry.title,
                    color = colors.primaryText,
                    fontSize = ViewerTypography.cardTitle.fontSize,
                    lineHeight = ViewerTypography.cardTitle.lineHeight,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.description,
                    color = colors.secondaryText,
                    fontSize = ViewerTypography.secondary.fontSize,
                    lineHeight = ViewerTypography.secondary.lineHeight,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
