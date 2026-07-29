@file:Suppress("MagicNumber", "MaxLineLength")

package com.androidperformancestudio.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.presentation.generated.resources.ViewerRes
import com.androidperformancestudio.storage.MarkerProjectionSnapshot
import com.androidperformancestudio.storage.ProfileMarkerId
import com.androidperformancestudio.ui.ViewerColors

@Composable
@Suppress("FunctionName", "LongMethod", "ktlint:standard:function-naming")
internal fun MarkerChartCanvas(
    snapshot: MarkerProjectionSnapshot,
    viewport: StackChartViewport,
    selectedMarkerId: ProfileMarkerId?,
    style: ViewerColors,
    onSelect: (ProfileMarkerId?) -> Unit,
) {
    var widthPixels by remember { mutableIntStateOf(0) }
    var heightPixels by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val markerChartDescription = localizedSimpleperfText("Marker chart")
    val byId = remember(snapshot) { snapshot.markers.associateBy { it.id } }
    val visibleLanes =
        remember(snapshot, viewport) {
            snapshot.lanes.mapNotNull { lane ->
                lane.markerIds
                    .mapNotNull(byId::get)
                    .filter { MarkerPresenter.visible(it, viewport) }
                    .takeIf(List<*>::isNotEmpty)
                    ?.let { lane.label to it }
            }
        }
    Box(
        Modifier
            .fillMaxSize()
            .testTag("marker-chart-canvas")
            .onSizeChanged {
                widthPixels = it.width
                heightPixels = it.height
            },
    ) {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = markerChartDescription }) {
            visibleLanes.forEachIndexed { laneIndex, (_, markers) ->
                val centerY = laneIndex * MARKER_LANE_HEIGHT_DP.dp.toPx() + MARKER_LANE_HEIGHT_DP.dp.toPx() / 2f
                drawLine(
                    style.border,
                    Offset(0f, centerY + MARKER_LANE_HEIGHT_DP.dp.toPx() / 2f),
                    Offset(
                        size.width,
                        centerY + MARKER_LANE_HEIGHT_DP.dp.toPx() / 2f,
                    ),
                )
                markers.forEach { marker ->
                    val color = if (marker.id == selectedMarkerId) style.accent else style.warning
                    when (val glyph = MarkerPresenter.glyph(marker, viewport, size.width)) {
                        is MarkerGlyph.Point -> {
                            drawLine(
                                color,
                                Offset(glyph.centerX, centerY - 7.dp.toPx()),
                                Offset(glyph.centerX, centerY + 7.dp.toPx()),
                                strokeWidth = 2f,
                            )
                            val diamond =
                                Path().apply {
                                    moveTo(glyph.centerX, centerY - 7.dp.toPx())
                                    lineTo(glyph.centerX + 4.dp.toPx(), centerY - 3.dp.toPx())
                                    lineTo(glyph.centerX, centerY + 1.dp.toPx())
                                    lineTo(glyph.centerX - 4.dp.toPx(), centerY - 3.dp.toPx())
                                    close()
                                }
                            drawPath(diamond, color)
                        }
                        is MarkerGlyph.Interval ->
                            drawRect(color, Offset(glyph.left, centerY - 4.dp.toPx()), Size(glyph.right - glyph.left, 8.dp.toPx()))
                    }
                }
            }
        }
        visibleLanes.forEachIndexed { laneIndex, (_, markers) ->
            markers.forEach { marker ->
                val markerDescription =
                    localizedSimpleperfResource(ViewerRes.sp_dynamic_marker_description, marker.name, marker.schema)
                val glyph = MarkerPresenter.glyph(marker, viewport, widthPixels.toFloat())
                val left =
                    when (glyph) {
                        is MarkerGlyph.Point -> glyph.centerX - 6f
                        is MarkerGlyph.Interval -> glyph.left
                    }
                val width =
                    when (glyph) {
                        is MarkerGlyph.Point -> 12f
                        is MarkerGlyph.Interval -> glyph.right - glyph.left
                    }
                Box(
                    Modifier
                        .offset(x = with(density) { left.toDp() }, y = (laneIndex * MARKER_LANE_HEIGHT_DP).dp)
                        .width(with(density) { width.coerceAtLeast(12f).toDp() })
                        .height(MARKER_LANE_HEIGHT_DP.dp)
                        .testTag("marker-glyph-${marker.id.value}")
                        .clickable { onSelect(marker.id) }
                        .semantics {
                            contentDescription = markerDescription
                            selected = marker.id == selectedMarkerId
                        },
                )
            }
        }
    }
}

private const val MARKER_LANE_HEIGHT_DP = 24
