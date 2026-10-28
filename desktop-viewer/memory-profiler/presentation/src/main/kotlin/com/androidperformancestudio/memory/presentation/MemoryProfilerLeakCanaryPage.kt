@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.model.LeakCanaryReport
import com.androidperformancestudio.memory.model.LeakCanaryStatus
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.analyzed_classes
import com.androidperformancestudio.memory.presentation.generated.resources.analyzed_objects
import com.androidperformancestudio.memory.presentation.generated.resources.analyzing_memory_leaks
import com.androidperformancestudio.memory.presentation.generated.resources.application_leaks
import com.androidperformancestudio.memory.presentation.generated.resources.leak_analysis_duration
import com.androidperformancestudio.memory.presentation.generated.resources.leak_analysis_not_run
import com.androidperformancestudio.memory.presentation.generated.resources.leak_analysis_status
import com.androidperformancestudio.memory.presentation.generated.resources.leak_analyze
import com.androidperformancestudio.memory.presentation.generated.resources.leak_candidates
import com.androidperformancestudio.memory.presentation.generated.resources.leak_duration_ms
import com.androidperformancestudio.memory.presentation.generated.resources.leak_no_matching_findings
import com.androidperformancestudio.memory.presentation.generated.resources.leak_offline_analysis
import com.androidperformancestudio.memory.presentation.generated.resources.leak_retry_analysis
import com.androidperformancestudio.memory.presentation.generated.resources.library_leaks
import com.androidperformancestudio.memory.presentation.generated.resources.memory_leaks
import com.androidperformancestudio.memory.presentation.generated.resources.memory_leaks_failed
import com.androidperformancestudio.memory.presentation.generated.resources.memory_leaks_no_candidates
import com.androidperformancestudio.memory.presentation.generated.resources.memory_leaks_no_findings
import com.androidperformancestudio.memory.presentation.generated.resources.memory_leaks_no_heap
import com.androidperformancestudio.memory.presentation.generated.resources.memory_leaks_not_run
import com.androidperformancestudio.memory.presentation.generated.resources.shark_status_completed
import com.androidperformancestudio.memory.presentation.generated.resources.shark_status_failed
import com.androidperformancestudio.memory.presentation.generated.resources.shark_status_no_candidates
import com.androidperformancestudio.memory.presentation.generated.resources.unavailable
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioTokens

@Composable
@Suppress("CyclomaticComplexMethod")
public fun MemoryProfilerLeakCanaryPage(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val report = state.leakCanaryReport
    val hasHeap = state.snapshotSummary != null
    val busy = state.isDumping || report.status == LeakCanaryStatus.ANALYZING
    val findings =
        remember(report, hasHeap) {
            if (hasHeap && report.status == LeakCanaryStatus.COMPLETED) {
                (report.applicationLeaks + report.libraryLeaks).mapIndexed { index, leak ->
                    MemoryLeakFinding(index, leak, index >= report.applicationLeaks.size)
                }
            } else {
                emptyList()
            }
        }
    val snapshotKey = state.activeSnapshotId ?: state.snapshotSummary?.id
    var search by remember(snapshotKey) { mutableStateOf("") }
    var scope by remember(snapshotKey) { mutableStateOf(MemoryLeakScope.ALL) }
    var selectedIndex by remember(snapshotKey, report) { mutableStateOf(0) }
    val visible =
        remember(findings, search, scope) {
            findings.filter { finding ->
                (scope == MemoryLeakScope.ALL || finding.library == (scope == MemoryLeakScope.LIBRARY)) &&
                    listOf(
                        finding.leak.leakingClassName,
                        finding.leak.shortDescription,
                        finding.leak.signature,
                        finding.leak.leakingObjectId
                            ?.let(::leakObjectId)
                            .orEmpty(),
                    ).any { it.contains(search, ignoreCase = true) }
            }
        }
    val selected = visible.firstOrNull { it.index == selectedIndex } ?: visible.firstOrNull()
    val colors = LocalViewerColors.current
    Column(
        modifier
            .background(colors.workspace)
            .testTag("memory-profiler-leak-canary-page")
            .padding(StudioTokens.workspacePadding),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
    ) {
        LeakReportSummary(state, actions, busy, language)
        MemoryLeakLiveControls(state, actions, language)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).testTag("memory-leak-workspace")) {
            val scroll = rememberScrollState()
            val contentWidth = maxOf(maxWidth, 1120.dp)
            val scrollbarInset = if (contentWidth > maxWidth) LocalScrollbarStyle.current.thickness else 0.dp
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = scrollbarInset)
                    .horizontalScroll(scroll)
                    .requiredWidth(contentWidth)
                    .testTag("memory-leak-panes"),
            ) {
                MemoryLeakFindingsPane(
                    findings = visible,
                    selectedIndex = selected?.index,
                    search = search,
                    onSearch = { search = it },
                    scope = scope,
                    onScope = { scope = it },
                    onSelect = { finding ->
                        selectedIndex = finding.index
                        finding.leak.leakingObjectId?.let(actions.onSelectInstance)
                    },
                    language = language,
                    modifier = Modifier.width(280.dp).fillMaxHeight(),
                )
                VerticalDivider(color = colors.border)
                Box(Modifier.weight(1f).fillMaxHeight().background(colors.panel)) {
                    when {
                        busy -> LeakAnalysisFeedback(state, true, language)
                        !hasHeap || report.status != LeakCanaryStatus.COMPLETED ->
                            LeakAnalysisFeedback(state, false, language)
                        selected == null ->
                            LeakAnalysisFeedback(
                                state,
                                false,
                                language,
                                filtered = findings.isNotEmpty(),
                            )
                        else ->
                            key(snapshotKey, selected.index) {
                                MemoryLeakPathPane(selected.leak, state, actions, language)
                            }
                    }
                }
                VerticalDivider(color = colors.border)
                MemoryObjectInspector(
                    state = state,
                    actions = actions,
                    language = language,
                    modifier = Modifier.width(330.dp).fillMaxHeight().background(colors.panel),
                )
            }
            if (scrollbarInset > 0.dp) {
                HorizontalScrollbar(
                    adapter = rememberScrollbarAdapter(scroll),
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .testTag("memory-leak-horizontal-scrollbar"),
                )
            }
        }
    }
}

@Composable
private fun LeakReportSummary(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    busy: Boolean,
    language: UiLanguage,
) {
    val report = state.leakCanaryReport
    val colors = LocalViewerColors.current
    Column(Modifier.fillMaxWidth().background(colors.panel).testTag("memory-leak-report-summary")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                localizedStringResource(Res.string.memory_leaks, language),
                style = ViewerTypography.subsectionTitle,
                fontWeight = FontWeight.SemiBold,
                color = colors.primaryText,
            )
            Text(
                localizedStringResource(Res.string.leak_offline_analysis, language),
                Modifier.weight(1f),
                style = ViewerTypography.secondary,
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ProfilerCompactButton(
                text =
                    localizedStringResource(
                        if (report.status == LeakCanaryStatus.FAILED) {
                            Res.string.leak_retry_analysis
                        } else {
                            Res.string.leak_analyze
                        },
                        language,
                    ),
                enabled = state.snapshotSummary != null && !busy,
                onClick = actions.onAnalyzeLeaks,
                modifier = Modifier.testTag("memory-leak-analyze"),
            )
        }
        val status =
            when {
                busy -> Res.string.analyzing_memory_leaks
                else ->
                    when (report.status) {
                        LeakCanaryStatus.NOT_RUN -> Res.string.leak_analysis_not_run
                        LeakCanaryStatus.ANALYZING -> Res.string.analyzing_memory_leaks
                        LeakCanaryStatus.COMPLETED -> Res.string.shark_status_completed
                        LeakCanaryStatus.NO_CANDIDATES -> Res.string.shark_status_no_candidates
                        LeakCanaryStatus.FAILED -> Res.string.shark_status_failed
                    }
            }
        Text(
            localizedStringResource(
                Res.string.leak_analysis_status,
                language,
                localizedStringResource(status, language),
            ),
            Modifier.padding(horizontal = 8.dp).testTag("memory-leak-analysis-status"),
            style = ViewerTypography.secondary,
            color = if (report.status == LeakCanaryStatus.FAILED && !busy) colors.error else colors.secondaryText,
        )
        LeakReportMetrics(report, language)
        LeakReportScopeNote(report)
        HorizontalDivider(color = colors.border)
    }
}

@Composable
private fun LeakReportMetrics(
    report: LeakCanaryReport,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        listOf(
            Res.string.application_leaks to integer(report.applicationLeaks.size),
            Res.string.library_leaks to integer(report.libraryLeaks.size),
            Res.string.leak_candidates to integer(report.candidateObjectCount),
            Res.string.analyzed_objects to integer(report.analyzedObjectCount),
            Res.string.analyzed_classes to integer(report.analyzedClassCount),
            Res.string.leak_analysis_duration to (
                report.analysisDurationMillis?.let {
                    localizedStringResource(Res.string.leak_duration_ms, language, integer(it))
                } ?: localizedStringResource(Res.string.unavailable, language)
            ),
        ).forEach { (label, value) ->
            Text(
                "${localizedStringResource(label, language)}: $value",
                style = ViewerTypography.secondary,
                color = colors.secondaryText,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LeakReportScopeNote(report: LeakCanaryReport) {
    if (report.status == LeakCanaryStatus.COMPLETED) {
        report.message?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                message,
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("memory-leak-analysis-note"),
                style = ViewerTypography.secondary,
                color = LocalViewerColors.current.secondaryText,
            )
        }
    }
}

@Composable
private fun LeakAnalysisFeedback(
    state: MemoryProfilerState,
    busy: Boolean,
    language: UiLanguage,
    filtered: Boolean = false,
) {
    val report = state.leakCanaryReport
    val text =
        when {
            busy -> localizedStringResource(Res.string.analyzing_memory_leaks, language)
            state.snapshotSummary == null -> localizedStringResource(Res.string.memory_leaks_no_heap, language)
            filtered -> localizedStringResource(Res.string.leak_no_matching_findings, language)
            report.status == LeakCanaryStatus.NOT_RUN ->
                localizedStringResource(
                    Res.string.memory_leaks_not_run,
                    language,
                )
            report.status == LeakCanaryStatus.NO_CANDIDATES ->
                localizedStringResource(
                    Res.string.memory_leaks_no_candidates,
                    language,
                )
            report.status == LeakCanaryStatus.FAILED ->
                localizedStringResource(Res.string.memory_leaks_failed, language, report.message.orEmpty())
            else -> localizedStringResource(Res.string.memory_leaks_no_findings, language)
        }
    Column(
        Modifier.fillMaxSize().padding(16.dp).testTag(
            if (busy) "memory-profiler-leak-canary-loading" else "memory-profiler-leak-canary-empty",
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(
            text,
            style = ViewerTypography.bodyCompact,
            color =
                if (report.status ==
                    LeakCanaryStatus.FAILED
                ) {
                    LocalViewerColors.current.error
                } else {
                    LocalViewerColors.current.secondaryText
                },
        )
    }
}
