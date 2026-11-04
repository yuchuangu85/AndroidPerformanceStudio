package com.androidperformancestudio.frame.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.frame.analysis.FrameTimelineStatus
import com.androidperformancestudio.frame.presentation.generated.resources.Res
import com.androidperformancestudio.frame.presentation.generated.resources.frame_timeline_dropped
import com.androidperformancestudio.frame.presentation.generated.resources.frame_timeline_high_latency
import com.androidperformancestudio.frame.presentation.generated.resources.frame_timeline_jank
import com.androidperformancestudio.frame.presentation.generated.resources.frame_timeline_normal
import com.androidperformancestudio.frame.presentation.generated.resources.frame_timeline_other_jank
import com.androidperformancestudio.frame.presentation.generated.resources.frame_timeline_unknown
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import org.jetbrains.compose.resources.StringResource

// Perfetto's ActualFramesRenderer base colors are semantic, not theme accent colors.
@Suppress("MagicNumber")
internal fun FrameTimelineStatus.color(unknown: Color): Color =
    when (this) {
        FrameTimelineStatus.GOOD -> Color(0xFF4CAF50)
        FrameTimelineStatus.HIGH_LATENCY -> Color(0xFFC0D588)
        FrameTimelineStatus.SELF_JANK -> Color(0xFFFF5722)
        FrameTimelineStatus.OTHER_JANK -> Color(0xFFFFEB3B)
        FrameTimelineStatus.DROPPED -> Color(0xFF03A9F4)
        FrameTimelineStatus.UNKNOWN -> unknown
    }

internal val FrameTimelineStatus.labelResource: StringResource
    get() =
        when (this) {
            FrameTimelineStatus.GOOD -> Res.string.frame_timeline_normal
            FrameTimelineStatus.HIGH_LATENCY -> Res.string.frame_timeline_high_latency
            FrameTimelineStatus.SELF_JANK -> Res.string.frame_timeline_jank
            FrameTimelineStatus.OTHER_JANK -> Res.string.frame_timeline_other_jank
            FrameTimelineStatus.DROPPED -> Res.string.frame_timeline_dropped
            FrameTimelineStatus.UNKNOWN -> Res.string.frame_timeline_unknown
        }

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
internal fun FrameTimelineLegend(
    language: UiLanguage,
    unknownColor: Color,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().testTag("frame-timeline-legend"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FrameTimelineStatus.entries.forEach { status ->
            Row(
                modifier = Modifier.testTag("frame-timeline-legend-${status.name}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.size(8.dp).background(status.color(unknownColor)))
                Text(
                    localizedStringResource(status.labelResource, language),
                    style = ViewerTypography.dense,
                    color = LocalViewerColors.current.secondaryText,
                    maxLines = 1,
                )
            }
        }
    }
}
