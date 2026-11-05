package com.androidperformancestudio.desktop

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import java.util.prefs.Preferences

/**
 * The default module order — the order that was hardcoded in [AppHomePage] and
 * [com.androidperformancestudio.desktop.shell.CommercialAppShell] before this change. A fresh
 * install or a layout with no recognized modules restores this order.
 */
internal val DEFAULT_MODULE_ORDER: List<AppDestination> =
    listOf(
        AppDestination.LAYOUT_INSPECTOR,
        AppDestination.SIMPLEPERF,
        AppDestination.PERFETTO,
        AppDestination.MEMORY_PROFILER,
        AppDestination.FRAME_PROFILER,
        AppDestination.STARTUP_PROFILER,
        AppDestination.BATTERY_NETWORK,
        AppDestination.GPU_INSPECTOR,
        AppDestination.BENCHMARK_REGRESSION,
        AppDestination.SOURCE_WORKSPACES,
        AppDestination.REVERSE_NET,
        AppDestination.SCREENCAST,
    )

/** The set of destinations the user is allowed to place in the module grid / rail. */
internal val REORDERABLE_MODULE_DESTINATIONS: Set<AppDestination> = DEFAULT_MODULE_ORDER.toSet()

/**
 * Persists the home layout — the ordered module list — as a comma-separated ID string in
 * [java.util.prefs.Preferences], mirroring the [ApplicationUiSettingsStore] pattern. Unknown IDs
 * are dropped on load for forward compatibility; missing defaults are appended so a fresh install
 * matches today's layout.
 */
internal class HomeLayoutPreferencesStore(
    private val readValue: (String) -> String?,
    private val writeValue: (String, String) -> Unit,
    private val flush: () -> Unit = {},
) {
    fun loadModuleOrder(): List<AppDestination> {
        val saved = readValue(MODULE_ORDER_KEY)?.split(',')?.mapNotNull { id ->
            AppDestination.entries.firstOrNull { it.name == id.trim() }
        } ?: emptyList()
        return reconcileModules(saved)
    }

    fun save(moduleOrder: List<AppDestination>): Boolean =
        runCatching {
            writeValue(MODULE_ORDER_KEY, moduleOrder.joinToString(",") { it.name })
            flush()
        }.isSuccess

    private fun reconcileModules(saved: List<AppDestination>): List<AppDestination> {
        val known = saved.filter { it in REORDERABLE_MODULE_DESTINATIONS }
        val missing = DEFAULT_MODULE_ORDER.filter { it !in known }
        return known + missing
    }

    companion object {
        private const val MODULE_ORDER_KEY = "application.home.moduleOrder"

        fun desktop(): HomeLayoutPreferencesStore {
            val preferences =
                runCatching {
                    Preferences.userNodeForPackage(HomeLayoutPreferencesStore::class.java)
                }.getOrNull()
            return HomeLayoutPreferencesStore(
                readValue = { key -> runCatching { preferences?.get(key, null) }.getOrNull() },
                writeValue = { key, value ->
                    checkNotNull(preferences) { "Home layout preferences are unavailable" }
                    preferences.put(key, value)
                },
                flush = { checkNotNull(preferences).flush() },
            )
        }
    }
}

/**
 * The single source of truth for the home layout. Both [AppHomePage] and the navigation rail read
 * [moduleOrder]. Mutations update the [SnapshotStateList] in place (driving recomposition and live
 * drag animation) and persist.
 */
internal class HomeLayoutController(
    private val store: HomeLayoutPreferencesStore,
) {
    val moduleOrder: SnapshotStateList<AppDestination> = store.loadModuleOrder().toMutableStateList()

    fun moveModule(from: Int, to: Int) {
        if (from == to || from !in moduleOrder.indices || to !in moduleOrder.indices) return
        moduleOrder.add(to, moduleOrder.removeAt(from))
        persist()
    }

    private fun persist() {
        store.save(moduleOrder.toList())
    }

    companion object {
        fun desktop(): HomeLayoutController = HomeLayoutController(HomeLayoutPreferencesStore.desktop())
    }
}

/**
 * Computes the insertion index for a pointer position over a lazy grid. Returns the index at
 * which the dragged item should be placed so the grid reorders live as the pointer moves.
 *
 * Strategy: find the item whose bounds contain the pointer; the dragged item goes to that item's
 * index. If the pointer is between items (in a gap), pick the item whose start edge is nearest
 * forward, else the last item. Pure and unit-testable.
 */
internal fun dropIndexForGrid(
    itemSlots: List<GridSlot>,
    pointerOffset: Int,
    columnCount: Int,
): Int {
    if (itemSlots.isEmpty()) return 0
    // Containment first.
    itemSlots.firstOrNull { pointerOffset in it.start..it.end }?.let { return it.index }
    // Pointer before the first item.
    if (pointerOffset < itemSlots.first().start) return 0
    // Pointer after the last item.
    if (pointerOffset > itemSlots.last().end) return itemSlots.last().index + 1
    // In a gap: nearest forward start edge.
    return itemSlots.firstOrNull { it.start >= pointerOffset }?.index ?: (itemSlots.last().index + 1)
}

/** One slot in the primary axis (a row's x-axis). */
internal data class GridSlot(val index: Int, val start: Int, val end: Int)

/** Minimal rect used by the drop-index math — kept allocation-light for unit tests. */
internal data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Resolves which row a pointer y falls into for a multi-row grid. */
internal fun rowForY(itemRects: List<IntRect>, columnCount: Int, pointerY: Int): Int {
    if (itemRects.isEmpty()) return 0
    val rowTops = itemRects.map { it.top }.distinct().sorted()
    // The row containing the pointer is the last row whose top edge is at or above the pointer.
    val row = rowTops.indexOfLast { it <= pointerY }
    val resolved = if (row == -1) 0 else row
    val maxRow = (itemRects.size - 1) / columnCount
    return resolved.coerceIn(0, maxRow)
}

/** Full grid drop index combining row and column targeting. */
internal fun dropIndexForGrid(
    itemRects: List<IntRect>,
    pointerX: Int,
    pointerY: Int,
    columnCount: Int,
): Int {
    if (itemRects.isEmpty()) return 0
    val row = rowForY(itemRects, columnCount, pointerY)
    val rowStart = row * columnCount
    val rowRects = itemRects.drop(rowStart).take(columnCount)
    val slots = rowRects.mapIndexed { i, rect -> GridSlot(rowStart + i, rect.left, rect.right) }
    return dropIndexForGrid(slots, pointerX, columnCount)
}
