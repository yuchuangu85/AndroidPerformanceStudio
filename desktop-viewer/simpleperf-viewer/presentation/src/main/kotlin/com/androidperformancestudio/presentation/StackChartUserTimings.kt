package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.storage.MarkerProjectionRow
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.visualization.FirefoxFlameGraphStyle
import java.util.PriorityQueue
import kotlin.math.ceil

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun StackChartUserTimingTooltip(
    marker: MarkerProjectionRow,
    style: FirefoxFlameGraphStyle,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .testTag("stack-user-timing-tooltip")
            .background(style.raisedSurface.toComposeColor())
            .border(1.dp, style.surfaceBorder.toComposeColor())
            .padding(8.dp),
    ) {
        Text(marker.name, color = style.canvasForeground.toComposeColor(), style = ViewerTypography.secondary)
        Text(
            firefoxTooltipDuration(marker.endNanosExclusive - marker.startNanos, null),
            color = style.mutedForeground.toComposeColor(),
            style = ViewerTypography.label,
        )
    }
}

internal data class StackChartUserTimingRow(
    val name: String,
    val markers: List<MarkerProjectionRow>,
)

internal data class VisibleStackChartUserTiming(
    val marker: MarkerProjectionRow,
    val rect: Rect,
)

@Suppress("LongParameterList")
internal fun visibleStackChartUserTimings(
    rows: List<StackChartUserTimingRow>,
    viewport: StackChartViewport,
    width: Float,
    height: Float,
    rowHeight: Float,
    scrollPixels: Float,
): List<VisibleStackChartUserTiming> =
    buildList {
        if (width <= 0 || height <= 0 || rowHeight <= 0) return@buildList
        val first = (scrollPixels / rowHeight).toInt().coerceAtLeast(0)
        val last = ceil((scrollPixels + height) / rowHeight).toInt().coerceAtMost(rows.size)
        for (rowIndex in first until last) {
            var lastDrawn = 0f
            val markers = rows[rowIndex].markers
            var index = markers.firstEndingAfter(viewport.startNanos)
            while (index < markers.size && markers[index].startNanos < viewport.endNanosExclusive) {
                val marker = markers[index]
                val left =
                    ((marker.startNanos - viewport.startNanos).toDouble() / viewport.durationNanos * width)
                        .toFloat()
                        .coerceIn(0f, width)
                        .coerceAtLeast(lastDrawn)
                val right =
                    ((marker.endNanosExclusive - viewport.startNanos).toDouble() / viewport.durationNanos * width)
                        .toFloat()
                        .coerceIn(0f, width)
                if (left < width) {
                    val top = rowIndex * rowHeight - scrollPixels
                    add(VisibleStackChartUserTiming(marker, Rect(left, top, maxOf(left + 1f, right), top + rowHeight)))
                    lastDrawn = maxOf(left + 1f, right)
                }
                val nextTime = StackChartPresenter.timeAtX(lastDrawn, viewport, width)
                index = maxOf(index + 1, markers.firstEndingAfter(nextTime))
            }
        }
    }

internal fun stackChartUserTimingRows(markers: List<MarkerProjectionRow>): List<StackChartUserTimingRow> =
    markers
        .asSequence()
        .filter { it.schema == "UserTiming" }
        .sortedWith(compareBy(MarkerProjectionRow::startNanos, { it.id.value }))
        .groupBy(MarkerProjectionRow::name)
        .toSortedMap()
        .flatMap { (name, named) ->
            val intervals = ArrayList<MutableList<MarkerProjectionRow>>()
            val busy = PriorityQueue(compareBy<TimingRowEnd>({ it.endNanos }, { it.row }))
            val free = PriorityQueue<Int>()
            val instants = named.filterNot(MarkerProjectionRow::interval)
            named.filter(MarkerProjectionRow::interval).forEach { marker ->
                while (busy.peek()?.endNanos?.let { it <= marker.startNanos } == true) free.add(busy.remove().row)
                val row = free.poll() ?: intervals.size.also { intervals.add(ArrayList()) }
                intervals[row].add(marker)
                busy.add(TimingRowEnd(marker.endNanosExclusive, row))
            }
            buildList {
                if (instants.isNotEmpty()) add(StackChartUserTimingRow(name, instants))
                intervals.forEach { add(StackChartUserTimingRow(name, it.toList())) }
            }
        }

internal fun StackChartUserTimingRow.markerAt(timeNanos: Long): MarkerProjectionRow? {
    var low = 0
    var high = markers.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (markers[middle].startNanos <= timeNanos) low = middle + 1 else high = middle
    }
    return markers.getOrNull(low - 1)?.takeIf { timeNanos < it.endNanosExclusive }
}

private data class TimingRowEnd(
    val endNanos: Long,
    val row: Int,
)

private fun List<MarkerProjectionRow>.firstEndingAfter(time: Long): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle].endNanosExclusive <= time) low = middle + 1 else high = middle
    }
    return low
}
