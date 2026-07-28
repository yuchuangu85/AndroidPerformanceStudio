package dev.agentperf.desktop

import org.jetbrains.compose.resources.stringResource

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.agentperf.presentation.generated.resources.ViewerRes
import org.jetbrains.compose.resources.StringResource
import java.util.Locale
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
    val language: ViewerLanguage,
    private val templates: Map<StringResource, String>,
) {
    private val chinese: Boolean
        get() = language == ViewerLanguage.SIMPLIFIED_CHINESE

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

    fun detailSection(english: String): String =
        detailSectionResources[english]?.let(::str) ?: english

    fun detailLabel(english: String): String =
        detailLabelResources[english]?.let(::str) ?: english

    // ---- Resource loading ----

    private fun str(resource: StringResource): String = templates.getValue(resource)

    private fun fmt(resource: StringResource, vararg args: Any?): String =
        String.format(Locale.ROOT, str(resource), *args)

    companion object {
        @Composable
        fun forLanguage(language: ViewerLanguage): ViewerStrings {
            val templates = LinkedHashMap<StringResource, String>(viewerStringResources.size)
            for ((_, resource) in viewerStringResources) {
                templates[resource] = stringResource(resource)
            }
            return ViewerStrings(language, templates)
        }

        internal fun fromTemplates(
            language: ViewerLanguage,
            values: Map<String, String>,
        ): ViewerStrings =
            ViewerStrings(
                language = language,
                templates = viewerStringResources.associate { (name, resource) -> resource to values.getValue(name) },
            )

        private val authorizedDeviceCountError =
            Regex("""Expected exactly one authorized device, found (\d+)""")
    }
}

internal val LocalViewerStrings = staticCompositionLocalOf<ViewerStrings> {
    error("ViewerStrings must be provided from a Composable resource context")
}

private val viewerStringResources: List<Pair<String, StringResource>> =
    listOf(
        "actions" to ViewerRes.actions,
        "ai_analysis_failed" to ViewerRes.ai_analysis_failed,
        "ai_analysis_running" to ViewerRes.ai_analysis_running,
        "ai_analysis_summary" to ViewerRes.ai_analysis_summary,
        "alpha_one" to ViewerRes.alpha_one,
        "app_only_off" to ViewerRes.app_only_off,
        "app_only_on" to ViewerRes.app_only_on,
        "archive_export_failed" to ViewerRes.archive_export_failed,
        "archive_export_succeeded" to ViewerRes.archive_export_succeeded,
        "archive_export_succeeded_no_attachments" to ViewerRes.archive_export_succeeded_no_attachments,
        "archive_import_failed" to ViewerRes.archive_import_failed,
        "archive_import_succeeded" to ViewerRes.archive_import_succeeded,
        "archive_limit_value" to ViewerRes.archive_limit_value,
        "auto_device" to ViewerRes.auto_device,
        "auto_scan" to ViewerRes.auto_scan,
        "back_to_home" to ViewerRes.back_to_home,
        "blending_alpha" to ViewerRes.blending_alpha,
        "canvas" to ViewerRes.canvas,
        "canvas_border_colors" to ViewerRes.canvas_border_colors,
        "canvas_hit_test_order" to ViewerRes.canvas_hit_test_order,
        "capture_archive" to ViewerRes.capture_archive,
        "capture_target" to ViewerRes.capture_target,
        "choose_archive_export_file" to ViewerRes.choose_archive_export_file,
        "choose_archive_to_import" to ViewerRes.choose_archive_to_import,
        "choose_screenshot_to_import" to ViewerRes.choose_screenshot_to_import,
        "connecting" to ViewerRes.connecting,
        "connection_error_device_count" to ViewerRes.connection_error_device_count,
        "connection_failed" to ViewerRes.connection_failed,
        "default_view_bounds_color" to ViewerRes.default_view_bounds_color,
        "detail_label_alpha" to ViewerRes.detail_label_alpha,
        "detail_label_background" to ViewerRes.detail_label_background,
        "detail_label_background_color" to ViewerRes.detail_label_background_color,
        "detail_label_blending" to ViewerRes.detail_label_blending,
        "detail_label_bounds" to ViewerRes.detail_label_bounds,
        "detail_label_class" to ViewerRes.detail_label_class,
        "detail_label_clickable" to ViewerRes.detail_label_clickable,
        "detail_label_clip_bounds" to ViewerRes.detail_label_clip_bounds,
        "detail_label_clip_children" to ViewerRes.detail_label_clip_children,
        "detail_label_clip_to_padding" to ViewerRes.detail_label_clip_to_padding,
        "detail_label_content_description" to ViewerRes.detail_label_content_description,
        "detail_label_descendants" to ViewerRes.detail_label_descendants,
        "detail_label_direct_children" to ViewerRes.detail_label_direct_children,
        "detail_label_elevation" to ViewerRes.detail_label_elevation,
        "detail_label_enabled" to ViewerRes.detail_label_enabled,
        "detail_label_focusable" to ViewerRes.detail_label_focusable,
        "detail_label_focused" to ViewerRes.detail_label_focused,
        "detail_label_foreground" to ViewerRes.detail_label_foreground,
        "detail_label_hardware_accelerated" to ViewerRes.detail_label_hardware_accelerated,
        "detail_label_hidden_descendants" to ViewerRes.detail_label_hidden_descendants,
        "detail_label_id" to ViewerRes.detail_label_id,
        "detail_label_layer_cost" to ViewerRes.detail_label_layer_cost,
        "detail_label_layer_type" to ViewerRes.detail_label_layer_type,
        "detail_label_layout_height" to ViewerRes.detail_label_layout_height,
        "detail_label_layout_params_class" to ViewerRes.detail_label_layout_params_class,
        "detail_label_layout_requested" to ViewerRes.detail_label_layout_requested,
        "detail_label_layout_width" to ViewerRes.detail_label_layout_width,
        "detail_label_local_layout_bounds" to ViewerRes.detail_label_local_layout_bounds,
        "detail_label_local_layout_size" to ViewerRes.detail_label_local_layout_size,
        "detail_label_long_clickable" to ViewerRes.detail_label_long_clickable,
        "detail_label_margin" to ViewerRes.detail_label_margin,
        "detail_label_measured_size" to ViewerRes.detail_label_measured_size,
        "detail_label_minimum_size" to ViewerRes.detail_label_minimum_size,
        "detail_label_opaque" to ViewerRes.detail_label_opaque,
        "detail_label_overdraw_estimate" to ViewerRes.detail_label_overdraw_estimate,
        "detail_label_padding" to ViewerRes.detail_label_padding,
        "detail_label_pivot" to ViewerRes.detail_label_pivot,
        "detail_label_resource" to ViewerRes.detail_label_resource,
        "detail_label_rotation" to ViewerRes.detail_label_rotation,
        "detail_label_scale" to ViewerRes.detail_label_scale,
        "detail_label_scroll" to ViewerRes.detail_label_scroll,
        "detail_label_selected" to ViewerRes.detail_label_selected,
        "detail_label_size" to ViewerRes.detail_label_size,
        "detail_label_subtree_complexity" to ViewerRes.detail_label_subtree_complexity,
        "detail_label_subtree_depth" to ViewerRes.detail_label_subtree_depth,
        "detail_label_text" to ViewerRes.detail_label_text,
        "detail_label_translation" to ViewerRes.detail_label_translation,
        "detail_label_tree_depth" to ViewerRes.detail_label_tree_depth,
        "detail_label_visibility" to ViewerRes.detail_label_visibility,
        "detail_label_will_not_draw" to ViewerRes.detail_label_will_not_draw,
        "detail_label_z" to ViewerRes.detail_label_z,
        "detail_section_drawing" to ViewerRes.detail_section_drawing,
        "detail_section_identity" to ViewerRes.detail_section_identity,
        "detail_section_interaction" to ViewerRes.detail_section_interaction,
        "detail_section_layout" to ViewerRes.detail_section_layout,
        "detail_section_raw_properties" to ViewerRes.detail_section_raw_properties,
        "detail_section_render_risks" to ViewerRes.detail_section_render_risks,
        "disconnected" to ViewerRes.disconnected,
        "dismiss" to ViewerRes.dismiss,
        "error_badge" to ViewerRes.error_badge,
        "export_archive" to ViewerRes.export_archive,
        "export_archive_failed_title" to ViewerRes.export_archive_failed_title,
        "export_archive_succeeded_title" to ViewerRes.export_archive_succeeded_title,
        "file" to ViewerRes.file,
        "find_in_memory_profiler" to ViewerRes.find_in_memory_profiler,
        "finding_deep_hierarchy" to ViewerRes.finding_deep_hierarchy,
        "finding_deep_hierarchy_message" to ViewerRes.finding_deep_hierarchy_message,
        "finding_excessive_children" to ViewerRes.finding_excessive_children,
        "finding_excessive_children_message" to ViewerRes.finding_excessive_children_message,
        "finding_invisible_node" to ViewerRes.finding_invisible_node,
        "finding_invisible_node_message" to ViewerRes.finding_invisible_node_message,
        "finding_overlapping_siblings" to ViewerRes.finding_overlapping_siblings,
        "finding_overlapping_siblings_message" to ViewerRes.finding_overlapping_siblings_message,
        "findings" to ViewerRes.findings,
        "foreground_app" to ViewerRes.foreground_app,
        "hidden_layer_summary" to ViewerRes.hidden_layer_summary,
        "hide_hierarchy_indices" to ViewerRes.hide_hierarchy_indices,
        "hide_invisible_findings" to ViewerRes.hide_invisible_findings,
        "hide_invisible_hierarchy_views" to ViewerRes.hide_invisible_hierarchy_views,
        "hide_layer" to ViewerRes.hide_layer,
        "hierarchy" to ViewerRes.hierarchy,
        "hovered" to ViewerRes.hovered,
        "hovered_view_bounds_color" to ViewerRes.hovered_view_bounds_color,
        "import_archive" to ViewerRes.import_archive,
        "import_archive_failed_title" to ViewerRes.import_archive_failed_title,
        "import_archive_succeeded_title" to ViewerRes.import_archive_succeeded_title,
        "import_screenshot" to ViewerRes.import_screenshot,
        "import_screenshot_failed_title" to ViewerRes.import_screenshot_failed_title,
        "import_screenshot_succeeded_title" to ViewerRes.import_screenshot_succeeded_title,
        "info_badge" to ViewerRes.info_badge,
        "layout_inspector" to ViewerRes.layout_inspector,
        "layout_inspector_settings" to ViewerRes.layout_inspector_settings,
        "layout_snapshot_archive_limit" to ViewerRes.layout_snapshot_archive_limit,
        "layout_snapshot_archive_limit_hint" to ViewerRes.layout_snapshot_archive_limit_hint,
        "live" to ViewerRes.live,
        "metrics_summary" to ViewerRes.metrics_summary,
        "next_node" to ViewerRes.next_node,
        "no_app" to ViewerRes.no_app,
        "no_available_windows" to ViewerRes.no_available_windows,
        "no_findings" to ViewerRes.no_findings,
        "no_high_overlap_pairs" to ViewerRes.no_high_overlap_pairs,
        "no_live_frame" to ViewerRes.no_live_frame,
        "no_snapshot_loaded" to ViewerRes.no_snapshot_loaded,
        "normal" to ViewerRes.normal,
        "offline_archive" to ViewerRes.offline_archive,
        "overlap_pair_single" to ViewerRes.overlap_pair_single,
        "overlap_pairs" to ViewerRes.overlap_pairs,
        "previous_node" to ViewerRes.previous_node,
        "properties" to ViewerRes.properties,
        "refresh" to ViewerRes.refresh,
        "refresh_once" to ViewerRes.refresh_once,
        "reset" to ViewerRes.reset,
        "run_ai_analysis" to ViewerRes.run_ai_analysis,
        "screenshot_import_failed" to ViewerRes.screenshot_import_failed,
        "screenshot_import_succeeded" to ViewerRes.screenshot_import_succeeded,
        "search_hierarchy" to ViewerRes.search_hierarchy,
        "search_next" to ViewerRes.search_next,
        "search_no_match" to ViewerRes.search_no_match,
        "search_previous" to ViewerRes.search_previous,
        "select_window" to ViewerRes.select_window,
        "selected" to ViewerRes.selected,
        "selected_view_bounds_color" to ViewerRes.selected_view_bounds_color,
        "settings" to ViewerRes.settings,
        "settings_save_failed" to ViewerRes.settings_save_failed,
        "show_hierarchy_ids" to ViewerRes.show_hierarchy_ids,
        "show_hierarchy_layer_visibility_buttons" to ViewerRes.show_hierarchy_layer_visibility_buttons,
        "show_layer" to ViewerRes.show_layer,
        "show_visible_view_bounds" to ViewerRes.show_visible_view_bounds,
        "small_area_first" to ViewerRes.small_area_first,
        "small_area_hit_testing" to ViewerRes.small_area_hit_testing,
        "snapshot_archive_limit_value" to ViewerRes.snapshot_archive_limit_value,
        "subtree_complexity" to ViewerRes.subtree_complexity,
        "system_ui" to ViewerRes.system_ui,
        "timeline_baseline" to ViewerRes.timeline_baseline,
        "timeline_diff" to ViewerRes.timeline_diff,
        "timeline_frame_summary" to ViewerRes.timeline_frame_summary,
        "timeline_live_capture" to ViewerRes.timeline_live_capture,
        "toggle_details" to ViewerRes.toggle_details,
        "toggle_findings" to ViewerRes.toggle_findings,
        "toggle_hierarchy" to ViewerRes.toggle_hierarchy,
        "toggle_hierarchy_ids" to ViewerRes.toggle_hierarchy_ids,
        "toggle_layer_visibility" to ViewerRes.toggle_layer_visibility,
        "toggle_selected_node" to ViewerRes.toggle_selected_node,
        "unavailable" to ViewerRes.unavailable,
        "view" to ViewerRes.view,
        "view_and_hierarchy" to ViewerRes.view_and_hierarchy,
        "waiting_for_frame" to ViewerRes.waiting_for_frame,
        "warning_badge" to ViewerRes.warning_badge,
        "window" to ViewerRes.window,
        "z_order" to ViewerRes.z_order,
        "z_order_hit_testing" to ViewerRes.z_order_hit_testing,
        "zoom_in_preview" to ViewerRes.zoom_in_preview,
        "zoom_out_preview" to ViewerRes.zoom_out_preview,
    )

private val detailSectionResources: Map<String, StringResource> = mapOf(
    "RENDER RISKS" to ViewerRes.detail_section_render_risks,
    "IDENTITY" to ViewerRes.detail_section_identity,
    "LAYOUT" to ViewerRes.detail_section_layout,
    "DRAWING" to ViewerRes.detail_section_drawing,
    "INTERACTION" to ViewerRes.detail_section_interaction,
    "RAW PROPERTIES" to ViewerRes.detail_section_raw_properties,
)

private val detailLabelResources: Map<String, StringResource> = mapOf(
    "Class" to ViewerRes.detail_label_class,
    "ID" to ViewerRes.detail_label_id,
    "Resource" to ViewerRes.detail_label_resource,
    "Text" to ViewerRes.detail_label_text,
    "Content description" to ViewerRes.detail_label_content_description,
    "Bounds" to ViewerRes.detail_label_bounds,
    "Size" to ViewerRes.detail_label_size,
    "Local layout bounds" to ViewerRes.detail_label_local_layout_bounds,
    "Local layout size" to ViewerRes.detail_label_local_layout_size,
    "Visibility" to ViewerRes.detail_label_visibility,
    "Tree depth" to ViewerRes.detail_label_tree_depth,
    "Direct children" to ViewerRes.detail_label_direct_children,
    "Descendants" to ViewerRes.detail_label_descendants,
    "Subtree depth" to ViewerRes.detail_label_subtree_depth,
    "Layout width" to ViewerRes.detail_label_layout_width,
    "Layout height" to ViewerRes.detail_label_layout_height,
    "Layout params class" to ViewerRes.detail_label_layout_params_class,
    "Measured size" to ViewerRes.detail_label_measured_size,
    "Minimum size" to ViewerRes.detail_label_minimum_size,
    "Padding" to ViewerRes.detail_label_padding,
    "Margin" to ViewerRes.detail_label_margin,
    "Scroll" to ViewerRes.detail_label_scroll,
    "Layout requested" to ViewerRes.detail_label_layout_requested,
    "Alpha" to ViewerRes.detail_label_alpha,
    "Z" to ViewerRes.detail_label_z,
    "Elevation" to ViewerRes.detail_label_elevation,
    "Translation" to ViewerRes.detail_label_translation,
    "Rotation" to ViewerRes.detail_label_rotation,
    "Scale" to ViewerRes.detail_label_scale,
    "Pivot" to ViewerRes.detail_label_pivot,
    "Background" to ViewerRes.detail_label_background,
    "Background color" to ViewerRes.detail_label_background_color,
    "Foreground" to ViewerRes.detail_label_foreground,
    "Clip bounds" to ViewerRes.detail_label_clip_bounds,
    "Clip children" to ViewerRes.detail_label_clip_children,
    "Clip to padding" to ViewerRes.detail_label_clip_to_padding,
    "Opaque" to ViewerRes.detail_label_opaque,
    "Will not draw" to ViewerRes.detail_label_will_not_draw,
    "Hardware accelerated" to ViewerRes.detail_label_hardware_accelerated,
    "Layer type" to ViewerRes.detail_label_layer_type,
    "Enabled" to ViewerRes.detail_label_enabled,
    "Clickable" to ViewerRes.detail_label_clickable,
    "Long clickable" to ViewerRes.detail_label_long_clickable,
    "Focusable" to ViewerRes.detail_label_focusable,
    "Focused" to ViewerRes.detail_label_focused,
    "Selected" to ViewerRes.detail_label_selected,
    "Overdraw estimate" to ViewerRes.detail_label_overdraw_estimate,
    "Subtree complexity" to ViewerRes.detail_label_subtree_complexity,
    "Hidden descendants" to ViewerRes.detail_label_hidden_descendants,
    "Blending" to ViewerRes.detail_label_blending,
    "Layer cost" to ViewerRes.detail_label_layer_cost,
)
