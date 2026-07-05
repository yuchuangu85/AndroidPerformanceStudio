package dev.agentperf.desktop

import java.util.prefs.Preferences

internal enum class ViewDisplayOption {
    HIDE_INVISIBLE_HIERARCHY_VIEWS,
    HIDE_INVISIBLE_FINDINGS,
    HIDE_HIERARCHY_INDICES,
}

internal data class ViewDisplayOptions(
    val hideInvisibleHierarchyViews: Boolean = false,
    val hideInvisibleFindings: Boolean = false,
    val hideHierarchyIndices: Boolean = false,
) {
    fun toggle(option: ViewDisplayOption): ViewDisplayOptions = when (option) {
        ViewDisplayOption.HIDE_INVISIBLE_HIERARCHY_VIEWS ->
            copy(hideInvisibleHierarchyViews = !hideInvisibleHierarchyViews)
        ViewDisplayOption.HIDE_INVISIBLE_FINDINGS ->
            copy(hideInvisibleFindings = !hideInvisibleFindings)
        ViewDisplayOption.HIDE_HIERARCHY_INDICES ->
            copy(hideHierarchyIndices = !hideHierarchyIndices)
    }
}

internal class ViewDisplayOptionsStore(
    private val readBoolean: (String, Boolean) -> Boolean,
    private val writeBoolean: (String, Boolean) -> Unit,
) {
    fun load(): ViewDisplayOptions = ViewDisplayOptions(
        hideInvisibleHierarchyViews = readBoolean(HIDE_INVISIBLE_HIERARCHY_VIEWS_KEY, false),
        hideInvisibleFindings = readBoolean(HIDE_INVISIBLE_FINDINGS_KEY, false),
        hideHierarchyIndices = readBoolean(HIDE_HIERARCHY_INDICES_KEY, false),
    )

    fun save(options: ViewDisplayOptions) {
        writeBoolean(HIDE_INVISIBLE_HIERARCHY_VIEWS_KEY, options.hideInvisibleHierarchyViews)
        writeBoolean(HIDE_INVISIBLE_FINDINGS_KEY, options.hideInvisibleFindings)
        writeBoolean(HIDE_HIERARCHY_INDICES_KEY, options.hideHierarchyIndices)
    }

    companion object {
        private const val HIDE_INVISIBLE_HIERARCHY_VIEWS_KEY =
            "view.hideInvisibleHierarchyViews"
        private const val HIDE_INVISIBLE_FINDINGS_KEY = "view.hideInvisibleFindings"
        private const val HIDE_HIERARCHY_INDICES_KEY = "view.hideHierarchyIndices"

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
