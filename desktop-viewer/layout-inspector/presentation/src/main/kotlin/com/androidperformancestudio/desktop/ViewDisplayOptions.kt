package com.androidperformancestudio.desktop

import java.util.prefs.Preferences

internal enum class ViewDisplayOption {
    HIDE_INVISIBLE_HIERARCHY_VIEWS,
    HIDE_INVISIBLE_FINDINGS,
    HIDE_HIERARCHY_INDICES,
    SHOW_HIERARCHY_LAYER_VISIBILITY_BUTTONS,
    SHOW_VISIBLE_VIEW_BOUNDS,
}

internal data class ViewDisplayOptions(
    val hideInvisibleHierarchyViews: Boolean = false,
    val hideInvisibleFindings: Boolean = false,
    val hideHierarchyIndices: Boolean = false,
    val showHierarchyIds: Boolean = true,
    val showHierarchyLayerVisibilityButtons: Boolean = false,
    val showVisibleViewBounds: Boolean = true,
    val canvasHitTestOrder: CanvasHitTestOrder = CanvasHitTestOrder.SMALL_AREA_FIRST,
) {
    fun toggle(option: ViewDisplayOption): ViewDisplayOptions = when (option) {
        ViewDisplayOption.HIDE_INVISIBLE_HIERARCHY_VIEWS ->
            copy(hideInvisibleHierarchyViews = !hideInvisibleHierarchyViews)
        ViewDisplayOption.HIDE_INVISIBLE_FINDINGS ->
            copy(hideInvisibleFindings = !hideInvisibleFindings)
        ViewDisplayOption.HIDE_HIERARCHY_INDICES ->
            copy(hideHierarchyIndices = !hideHierarchyIndices)
        ViewDisplayOption.SHOW_HIERARCHY_LAYER_VISIBILITY_BUTTONS ->
            copy(showHierarchyLayerVisibilityButtons = !showHierarchyLayerVisibilityButtons)
        ViewDisplayOption.SHOW_VISIBLE_VIEW_BOUNDS ->
            copy(showVisibleViewBounds = !showVisibleViewBounds)
    }

    fun toggleHierarchyIds(): ViewDisplayOptions =
        copy(showHierarchyIds = !showHierarchyIds)

    fun toggleHierarchyLayerVisibilityButtons(): ViewDisplayOptions =
        copy(showHierarchyLayerVisibilityButtons = !showHierarchyLayerVisibilityButtons)

    fun toggleCanvasHitTestOrder(): ViewDisplayOptions =
        copy(
            canvasHitTestOrder = when (canvasHitTestOrder) {
                CanvasHitTestOrder.SMALL_AREA_FIRST -> CanvasHitTestOrder.Z_ORDER
                CanvasHitTestOrder.Z_ORDER -> CanvasHitTestOrder.SMALL_AREA_FIRST
            },
        )
}

internal class ViewDisplayOptionsStore(
    private val readBoolean: (String, Boolean) -> Boolean,
    private val writeBoolean: (String, Boolean) -> Unit,
    private val flush: () -> Unit = {},
) {
    fun load(): ViewDisplayOptions = ViewDisplayOptions(
        hideInvisibleHierarchyViews = readBoolean(HIDE_INVISIBLE_HIERARCHY_VIEWS_KEY, false),
        hideInvisibleFindings = readBoolean(HIDE_INVISIBLE_FINDINGS_KEY, false),
        hideHierarchyIndices = readBoolean(HIDE_HIERARCHY_INDICES_KEY, false),
        showHierarchyIds = readBoolean(SHOW_HIERARCHY_IDS_KEY, true),
        showHierarchyLayerVisibilityButtons = readBoolean(
            SHOW_HIERARCHY_LAYER_VISIBILITY_BUTTONS_KEY,
            false,
        ),
        showVisibleViewBounds = readBoolean(SHOW_VISIBLE_VIEW_BOUNDS_KEY, true),
        canvasHitTestOrder = if (readBoolean(CANVAS_HIT_TEST_ORDER_Z_KEY, false)) {
            CanvasHitTestOrder.Z_ORDER
        } else {
            CanvasHitTestOrder.SMALL_AREA_FIRST
        },
    )

    fun save(options: ViewDisplayOptions): Boolean =
        runCatching {
            writeBoolean(HIDE_INVISIBLE_HIERARCHY_VIEWS_KEY, options.hideInvisibleHierarchyViews)
            writeBoolean(HIDE_INVISIBLE_FINDINGS_KEY, options.hideInvisibleFindings)
            writeBoolean(HIDE_HIERARCHY_INDICES_KEY, options.hideHierarchyIndices)
            writeBoolean(SHOW_HIERARCHY_IDS_KEY, options.showHierarchyIds)
            writeBoolean(
                SHOW_HIERARCHY_LAYER_VISIBILITY_BUTTONS_KEY,
                options.showHierarchyLayerVisibilityButtons,
            )
            writeBoolean(SHOW_VISIBLE_VIEW_BOUNDS_KEY, options.showVisibleViewBounds)
            writeBoolean(
                CANVAS_HIT_TEST_ORDER_Z_KEY,
                options.canvasHitTestOrder == CanvasHitTestOrder.Z_ORDER,
            )
            flush()
        }.isSuccess

    companion object {
        private const val HIDE_INVISIBLE_HIERARCHY_VIEWS_KEY =
            "view.hideInvisibleHierarchyViews"
        private const val HIDE_INVISIBLE_FINDINGS_KEY = "view.hideInvisibleFindings"
        private const val HIDE_HIERARCHY_INDICES_KEY = "view.hideHierarchyIndices"
        private const val SHOW_HIERARCHY_IDS_KEY = "view.showHierarchyIds"
        private const val SHOW_HIERARCHY_LAYER_VISIBILITY_BUTTONS_KEY =
            "view.showHierarchyLayerVisibilityButtons"
        private const val SHOW_VISIBLE_VIEW_BOUNDS_KEY = "view.showVisibleViewBounds"
        private const val CANVAS_HIT_TEST_ORDER_Z_KEY = "view.canvasHitTestOrder.zOrder"

        fun desktop(): ViewDisplayOptionsStore {
            val preferences = runCatching {
                Preferences.userNodeForPackage(ViewDisplayOptionsStore::class.java)
            }.getOrNull()
            return ViewDisplayOptionsStore(
                readBoolean = { key, default ->
                    runCatching { preferences?.getBoolean(key, default) ?: default }
                        .getOrDefault(default)
                },
                writeBoolean = { key, value ->
                    checkNotNull(preferences) { "View display preferences are unavailable" }
                    preferences.putBoolean(key, value)
                },
                flush = { checkNotNull(preferences).flush() },
            )
        }
    }
}
