package com.androidperformancestudio.frame.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.frame.analysis.AnalyzedFrame
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ViewerTypography
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.pow

private const val AXIS_INTERVALS = 4
private const val NANOS_PER_MILLISECOND = 1_000_000.0
private const val FRAME_CENTER_OFFSET = 0.5f

/** A duration ruler, not an assumed frame budget. Every visible duration fits below its upper bound. */
internal fun frameTimelineDurationTicks(maximumDurationNs: Long): List<Double> {
    val maximumMs = (maximumDurationNs / NANOS_PER_MILLISECOND).coerceAtLeast(1.0)
    val upperBoundMs = 2.0.pow(ceil(log2(maximumMs)))
    return (0..AXIS_INTERVALS).map { upperBoundMs * it / AXIS_INTERVALS }
}

internal fun FrameTimelineWindow.axisIndices(): List<Int> {
    if (count == 0) return emptyList()
    return (0..AXIS_INTERVALS).map { startIndex + ((count - 1L) * it / AXIS_INTERVALS).toInt() }.distinct()
}

internal fun formatFrameTimelineTick(valueMs: Double) = "%.2f".format(Locale.ROOT, valueMs).trimEnd('0').trimEnd('.')

@Composable
@Suppress("FunctionNaming", "LongMethod", "ktlint:standard:function-naming")
internal fun FrameTimelineAxes(
    durationTicksMs: List<Double>,
    window: FrameTimelineWindow,
    frames: List<AnalyzedFrame>,
    modifier: Modifier = Modifier,
    plot: @Composable () -> Unit,
) {
    val colors = LocalViewerColors.current
    val textMeasurer = rememberTextMeasurer()
    val textStyle = ViewerTypography.dense.copy(color = colors.secondaryText)
    val durationLabels = remember(durationTicksMs) { durationTicksMs.map(::formatFrameTimelineTick) }
    val durationLayouts = durationLabels.map { textMeasurer.measure(it, textStyle) }
    val gutter = with(LocalDensity.current) { durationLayouts.maxOf { it.size.width }.toDp() } + 8.dp
    val frameIndices = remember(window) { window.axisIndices() }
    val frameLabels = frameIndices.map { "#${frames[it].sample.frameId}" }
    val frameLayouts = frameLabels.map { textMeasurer.measure(it, textStyle) }
    val labelHeight = with(LocalDensity.current) { frameLayouts.maxOfOrNull { it.size.height }?.toDp() ?: 0.dp }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val actualPlotHeight =
            if (constraints.hasBoundedHeight) {
                (maxHeight - labelHeight - 8.dp).coerceAtLeast(0.dp)
            } else {
                72.dp
            }
        Row(Modifier.fillMaxWidth()) {
            Canvas(
                Modifier
                    .width(gutter)
                    .height(actualPlotHeight)
                    .testTag("frame-timeline-duration-axis")
                    .semantics { contentDescription = durationLabels.joinToString { "$it ms" } },
            ) {
                durationLayouts.forEachIndexed { index, label ->
                    val y = size.height * (1f - index.toFloat() / AXIS_INTERVALS)
                    val labelTop =
                        (y - label.size.height / 2f).coerceIn(0f, (size.height - label.size.height).coerceAtLeast(0f))
                    drawText(
                        label,
                        topLeft =
                            Offset(
                                size.width - label.size.width - 8.dp.toPx(),
                                labelTop,
                            ),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(actualPlotHeight)) { plot() }
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(labelHeight + 8.dp)
                        .testTag("frame-timeline-frame-axis")
                        .semantics { contentDescription = frameLabels.joinToString() },
                ) {
                    drawLine(colors.border, Offset.Zero, Offset(size.width, 0f))
                    frameIndices.forEachIndexed { index, frameIndex ->
                        val x = (frameIndex - window.startIndex + FRAME_CENTER_OFFSET) / window.count * size.width
                        drawLine(colors.border, Offset(x, 0f), Offset(x, 4.dp.toPx()))
                        val label = frameLayouts[index]
                        val labelLeft =
                            (x - label.size.width / 2f).coerceIn(0f, (size.width - label.size.width).coerceAtLeast(0f))
                        drawText(
                            label,
                            topLeft = Offset(labelLeft, 6.dp.toPx()),
                        )
                    }
                }
            }
        }
    }
}
