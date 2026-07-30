@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("LongMethod", "LongParameterList", "MagicNumber", "MaxLineLength", "TooManyFunctions")

package com.androidperformancestudio.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.presentation.generated.resources.ViewerRes
import com.androidperformancestudio.profileanalysis.AnalysisTimeRange
import com.androidperformancestudio.storage.MarkerProjectionSnapshot
import com.androidperformancestudio.storage.PanelProjection
import com.androidperformancestudio.storage.ProfileMarkerId
import com.androidperformancestudio.storage.ThreadTimelineTrack
import com.androidperformancestudio.storage.TimelineBucket
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.visualization.NavigationAction
import com.androidperformancestudio.visualization.TimeViewport
import com.androidperformancestudio.visualization.TimelineCanvas
import com.androidperformancestudio.visualization.TimelineColumn
import com.androidperformancestudio.visualization.TimelineFrame
import com.androidperformancestudio.visualization.navigate
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

@Composable
@Suppress("CyclomaticComplexMethod", "FunctionName", "ktlint:standard:function-naming")
internal fun TimelineReport(
    state: ReportState,
    report: ReportData,
    actions: ReportActions,
    style: ViewerColors,
) {
    val fullStart = report.sessionOverview.startNanos ?: 0L
    val fullEnd = (report.sessionOverview.endNanosInclusive ?: fullStart).safeTimelineIncrement()
    val bounds = TimeViewport(fullStart, fullEnd.coerceAtLeast(fullStart + 1))
    val viewport =
        TimeViewport(
            state.filter.startNanosInclusive ?: bounds.startNanos,
            state.filter.endNanosExclusive ?: bounds.endNanosExclusive,
        )
    val tracks = report.firefoxTimelineTracks()
    val groups = tracks.groupBy(ThreadTimelineTrack::processId).toSortedMap()
    val defaultSelectedTrackId =
        groups.values
            .firstOrNull()
            ?.firefoxOrderedTracks()
            ?.firstOrNull()
            ?.id
    val shortcutFocusRequester = remember { FocusRequester() }

    fun navigate(action: NavigationAction) {
        val next = viewport.navigate(action, bounds)
        actions.onTimeRange(next.startNanos, next.endNanosExclusive)
    }

    LaunchedEffect(shortcutFocusRequester) { shortcutFocusRequester.requestFocus() }
    Column(
        Modifier
            .fillMaxSize()
            .focusRequester(shortcutFocusRequester)
            .onPreviewKeyEvent { event -> handleKey(event, ::navigate) }
            .focusable()
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                if (event.keyboardModifiers.isCtrlPressed) {
                    when {
                        change.scrollDelta.y < 0f -> navigate(NavigationAction.ZOOM_IN)
                        change.scrollDelta.y > 0f -> navigate(NavigationAction.ZOOM_OUT)
                    }
                }
            },
    ) {
        FirefoxTimelineHeader(
            visibleTrackCount = tracks.size,
            totalTrackCount = tracks.size,
            viewport = viewport,
            zeroAtNanos = fullStart,
            style = style,
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .background(style.field),
        ) {
            groups.forEach { (_, processTracks) ->
                val ordered = processTracks.firefoxOrderedTracks()
                ordered.firstOrNull()?.let { global ->
                    FirefoxThreadTrack(
                        track = global,
                        local = false,
                        selected = global.isFirefoxSelected(state, defaultSelectedTrackId),
                        viewport = viewport,
                        previewRange = state.callStackQuery.previewRange,
                        style = style,
                        actions = actions,
                    )
                }
                if (ordered.size > 1) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(firefoxLocalTrackBackground(style))
                            .drawBehind {
                                drawLine(style.border, Offset.Zero, Offset(size.width, 0f))
                            },
                    ) {
                        ordered.drop(1).forEach { local ->
                            FirefoxThreadTrack(
                                track = local,
                                local = true,
                                selected = local.isFirefoxSelected(state, defaultSelectedTrackId),
                                viewport = viewport,
                                previewRange = state.callStackQuery.previewRange,
                                style = style,
                                actions = actions,
                            )
                        }
                    }
                }
            }
            (report.markers as? PanelProjection.Ready)?.value?.let { markers ->
                FirefoxMarkerTimelineLanes(
                    snapshot = markers,
                    viewport = viewport,
                    selectedMarkerId = state.workspace.selections.markerId,
                    style = style,
                    onSelect = actions.onSelectMarker,
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxMarkerTimelineLanes(
    snapshot: MarkerProjectionSnapshot,
    viewport: TimeViewport,
    selectedMarkerId: ProfileMarkerId?,
    style: ViewerColors,
    onSelect: (ProfileMarkerId?) -> Unit,
) {
    val markersById = remember(snapshot) { snapshot.markers.associateBy { it.id } }
    snapshot.lanes.forEach { lane ->
        Row(Modifier.fillMaxWidth().height(FIREFOX_MARKER_LANE_HEIGHT).background(style.toolbar)) {
            Text(
                lane.label,
                modifier = Modifier.width(FIREFOX_TIMELINE_LABEL_WIDTH).padding(horizontal = 8.dp),
                color = style.secondaryText,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                lane.markerIds.mapNotNull(markersById::get).forEach { marker ->
                    if (marker.endNanosExclusive > viewport.startNanos && marker.startNanos < viewport.endNanosExclusive) {
                        val markerDescription =
                            localizedStringResource(
                                ViewerRes.sp_timeline_marker_description_format,
                                currentSimpleperfLanguage(),
                                marker.name,
                            )
                        val fraction =
                            (marker.startNanos - viewport.startNanos).toDouble() /
                                (viewport.endNanosExclusive - viewport.startNanos).coerceAtLeast(1L)
                        Box(
                            Modifier
                                .offset(x = maxWidth * fraction.toFloat())
                                .width(if (marker.interval) 8.dp else 4.dp)
                                .fillMaxHeight()
                                .testTag("timeline-marker-${marker.id.value}")
                                .background(
                                    if (marker.id == selectedMarkerId) style.accent else style.warning,
                                ).clickable { onSelect(marker.id) }
                                .semantics {
                                    contentDescription = markerDescription
                                    selected = marker.id == selectedMarkerId
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxTimelineHeader(
    visibleTrackCount: Int,
    totalTrackCount: Int,
    viewport: TimeViewport,
    zeroAtNanos: Long,
    style: ViewerColors,
) {
    val visibleTracksDescription =
        localizedStringResource(
            ViewerRes.sp_timeline_visible_tracks_format,
            currentSimpleperfLanguage(),
            visibleTrackCount,
            totalTrackCount,
        )
    Row(
        Modifier
            .fillMaxWidth()
            .height(FIREFOX_TIMELINE_HEADER_HEIGHT)
            .background(style.toolbar)
            .drawBehind { drawLine(style.border, Offset(0f, size.height), Offset(size.width, size.height)) },
    ) {
        Row(
            Modifier
                .width(FIREFOX_TIMELINE_LABEL_WIDTH)
                .fillMaxHeight()
                .drawBehind { drawLine(style.border, Offset(size.width, 0f), Offset(size.width, size.height)) }
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                visibleTracksDescription,
                modifier = Modifier.weight(1f),
                color = style.text,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text("▾", color = style.secondaryText, fontSize = 9.sp)
        }
        FirefoxTimelineRuler(viewport, zeroAtNanos, style, Modifier.weight(1f))
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxTimelineRuler(
    viewport: TimeViewport,
    zeroAtNanos: Long,
    style: ViewerColors,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val notchCount = floor(maxWidth / FIREFOX_RULER_MIN_NOTCH_DISTANCE).toInt().coerceAtLeast(1)
        val duration = viewport.endNanosExclusive - viewport.startNanos
        repeat(notchCount + 1) { index ->
            val fraction = index.toFloat() / notchCount
            val x = maxWidth * fraction
            val timestamp = viewport.startNanos + (duration.toDouble() * fraction).roundToLong()
            Box(
                Modifier
                    .offset(x = x - FIREFOX_RULER_LABEL_WIDTH)
                    .width(FIREFOX_RULER_LABEL_WIDTH)
                    .fillMaxHeight()
                    .drawBehind {
                        drawLine(
                            style.border,
                            Offset(size.width - 1f, size.height - 2.dp.toPx()),
                            Offset(size.width - 1f, size.height),
                        )
                    },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    formatFirefoxTimelineTime(timestamp - zeroAtNanos),
                    modifier = Modifier.padding(end = 5.dp),
                    color = style.secondaryText,
                    fontSize = 9.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxThreadTrack(
    track: ThreadTimelineTrack,
    local: Boolean,
    selected: Boolean,
    viewport: TimeViewport,
    previewRange: AnalysisTimeRange?,
    style: ViewerColors,
    actions: ReportActions,
) {
    val trackDescription =
        localizedStringResource(
            ViewerRes.sp_timeline_track_description_format,
            currentSimpleperfLanguage(),
            track.name,
            track.threadId,
        )
    val rowBackground = if (selected) style.accent.copy(alpha = 0.10f) else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .height(FIREFOX_THREAD_TRACK_HEIGHT)
            .padding(start = if (local) FIREFOX_LOCAL_TRACK_MARGIN else 0.dp)
            .background(rowBackground)
            .drawBehind {
                drawLine(style.border, Offset(0f, 0f), Offset(size.width, 0f))
                if (selected) drawRect(style.accent, size = Size(3.dp.toPx(), size.height))
                if (local) drawLine(style.border, Offset(0f, 0f), Offset(0f, size.height))
            }.semantics {
                contentDescription = trackDescription
                this.selected = selected
            },
    ) {
        FirefoxTrackLabel(
            track,
            local,
            style,
            actions,
            Modifier.width(
                FIREFOX_TIMELINE_LABEL_WIDTH - if (local) FIREFOX_LOCAL_TRACK_MARGIN else 0.dp,
            ),
        )
        FirefoxTrackGraph(track, viewport, previewRange, style, actions, Modifier.weight(1f))
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxTrackLabel(
    track: ThreadTimelineTrack,
    local: Boolean,
    style: ViewerColors,
    actions: ReportActions,
    modifier: Modifier,
) {
    Row(
        modifier
            .fillMaxHeight()
            .drawBehind { drawLine(style.border, Offset(size.width, 0f), Offset(size.width, size.height)) }
            .clickable { actions.onThreads(setOf(track.threadId)) }
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            track.name,
            modifier = Modifier.weight(1f),
            color = style.text,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (local) "(${track.threadId})" else "(${track.processId})",
            color = style.secondaryText,
            fontSize = 9.sp,
            maxLines = 1,
        )
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxTrackGraph(
    track: ThreadTimelineTrack,
    viewport: TimeViewport,
    previewRange: AnalysisTimeRange?,
    style: ViewerColors,
    actions: ReportActions,
    modifier: Modifier,
) {
    val graphDescription =
        localizedStringResource(
            ViewerRes.sp_timeline_thread_activity_description_format,
            currentSimpleperfLanguage(),
            track.name,
        )
    val overlayModifier =
        modifier
            .fillMaxHeight()
            .background(style.panel)
            .drawWithContent {
                drawContent()
                previewRange?.let { range ->
                    val duration = viewport.endNanosExclusive - viewport.startNanos
                    if (duration > 0) {
                        val left =
                            ((range.startNanosInclusive - viewport.startNanos).toDouble() / duration)
                                .coerceIn(0.0, 1.0)
                                .toFloat() * size.width
                        val right =
                            ((range.endNanosExclusive - viewport.startNanos).toDouble() / duration)
                                .coerceIn(0.0, 1.0)
                                .toFloat() * size.width
                        val x = minOf(left, right)
                        val width = kotlin.math.abs(right - left)
                        drawRect(style.accent.copy(alpha = 0.18f), Offset(x, 0f), Size(width, size.height))
                        drawLine(style.accent.copy(alpha = 0.75f), Offset(x, 0f), Offset(x, size.height))
                        drawLine(
                            style.accent.copy(alpha = 0.75f),
                            Offset(x + width, 0f),
                            Offset(x + width, size.height),
                        )
                    }
                }
            }.semantics { contentDescription = graphDescription }
    Column(overlayModifier) {
        TimelineCanvas(
            frame = TimelineFrame(track.buckets.map { TimelineColumn(it.eventWeight) }),
            color = firefoxActivityColor(style),
            viewport = viewport,
            onRangePreview = { range ->
                actions.onFlamePreviewRange(AnalysisTimeRange(range.startNanos, range.endNanosExclusive))
            },
            onRangeCommit = { range -> actions.onTimeRange(range.startNanos, range.endNanosExclusive) },
            onRangeCancel = actions.onCancelFlamePreview,
            modifier = Modifier.fillMaxWidth().height(FIREFOX_ACTIVITY_GRAPH_HEIGHT),
        )
        FirefoxSampleGraph(track.buckets, style)
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun FirefoxSampleGraph(
    buckets: List<TimelineBucket>,
    style: ViewerColors,
) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(top = 1.dp)
            .height(FIREFOX_SAMPLE_GRAPH_HEIGHT),
    ) {
        if (buckets.isEmpty()) return@Canvas
        val columnWidth = size.width / buckets.size
        buckets.forEachIndexed { index, bucket ->
            if (bucket.sampleCount > 0) {
                val x = (index + 0.5f) * columnWidth
                drawLine(
                    color = style.text.copy(alpha = 0.72f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
    }
}

private fun ReportData.firefoxTimelineTracks(): List<ThreadTimelineTrack> {
    if (timelineTracks.isNotEmpty()) return timelineTracks
    val totalWeight = sessionThreads.sumOf { it.totalEventCount }.coerceAtLeast(1L)
    return sessionThreads.mapIndexed { index, thread ->
        ThreadTimelineTrack(
            id = "fallback:${thread.processId}:${thread.threadId}:$index",
            processId = thread.processId,
            threadId = thread.threadId,
            name = thread.name,
            buckets =
                timeline.map { bucket ->
                    bucket.copy(eventWeight = bucket.eventWeight * thread.totalEventCount / totalWeight)
                },
        )
    }
}

private fun List<ThreadTimelineTrack>.firefoxOrderedTracks(): List<ThreadTimelineTrack> {
    if (isEmpty()) return emptyList()
    val global = firstOrNull { it.threadId == it.processId } ?: maxBy { it.buckets.sumOf(TimelineBucket::eventWeight) }
    return listOf(global) +
        asSequence()
            .filterNot { it.id == global.id }
            .sortedWith(
                compareByDescending<ThreadTimelineTrack> { it.buckets.sumOf(TimelineBucket::eventWeight) }
                    .thenBy(ThreadTimelineTrack::threadId)
                    .thenBy(ThreadTimelineTrack::id),
            ).toList()
}

private fun ThreadTimelineTrack.isFirefoxSelected(
    state: ReportState,
    defaultSelectedTrackId: String?,
): Boolean =
    if (state.filter.threadIds.isEmpty()) {
        id == defaultSelectedTrackId
    } else {
        threadId in state.filter.threadIds
    }

private fun formatFirefoxTimelineTime(nanos: Long): String {
    val absolute = kotlin.math.abs(nanos)
    return when {
        absolute < 1_000L -> "$nanos ns"
        absolute < 1_000_000L -> formatTimelineUnit(nanos / 1_000.0, "μs")
        absolute < 1_000_000_000L -> formatTimelineUnit(nanos / 1_000_000.0, "ms")
        else -> formatTimelineUnit(nanos / 1_000_000_000.0, "s")
    }
}

private fun formatTimelineUnit(
    value: Double,
    unit: String,
): String {
    val precision = if (kotlin.math.abs(value) >= 10.0) 0 else 1
    return String.format(Locale.US, "%.${precision}f %s", value, unit)
}

private fun firefoxActivityColor(style: ViewerColors): Color = if (style.panel.red < 0.5f) Color(0xFF75A7D4) else Color(0xFF5B8DB8)

private fun firefoxLocalTrackBackground(style: ViewerColors): Color = if (style.panel.red < 0.5f) Color(0xFF202124) else Color(0xFFF0F0F4)

private fun Long.safeTimelineIncrement(): Long = if (this == Long.MAX_VALUE) this else this + 1

private val FIREFOX_TIMELINE_HEADER_HEIGHT = 20.dp
private val FIREFOX_TIMELINE_LABEL_WIDTH = 150.dp
private val FIREFOX_LOCAL_TRACK_MARGIN = 15.dp
private val FIREFOX_ACTIVITY_GRAPH_HEIGHT = 25.dp
private val FIREFOX_SAMPLE_GRAPH_HEIGHT = 5.dp
private val FIREFOX_THREAD_TRACK_HEIGHT = 31.dp
private val FIREFOX_MARKER_LANE_HEIGHT = 24.dp
private val FIREFOX_RULER_MIN_NOTCH_DISTANCE = 55.dp
private val FIREFOX_RULER_LABEL_WIDTH = 55.dp
