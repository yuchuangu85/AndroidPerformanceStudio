@file:Suppress(
    "FunctionNaming",
    "MagicNumber",
    "LongMethod",
    "MaxLineLength",
    "LongParameterList",
    "TooManyFunctions",
    "ktlint:standard:function-naming",
)

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.model.LeakCanaryLeak
import com.androidperformancestudio.memory.model.LeakCanaryLiveSession
import com.androidperformancestudio.memory.model.LeakCanaryLiveStatus
import com.androidperformancestudio.memory.model.LeakCanaryReport
import com.androidperformancestudio.memory.model.LeakCanaryStatus
import com.androidperformancestudio.memory.model.LeakCanaryTraceElement
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.agent_connected
import com.androidperformancestudio.memory.presentation.generated.resources.agent_not_connected
import com.androidperformancestudio.memory.presentation.generated.resources.analyzed_classes
import com.androidperformancestudio.memory.presentation.generated.resources.analyzed_objects
import com.androidperformancestudio.memory.presentation.generated.resources.analyzing_memory_leaks
import com.androidperformancestudio.memory.presentation.generated.resources.application_leaks
import com.androidperformancestudio.memory.presentation.generated.resources.gc_root
import com.androidperformancestudio.memory.presentation.generated.resources.leak_trace
import com.androidperformancestudio.memory.presentation.generated.resources.leaking
import com.androidperformancestudio.memory.presentation.generated.resources.library_leaks
import com.androidperformancestudio.memory.presentation.generated.resources.live_event_summary
import com.androidperformancestudio.memory.presentation.generated.resources.live_events
import com.androidperformancestudio.memory.presentation.generated.resources.memory_leaks
import com.androidperformancestudio.memory.presentation.generated.resources.memory_leaks_detail
import com.androidperformancestudio.memory.presentation.generated.resources.memory_leaks_failed
import com.androidperformancestudio.memory.presentation.generated.resources.memory_leaks_no_candidates
import com.androidperformancestudio.memory.presentation.generated.resources.memory_leaks_no_findings
import com.androidperformancestudio.memory.presentation.generated.resources.memory_leaks_no_heap
import com.androidperformancestudio.memory.presentation.generated.resources.memory_leaks_not_run
import com.androidperformancestudio.memory.presentation.generated.resources.no_live_events
import com.androidperformancestudio.memory.presentation.generated.resources.not_leaking
import com.androidperformancestudio.memory.presentation.generated.resources.retained_bytes
import com.androidperformancestudio.memory.presentation.generated.resources.retained_objects
import com.androidperformancestudio.memory.presentation.generated.resources.shark_status_completed
import com.androidperformancestudio.memory.presentation.generated.resources.shark_status_failed
import com.androidperformancestudio.memory.presentation.generated.resources.shark_status_no_candidates
import com.androidperformancestudio.memory.presentation.generated.resources.shark_status_not_run
import com.androidperformancestudio.memory.presentation.generated.resources.trace_reason
import com.androidperformancestudio.memory.presentation.generated.resources.unknown_status
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerDimensions
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource

@Composable
public fun MemoryProfilerLeakCanaryPage(
    report: LeakCanaryReport,
    hasHeap: Boolean,
    isLoading: Boolean = false,
    liveSession: LeakCanaryLiveSession = LeakCanaryLiveSession(),
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    Surface(
        modifier = modifier.testTag("memory-profiler-leak-canary-page"),
        color = colors.workspace,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        localizedStringResource(Res.string.memory_leaks, language),
                        style = ViewerTypography.pageTitle,
                        color = colors.primaryText,
                    )
                    Text(
                        localizedStringResource(Res.string.memory_leaks_detail, language),
                        style = ViewerTypography.secondary,
                        color = colors.secondaryText,
                    )
                }
                LeakCanaryStatusChip(report.status, language)
                Spacer(Modifier.width(8.dp))
                LiveAgentStatusChip(liveSession, language)
            }

            val hasLiveSession =
                liveSession.status == LeakCanaryLiveStatus.RUNNING ||
                    liveSession.events.isNotEmpty()
            if (!hasHeap && !hasLiveSession) {
                EmptyLeakCanaryState(
                    text = localizedStringResource(Res.string.memory_leaks_no_heap, language),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else if (isLoading && liveSession.status != LeakCanaryLiveStatus.RUNNING) {
                LeakCanaryLoadingState(language, Modifier.fillMaxWidth().weight(1f))
            } else {
                LiveEventsSection(liveSession, language)
                LeakCanarySummary(report, language)
                when (report.status) {
                    LeakCanaryStatus.ANALYZING ->
                        EmptyLeakCanaryState(localizedStringResource(Res.string.analyzing_memory_leaks, language))
                    LeakCanaryStatus.NO_CANDIDATES ->
                        EmptyLeakCanaryState(localizedStringResource(Res.string.memory_leaks_no_candidates, language))
                    LeakCanaryStatus.NOT_RUN ->
                        EmptyLeakCanaryState(localizedStringResource(Res.string.memory_leaks_not_run, language))
                    LeakCanaryStatus.FAILED ->
                        EmptyLeakCanaryState(
                            localizedStringResource(
                                Res.string.memory_leaks_failed,
                                language,
                                report.message.orEmpty(),
                            ),
                            tone = colors.error,
                        )
                    LeakCanaryStatus.COMPLETED ->
                        if (report.totalLeaks == 0) {
                            EmptyLeakCanaryState(localizedStringResource(Res.string.memory_leaks_no_findings, language))
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (report.applicationLeaks.isNotEmpty()) {
                                    item { LeakSectionTitle(localizedStringResource(Res.string.application_leaks, language)) }
                                    itemsIndexed(report.applicationLeaks) { index, leak ->
                                        LeakCanaryFindingCard(index + 1, leak, language)
                                    }
                                }
                                if (report.libraryLeaks.isNotEmpty()) {
                                    item { LeakSectionTitle(localizedStringResource(Res.string.library_leaks, language)) }
                                    itemsIndexed(report.libraryLeaks) { index, leak ->
                                        LeakCanaryFindingCard(index + 1, leak, language)
                                    }
                                }
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun LiveAgentStatusChip(
    session: LeakCanaryLiveSession,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    val connected = session.status == LeakCanaryLiveStatus.RUNNING
    Text(
        text =
            localizedStringResource(
                if (connected) Res.string.agent_connected else Res.string.agent_not_connected,
                language,
            ),
        style = ViewerTypography.secondary,
        color = if (connected) colors.success else colors.mutedText,
        modifier =
            Modifier
                .background(
                    (if (connected) colors.success else colors.mutedText).copy(alpha = 0.12f),
                    RoundedCornerShape(ViewerDimensions.controlRadius),
                ).padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

@Composable
private fun LiveEventsSection(
    session: LeakCanaryLiveSession,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    if (session.status == LeakCanaryLiveStatus.DISCONNECTED && session.events.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("memory-profiler-live-events"),
        color = colors.panel,
        shape = RoundedCornerShape(ViewerDimensions.controlRadius),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    localizedStringResource(Res.string.live_events, language),
                    style = ViewerTypography.subsectionTitle,
                    color = colors.primaryText,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    localizedStringResource(
                        Res.string.live_event_summary,
                        language,
                        session.events.size,
                        session.droppedEvents,
                    ),
                    style = ViewerTypography.label,
                    color = colors.secondaryText,
                )
            }
            if (session.events.isEmpty()) {
                Text(
                    if (session.message.isNullOrBlank()) {
                        localizedStringResource(Res.string.no_live_events, language)
                    } else {
                        session.message.orEmpty()
                    },
                    style = ViewerTypography.secondary,
                    color = colors.secondaryText,
                )
            } else {
                session.events.takeLast(12).asReversed().forEach { event ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = event.kind,
                            style = ViewerTypography.label,
                            color = if (event.retained == true) colors.error else colors.accent,
                            modifier = Modifier.width(150.dp),
                        )
                        Text(
                            text = event.className ?: event.description.orEmpty(),
                            style = ViewerTypography.secondary,
                            color = colors.primaryText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LeakCanarySummary(
    report: LeakCanaryReport,
    language: UiLanguage,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LeakMetricCard(
            label = localizedStringResource(Res.string.application_leaks, language),
            value = integer(report.applicationLeaks.size),
            modifier = Modifier.weight(1f),
        )
        LeakMetricCard(
            label = localizedStringResource(Res.string.library_leaks, language),
            value = integer(report.libraryLeaks.size),
            modifier = Modifier.weight(1f),
        )
        LeakMetricCard(
            label = localizedStringResource(Res.string.analyzed_objects, language),
            value = integer(report.analyzedObjectCount),
            modifier = Modifier.weight(1f),
        )
        LeakMetricCard(
            label = localizedStringResource(Res.string.analyzed_classes, language),
            value = integer(report.analyzedClassCount),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LeakMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    Surface(
        modifier = modifier,
        color = colors.panel,
        shape = RoundedCornerShape(ViewerDimensions.controlRadius),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(label, style = ViewerTypography.label, color = colors.secondaryText, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text(value, style = ViewerTypography.metric, color = colors.accent, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LeakSectionTitle(text: String) {
    Text(
        text = text,
        style = ViewerTypography.sectionTitle,
        color = LocalViewerColors.current.primaryText,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun LeakCanaryFindingCard(
    index: Int,
    leak: LeakCanaryLeak,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("memory-profiler-leak-finding-$index"),
        color = colors.panel,
        shape = RoundedCornerShape(ViewerDimensions.controlRadius),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = leak.shortDescription,
                        style = ViewerTypography.cardTitle,
                        color = colors.primaryText,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = leak.leakingClassName,
                        style = ViewerTypography.bodyCompact,
                        color = colors.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("#$index", style = ViewerTypography.label, color = colors.mutedText)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                leak.retainedHeapByteSize?.let { bytes ->
                    Text(
                        localizedStringResource(Res.string.retained_bytes, language, formatBytes(bytes.toLong())),
                        style = ViewerTypography.secondary,
                        color = colors.warning,
                    )
                }
                leak.retainedObjectCount?.let { count ->
                    Text(
                        localizedStringResource(Res.string.retained_objects, language, integer(count)),
                        style = ViewerTypography.secondary,
                        color = colors.secondaryText,
                    )
                }
                leak.gcRootType?.let { root ->
                    Text(
                        localizedStringResource(Res.string.gc_root, language, root),
                        style = ViewerTypography.secondary,
                        color = colors.secondaryText,
                    )
                }
            }
            Text(
                localizedStringResource(Res.string.leak_trace, language),
                style = ViewerTypography.subsectionTitle,
                color = colors.primaryText,
            )
            leak.trace.forEachIndexed { traceIndex, element ->
                LeakTraceRow(traceIndex, element, language)
            }
        }
    }
}

@Composable
private fun LeakTraceRow(
    index: Int,
    element: LeakCanaryTraceElement,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    val leaking = element.leakingStatus?.equals("LEAKING", ignoreCase = true) == true
    val statusColor = if (leaking) colors.error else colors.success
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = (index * 12).coerceAtMost(72).dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = if (index == 0) "●" else "↳",
            style = ViewerTypography.bodyCompact,
            color = statusColor,
            modifier = Modifier.width(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text =
                    buildString {
                        append(element.className)
                        element.referenceName?.takeIf(String::isNotBlank)?.let { append(".$it") }
                        element.referenceType?.takeIf(String::isNotBlank)?.let { append(" [$it]") }
                    },
                style = ViewerTypography.bodyCompact,
                color = colors.primaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val statusText =
                when {
                    leaking -> localizedStringResource(Res.string.leaking, language)
                    element.leakingStatus?.equals("NOT_LEAKING", ignoreCase = true) == true ->
                        localizedStringResource(Res.string.not_leaking, language)
                    else -> localizedStringResource(Res.string.unknown_status, language)
                }
            element.leakingStatusReason?.takeIf(String::isNotBlank)?.let { reason ->
                Text(
                    localizedStringResource(Res.string.trace_reason, language, statusText, reason),
                    style = ViewerTypography.label,
                    color = statusColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } ?: Text(statusText, style = ViewerTypography.label, color = statusColor)
        }
    }
}

@Composable
private fun LeakCanaryStatusChip(
    status: LeakCanaryStatus,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    val (label, tint) =
        when (status) {
            LeakCanaryStatus.COMPLETED -> localizedStringResource(Res.string.shark_status_completed, language) to colors.success
            LeakCanaryStatus.ANALYZING -> localizedStringResource(Res.string.analyzing_memory_leaks, language) to colors.accent
            LeakCanaryStatus.NO_CANDIDATES -> localizedStringResource(Res.string.shark_status_no_candidates, language) to colors.warning
            LeakCanaryStatus.NOT_RUN -> localizedStringResource(Res.string.shark_status_not_run, language) to colors.mutedText
            LeakCanaryStatus.FAILED -> localizedStringResource(Res.string.shark_status_failed, language) to colors.error
        }
    Text(
        text = label,
        style = ViewerTypography.secondary,
        color = tint,
        modifier =
            Modifier
                .background(tint.copy(alpha = 0.12f), RoundedCornerShape(ViewerDimensions.controlRadius))
                .border(ViewerDimensions.hairline, tint.copy(alpha = 0.45f), RoundedCornerShape(ViewerDimensions.controlRadius))
                .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
private fun LeakCanaryLoadingState(
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    Surface(
        modifier = modifier.fillMaxWidth().testTag("memory-profiler-leak-canary-loading"),
        color = colors.panel,
        shape = RoundedCornerShape(ViewerDimensions.controlRadius),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(color = colors.accent)
            Spacer(Modifier.height(10.dp))
            Text(
                localizedStringResource(Res.string.analyzing_memory_leaks, language),
                style = ViewerTypography.body,
                color = colors.secondaryText,
            )
        }
    }
}

@Composable
private fun EmptyLeakCanaryState(
    text: String,
    modifier: Modifier = Modifier,
    tone: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("memory-profiler-leak-canary-empty"),
        color = LocalViewerColors.current.panel,
        shape = RoundedCornerShape(ViewerDimensions.controlRadius),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text, style = ViewerTypography.body, color = tone)
        }
    }
}
