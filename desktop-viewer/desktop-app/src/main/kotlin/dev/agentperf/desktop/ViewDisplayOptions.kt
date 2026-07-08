package dev.agentperf.desktop

import java.util.prefs.Preferences

internal enum class ViewDisplayOption {
    HIDE_INVISIBLE_HIERARCHY_VIEWS,
    HIDE_INVISIBLE_FINDINGS,
    HIDE_HIERARCHY_INDICES,
    SHOW_VISIBLE_VIEW_BOUNDS,
}

internal data class ViewDisplayOptions(
    val hideInvisibleHierarchyViews: Boolean = false,
    val hideInvisibleFindings: Boolean = false,
    val hideHierarchyIndices: Boolean = false,
    val showHierarchyIds: Boolean = true,
    val showVisibleViewBounds: Boolean = false,
    val canvasHitTestOrder: CanvasHitTestOrder = CanvasHitTestOrder.SMALL_AREA_FIRST,
) {
    fun toggle(option: ViewDisplayOption): ViewDisplayOptions = when (option) {
        ViewDisplayOption.HIDE_INVISIBLE_HIERARCHY_VIEWS ->
            copy(hideInvisibleHierarchyViews = !hideInvisibleHierarchyViews)
        ViewDisplayOption.HIDE_INVISIBLE_FINDINGS ->
            copy(hideInvisibleFindings = !hideInvisibleFindings)
        ViewDisplayOption.HIDE_HIERARCHY_INDICES ->
            copy(hideHierarchyIndices = !hideHierarchyIndices)
        ViewDisplayOption.SHOW_VISIBLE_VIEW_BOUNDS ->
            copy(showVisibleViewBounds = !showVisibleViewBounds)
    }

    fun toggleHierarchyIds(): ViewDisplayOptions =
        copy(showHierarchyIds = !showHierarchyIds)

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
) {
    fun load(): ViewDisplayOptions = ViewDisplayOptions(
        hideInvisibleHierarchyViews = readBoolean(HIDE_INVISIBLE_HIERARCHY_VIEWS_KEY, false),
        hideInvisibleFindings = readBoolean(HIDE_INVISIBLE_FINDINGS_KEY, false),
        hideHierarchyIndices = readBoolean(HIDE_HIERARCHY_INDICES_KEY, false),
        showHierarchyIds = readBoolean(SHOW_HIERARCHY_IDS_KEY, true),
        showVisibleViewBounds = readBoolean(SHOW_VISIBLE_VIEW_BOUNDS_KEY, false),
        canvasHitTestOrder = if (readBoolean(CANVAS_HIT_TEST_ORDER_Z_KEY, false)) {
            CanvasHitTestOrder.Z_ORDER
        } else {
            CanvasHitTestOrder.SMALL_AREA_FIRST
        },
    )

    fun save(options: ViewDisplayOptions) {
        writeBoolean(HIDE_INVISIBLE_HIERARCHY_VIEWS_KEY, options.hideInvisibleHierarchyViews)
        writeBoolean(HIDE_INVISIBLE_FINDINGS_KEY, options.hideInvisibleFindings)
        writeBoolean(HIDE_HIERARCHY_INDICES_KEY, options.hideHierarchyIndices)
        writeBoolean(SHOW_HIERARCHY_IDS_KEY, options.showHierarchyIds)
        writeBoolean(SHOW_VISIBLE_VIEW_BOUNDS_KEY, options.showVisibleViewBounds)
        writeBoolean(
            CANVAS_HIT_TEST_ORDER_Z_KEY,
            options.canvasHitTestOrder == CanvasHitTestOrder.Z_ORDER,
        )
    }

    companion object {
        private const val HIDE_INVISIBLE_HIERARCHY_VIEWS_KEY =
            "view.hideInvisibleHierarchyViews"
        private const val HIDE_INVISIBLE_FINDINGS_KEY = "view.hideInvisibleFindings"
        private const val HIDE_HIERARCHY_INDICES_KEY = "view.hideHierarchyIndices"
        private const val SHOW_HIERARCHY_IDS_KEY = "view.showHierarchyIds"
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
                    runCatching { preferences?.putBoolean(key, value) }
                    Unit
                },
            )
        }
    }
}
