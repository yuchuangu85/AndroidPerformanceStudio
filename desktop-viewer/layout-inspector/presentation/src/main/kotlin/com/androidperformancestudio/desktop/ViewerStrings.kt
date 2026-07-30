package com.androidperformancestudio.desktop

import androidx.compose.runtime.staticCompositionLocalOf
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.presentation.generated.resources.ViewerRes
import org.jetbrains.compose.resources.StringResource
import kotlin.math.roundToInt

/**
 * Language-aware string facade backed by Compose Multiplatform Resources.
 *
 * Simple strings are loaded from [composeResources/values/strings.xml] (English)
 * and [composeResources/values-zh/strings.xml] (Simplified Chinese) via language qualifiers.
 *
 * The public API remains identical to the previous hardcoded version so that all
 * existing Compose and presenter call sites compile without changes.
 *
 * Complex methods (finding messages, detail label lookups, etc.) continue to use
 * parameterized resource strings with runtime arguments.
 */
internal class ViewerStrings private constructor(
    val language: UiLanguage,
) {
    // ---- Simple strings (loaded from resources) ----

    val settings: String get() = str(ViewerRes.settings)
    val backToHome: String get() = str(ViewerRes.back_to_home)
    val layoutInspectorSettings: String get() = str(ViewerRes.layout_inspector_settings)
    val layoutInspector: String get() = str(ViewerRes.layout_inspector)
    val settingsSaveFailed: String get() = str(ViewerRes.settings_save_failed)
    val viewAndHierarchy: String get() = str(ViewerRes.view_and_hierarchy)
    val showHierarchyIds: String get() = str(ViewerRes.show_hierarchy_ids)
    val canvasBorderColors: String get() = str(ViewerRes.canvas_border_colors)
    val defaultViewBoundsColor: String get() = str(ViewerRes.default_view_bounds_color)
    val hoveredViewBoundsColor: String get() = str(ViewerRes.hovered_view_bounds_color)
    val selectedViewBoundsColor: String get() = str(ViewerRes.selected_view_bounds_color)
    val captureArchive: String get() = str(ViewerRes.capture_archive)
    val layoutSnapshotArchiveLimit: String get() = str(ViewerRes.layout_snapshot_archive_limit)
    val layoutSnapshotArchiveLimitHint: String get() = str(ViewerRes.layout_snapshot_archive_limit_hint)
    val reset: String get() = str(ViewerRes.reset)
    val normal: String get() = str(ViewerRes.normal)
    val hovered: String get() = str(ViewerRes.hovered)
    val selected: String get() = str(ViewerRes.selected)
    val canvasHitTestOrder: String get() = str(ViewerRes.canvas_hit_test_order)
    val smallAreaFirst: String get() = str(ViewerRes.small_area_first)
    val zOrder: String get() = str(ViewerRes.z_order)
    val actions: String get() = str(ViewerRes.actions)
    val view: String get() = str(ViewerRes.view)
    val file: String get() = str(ViewerRes.file)
    val importArchive: String get() = str(ViewerRes.import_archive)
    val importScreenshot: String get() = str(ViewerRes.import_screenshot)
    val exportArchive: String get() = str(ViewerRes.export_archive)
    val openRecent: String get() = str(ViewerRes.sp_layout_inspector_menu_open_recent)
    val noRecentArchives: String get() = str(ViewerRes.sp_layout_inspector_recent_empty)
    val clearRecentMenu: String get() = str(ViewerRes.sp_layout_inspector_recent_clear_menu)
    val chooseArchiveToImport: String get() = str(ViewerRes.choose_archive_to_import)
    val chooseArchiveExportFile: String get() = str(ViewerRes.choose_archive_export_file)
    val chooseScreenshotToImport: String get() = str(ViewerRes.choose_screenshot_to_import)
    val importArchiveSucceededTitle: String get() = str(ViewerRes.import_archive_succeeded_title)
    val importArchiveFailedTitle: String get() = str(ViewerRes.import_archive_failed_title)
    val importScreenshotSucceededTitle: String get() = str(ViewerRes.import_screenshot_succeeded_title)
    val importScreenshotFailedTitle: String get() = str(ViewerRes.import_screenshot_failed_title)
    val exportArchiveSucceededTitle: String get() = str(ViewerRes.export_archive_succeeded_title)
    val exportArchiveFailedTitle: String get() = str(ViewerRes.export_archive_failed_title)
    val hideInvisibleHierarchyViews: String get() = str(ViewerRes.hide_invisible_hierarchy_views)
    val hideInvisibleFindings: String get() = str(ViewerRes.hide_invisible_findings)
    val hideHierarchyIndices: String get() = str(ViewerRes.hide_hierarchy_indices)
    val showHierarchyLayerVisibilityButtons: String get() = str(ViewerRes.show_hierarchy_layer_visibility_buttons)
    val showVisibleViewBounds: String get() = str(ViewerRes.show_visible_view_bounds)
    val dismiss: String get() = str(ViewerRes.dismiss)
    val autoScan: String get() = str(ViewerRes.auto_scan)
    val autoDevice: String get() = str(ViewerRes.auto_device)
    val captureTarget: String get() = str(ViewerRes.capture_target)
    val window: String get() = str(ViewerRes.window)
    val selectWindow: String get() = str(ViewerRes.select_window)
    val refresh: String get() = str(ViewerRes.refresh)
    val refreshOnce: String get() = str(ViewerRes.refresh_once)
    val hierarchy: String get() = str(ViewerRes.hierarchy)
    val canvas: String get() = str(ViewerRes.canvas)
    val properties: String get() = str(ViewerRes.properties)
    val findings: String get() = str(ViewerRes.findings)
    val runAiAnalysis: String get() = str(ViewerRes.run_ai_analysis)
    val aiAnalysisRunning: String get() = str(ViewerRes.ai_analysis_running)
    val noLiveFrame: String get() = str(ViewerRes.no_live_frame)
    val waitingForFrame: String get() = str(ViewerRes.waiting_for_frame)
    val zoomOutPreview: String get() = str(ViewerRes.zoom_out_preview)
    val zoomInPreview: String get() = str(ViewerRes.zoom_in_preview)
    val noFindings: String get() = str(ViewerRes.no_findings)
    val findInMemoryProfiler: String get() = str(ViewerRes.find_in_memory_profiler)
    val timelineLiveCapture: String get() = str(ViewerRes.timeline_live_capture)
    val noApp: String get() = str(ViewerRes.no_app)
    val noAvailableWindows: String get() = str(ViewerRes.no_available_windows)
    val noSnapshotLoaded: String get() = str(ViewerRes.no_snapshot_loaded)
    val disconnected: String get() = str(ViewerRes.disconnected)
    val connecting: String get() = str(ViewerRes.connecting)
    val live: String get() = str(ViewerRes.live)
    val offlineArchive: String get() = str(ViewerRes.offline_archive)
    val connectionFailed: String get() = str(ViewerRes.connection_failed)
    val unavailable: String get() = str(ViewerRes.unavailable)
    val appOnlyOn: String get() = str(ViewerRes.app_only_on)
    val appOnlyOff: String get() = str(ViewerRes.app_only_off)
    val smallAreaHitTesting: String get() = str(ViewerRes.small_area_hit_testing)
    val zOrderHitTesting: String get() = str(ViewerRes.z_order_hit_testing)
    val hideLayer: String get() = str(ViewerRes.hide_layer)
    val showLayer: String get() = str(ViewerRes.show_layer)
    val toggleLayerVisibility: String get() = str(ViewerRes.toggle_layer_visibility)
    val searchHierarchy: String get() = str(ViewerRes.search_hierarchy)
    val searchPrevious: String get() = str(ViewerRes.search_previous)
    val searchNext: String get() = str(ViewerRes.search_next)
    val searchNoMatch: String get() = str(ViewerRes.search_no_match)
    val noHighOverlapPairsStr: String get() = str(ViewerRes.no_high_overlap_pairs)
    val timelineBaseline: String get() = str(ViewerRes.timeline_baseline)

    // ---- Parameterized / format strings ----

    fun aiAnalysisSummary(summary: String): String = fmt(ViewerRes.ai_analysis_summary, summary)
    fun aiAnalysisFailed(message: String): String = fmt(ViewerRes.ai_analysis_failed, message)

    fun hiddenLayerSummary(count: Int): String = fmt(ViewerRes.hidden_layer_summary, count)
    fun snapshotArchiveLimit(sizeMiB: Int): String = fmt(ViewerRes.snapshot_archive_limit_value, sizeMiB)

    fun archiveImportSucceeded(path: String): String = fmt(ViewerRes.archive_import_succeeded, path)
    fun archiveImportFailed(message: String): String = fmt(ViewerRes.archive_import_failed, message)
    fun screenshotImportSucceeded(path: String): String = fmt(ViewerRes.screenshot_import_succeeded, path)
    fun screenshotImportFailed(message: String): String = fmt(ViewerRes.screenshot_import_failed, message)

    fun archiveExportSucceeded(
        path: String,
        rawArtifactsIncluded: Boolean,
    ): String =
        if (rawArtifactsIncluded) {
            fmt(ViewerRes.archive_export_succeeded, path)
        } else {
            fmt(ViewerRes.archive_export_succeeded_no_attachments, path)
        }

    fun archiveExportFailed(message: String): String = fmt(ViewerRes.archive_export_failed, message)

    fun archiveLimitValue(limits: CaptureArchiveLimits): String =
        fmt(ViewerRes.archive_limit_value, limits.maxSnapshotSizeMiB, limits.snapshotSizeMultiplier)

    fun connectionError(message: String): String {
        val deviceCount = authorizedDeviceCountError
            .matchEntire(message)
            ?.groupValues
            ?.get(1)
            ?: return message
        return fmt(ViewerRes.connection_error_device_count, deviceCount)
    }

    fun metrics(nodeCount: Int, maxDepth: Int, widestLevel: Int): String =
        fmt(ViewerRes.metrics_summary, nodeCount, maxDepth, widestLevel)

    fun timelineDiff(added: Int, removed: Int, moved: Int): String =
        fmt(ViewerRes.timeline_diff, added, removed, moved)

    fun timelineFrameSummary(added: Int, removed: Int, moved: Int): String =
        fmt(ViewerRes.timeline_frame_summary, added, removed, moved)

    fun infoBadge(count: Int): String = fmt(ViewerRes.info_badge, count)
    fun warningBadge(count: Int): String = fmt(ViewerRes.warning_badge, count)
    fun errorBadge(count: Int): String = fmt(ViewerRes.error_badge, count)

    fun subtreeComplexity(descendants: Int, depth: Int): String =
        fmt(ViewerRes.subtree_complexity, descendants, depth)

    fun noHighOverlapPairs(): String = noHighOverlapPairsStr

    fun overlapPairs(pairs: Int, maxRatio: Float): String {
        val pct = (maxRatio * 100).roundToInt()
        return if (pairs == 1) {
            fmt(ViewerRes.overlap_pair_single, pairs, pct)
        } else {
            fmt(ViewerRes.overlap_pairs, pairs, pct)
        }
    }

    fun blending(alpha: Float): String =
        if (alpha < 1f) fmt(ViewerRes.blending_alpha, alpha.toString()) else str(ViewerRes.alpha_one)

    // ---- Enum-to-string mappings ----

    fun actionLabel(action: ViewerAction): String = when (action) {
        ViewerAction.TOGGLE_AUTO_SCAN -> autoScan
        ViewerAction.PREVIOUS_NODE -> str(ViewerRes.previous_node)
        ViewerAction.NEXT_NODE -> str(ViewerRes.next_node)
        ViewerAction.TOGGLE_SELECTED_NODE -> str(ViewerRes.toggle_selected_node)
        ViewerAction.TOGGLE_HIERARCHY -> str(ViewerRes.toggle_hierarchy)
        ViewerAction.TOGGLE_FINDINGS -> str(ViewerRes.toggle_findings)
        ViewerAction.TOGGLE_DETAILS -> str(ViewerRes.toggle_details)
        ViewerAction.TOGGLE_HIERARCHY_IDS -> str(ViewerRes.toggle_hierarchy_ids)
        ViewerAction.OPEN_SETTINGS -> settings
    }

    fun viewOptionLabel(option: ViewDisplayOption): String = when (option) {
        ViewDisplayOption.HIDE_INVISIBLE_HIERARCHY_VIEWS -> hideInvisibleHierarchyViews
        ViewDisplayOption.HIDE_INVISIBLE_FINDINGS -> hideInvisibleFindings
        ViewDisplayOption.HIDE_HIERARCHY_INDICES -> hideHierarchyIndices
        ViewDisplayOption.SHOW_HIERARCHY_LAYER_VISIBILITY_BUTTONS -> showHierarchyLayerVisibilityButtons
        ViewDisplayOption.SHOW_VISIBLE_VIEW_BOUNDS -> showVisibleViewBounds
    }

    fun captureTargetLabel(mode: CaptureTargetMode): String = when (mode) {
        CaptureTargetMode.FOREGROUND_APP -> str(ViewerRes.foreground_app)
        CaptureTargetMode.SYSTEM_UI -> str(ViewerRes.system_ui)
    }

    // ---- Finding logic (rule-based routing) ----

    fun findingTitle(ruleId: String): String = when (ruleId) {
        "layout.invisible-node" -> str(ViewerRes.finding_invisible_node)
        "layout.excessive-children" -> str(ViewerRes.finding_excessive_children)
        "layout.overlapping-siblings" -> str(ViewerRes.finding_overlapping_siblings)
        "layout.deep-hierarchy" -> str(ViewerRes.finding_deep_hierarchy)
        else -> ruleId
    }

    fun findingMessage(
        ruleId: String,
        arguments: Map<String, String>,
        fallback: String,
    ): String = when (ruleId) {
        "layout.invisible-node" -> arguments["className"]?.let { className ->
            fmt(ViewerRes.finding_invisible_node_message, className)
        }
        "layout.excessive-children" -> {
            val count = arguments["count"]
            val threshold = arguments["threshold"]
            if (count != null && threshold != null) {
                fmt(ViewerRes.finding_excessive_children_message, count, threshold)
            } else {
                null
            }
        }
        "layout.overlapping-siblings" -> {
            val count = arguments["count"]
            val ratio = arguments["ratioPercent"]
            if (count != null && ratio != null) {
                fmt(ViewerRes.finding_overlapping_siblings_message, count, ratio)
            } else {
                null
            }
        }
        "layout.deep-hierarchy" -> {
            val depth = arguments["depth"]
            val threshold = arguments["threshold"]
            if (depth != null && threshold != null) {
                fmt(ViewerRes.finding_deep_hierarchy_message, depth, threshold)
            } else {
                null
            }
        }
        else -> null
    } ?: fallback

    fun detailSection(resource: StringResource): String = str(resource)

    fun detailLabel(resource: StringResource): String = str(resource)

    // ---- Resource loading ----

    private fun str(resource: StringResource): String = localizedStringResource(resource, language)

    private fun fmt(resource: StringResource, vararg args: Any?): String =
        localizedStringResource(resource, language, *args)

    companion object {
        fun forLanguage(language: UiLanguage): ViewerStrings = ViewerStrings(language)

        val English: ViewerStrings = ViewerStrings(UiLanguage.ENGLISH)

        private val authorizedDeviceCountError =
            Regex("""Expected exactly one authorized device, found (\d+)""")
    }
}

internal val LocalViewerStrings = staticCompositionLocalOf { ViewerStrings.English }
