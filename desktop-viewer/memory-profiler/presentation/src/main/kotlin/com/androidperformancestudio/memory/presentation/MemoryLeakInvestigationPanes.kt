@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "MagicNumber", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.model.LeakCanaryLeak
import com.androidperformancestudio.memory.model.LeakCanaryTraceElement
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.application_leaks
import com.androidperformancestudio.memory.presentation.generated.resources.gc_root
import com.androidperformancestudio.memory.presentation.generated.resources.leak_all_findings
import com.androidperformancestudio.memory.presentation.generated.resources.leak_findings
import com.androidperformancestudio.memory.presentation.generated.resources.leak_findings_filter
import com.androidperformancestudio.memory.presentation.generated.resources.leak_no_matching_findings
import com.androidperformancestudio.memory.presentation.generated.resources.leak_no_path
import com.androidperformancestudio.memory.presentation.generated.resources.leak_object_unavailable
import com.androidperformancestudio.memory.presentation.generated.resources.leak_retained_instance
import com.androidperformancestudio.memory.presentation.generated.resources.leak_search_findings
import com.androidperformancestudio.memory.presentation.generated.resources.leak_suspect_reference
import com.androidperformancestudio.memory.presentation.generated.resources.leak_suspects
import com.androidperformancestudio.memory.presentation.generated.resources.leak_trace
import com.androidperformancestudio.memory.presentation.generated.resources.leaking
import com.androidperformancestudio.memory.presentation.generated.resources.library_leaks
import com.androidperformancestudio.memory.presentation.generated.resources.not_leaking
import com.androidperformancestudio.memory.presentation.generated.resources.retained_bytes
import com.androidperformancestudio.memory.presentation.generated.resources.retained_objects
import com.androidperformancestudio.memory.presentation.generated.resources.unknown_status
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioSearchField
import com.androidperformancestudio.ui_components.generated.resources.ic_stat_attention
import org.jetbrains.compose.resources.painterResource
import com.androidperformancestudio.ui_components.generated.resources.Res as UiRes

internal enum class MemoryLeakScope { ALL, APPLICATION, LIBRARY }

internal data class MemoryLeakFinding(
    val index: Int,
    val leak: LeakCanaryLeak,
    val library: Boolean,
)

internal fun leakObjectId(id: Long): String = "0x${java.lang.Long.toHexString(id)}"

@Composable
internal fun MemoryLeakFindingsPane(
    findings: List<MemoryLeakFinding>,
    selectedIndex: Int?,
    search: String,
    onSearch: (String) -> Unit,
    scope: MemoryLeakScope,
    onScope: (MemoryLeakScope) -> Unit,
    onSelect: (MemoryLeakFinding) -> Unit,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    Column(modifier.background(colors.panel).testTag("memory-leak-findings")) {
        Text(
            "${localizedStringResource(Res.string.leak_findings, language)} (${findings.size})",
            Modifier.padding(8.dp),
            style = ViewerTypography.subsectionTitle,
            fontWeight = FontWeight.SemiBold,
        )
        StudioSearchField(
            value = search,
            onValueChange = onSearch,
            placeholder = localizedStringResource(Res.string.leak_search_findings, language),
            modifier =
                Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("memory-leak-search").semantics {
                    contentDescription = localizedStringResource(Res.string.leak_search_findings, language)
                },
            compact = true,
        )
        val labels =
            mapOf(
                MemoryLeakScope.ALL to localizedStringResource(Res.string.leak_all_findings, language),
                MemoryLeakScope.APPLICATION to localizedStringResource(Res.string.application_leaks, language),
                MemoryLeakScope.LIBRARY to localizedStringResource(Res.string.library_leaks, language),
            )
        DropdownSelector(
            items = MemoryLeakScope.entries,
            selectedItem = scope,
            onItemSelected = onScope,
            itemLabel = { labels.getValue(it) },
            placeholder = labels.getValue(MemoryLeakScope.ALL),
            selectorDescription = localizedStringResource(Res.string.leak_findings_filter, language),
            fillWidth = true,
            modifier = Modifier.fillMaxWidth().padding(8.dp).testTag("memory-leak-scope"),
            menuModifier = Modifier.testTag("memory-leak-scope-menu"),
        )
        HorizontalDivider(color = colors.border)
        val list = rememberLazyListState()
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), state = list) {
                if (findings.isEmpty()) {
                    item {
                        Text(
                            localizedStringResource(Res.string.leak_no_matching_findings, language),
                            Modifier.padding(12.dp),
                            style = ViewerTypography.secondary,
                            color = colors.secondaryText,
                        )
                    }
                }
                items(findings, key = { it.index }) { finding ->
                    val leak = finding.leak
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(if (selectedIndex == finding.index) colors.selectedRow else colors.panel)
                            .selectable(selectedIndex == finding.index, role = Role.Tab) { onSelect(finding) }
                            .testTag("memory-leak-finding-${finding.index}")
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painterResource(UiRes.drawable.ic_stat_attention),
                                contentDescription = localizedStringResource(Res.string.leak_suspects, language),
                                modifier = Modifier.size(14.dp),
                                tint = colors.warning,
                            )
                            Text(
                                leak.leakingClassName,
                                style = ViewerTypography.bodyCompact,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.primaryText,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            leak.shortDescription,
                            style = ViewerTypography.secondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            localizedStringResource(
                                if (finding.library) Res.string.library_leaks else Res.string.application_leaks,
                                language,
                            ),
                            style = ViewerTypography.label,
                            color = colors.secondaryText,
                        )
                        Text(
                            leak.leakingObjectId?.let(::leakObjectId)
                                ?: localizedStringResource(Res.string.leak_object_unavailable, language),
                            style = ViewerTypography.secondary,
                            color = colors.secondaryText,
                        )
                        leak.retainedHeapByteSize?.let {
                            Text(
                                localizedStringResource(Res.string.retained_bytes, language, formatBytes(it.toLong())),
                                style = ViewerTypography.secondary,
                                color = colors.secondaryText,
                            )
                        }
                    }
                    HorizontalDivider(color = colors.border)
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
internal fun MemoryLeakPathPane(
    leak: LeakCanaryLeak,
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize().testTag("memory-leak-path")) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), state = list) {
            item {
                Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        localizedStringResource(Res.string.leak_retained_instance, language),
                        style = ViewerTypography.subsectionTitle,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MemoryLeakWarningIcon(language)
                        Text(leak.leakingClassName, style = ViewerTypography.bodyCompact, color = colors.primaryText)
                    }
                    leak.leakingObjectId?.let { id ->
                        ProfilerCompactButton(
                            text = leakObjectId(id),
                            onClick = { actions.onSelectInstance(id) },
                            modifier = Modifier.testTag("memory-leak-retained-object"),
                        )
                    } ?: Text(
                        localizedStringResource(Res.string.leak_object_unavailable, language),
                        style = ViewerTypography.secondary,
                    )
                    Text(leak.shortDescription, style = ViewerTypography.secondary, color = colors.secondaryText)
                    leak.retainedHeapByteSize?.let {
                        Text(
                            localizedStringResource(Res.string.retained_bytes, language, formatBytes(it.toLong())),
                            style = ViewerTypography.secondary,
                        )
                    }
                    leak.retainedObjectCount?.let {
                        Text(
                            localizedStringResource(Res.string.retained_objects, language, integer(it)),
                            style = ViewerTypography.secondary,
                        )
                    }
                }
                HorizontalDivider(color = colors.border)
                Text(
                    localizedStringResource(Res.string.leak_trace, language),
                    Modifier.padding(8.dp),
                    style = ViewerTypography.subsectionTitle,
                    fontWeight = FontWeight.SemiBold,
                )
                leak.gcRootType?.let {
                    Text(
                        localizedStringResource(Res.string.gc_root, language, it),
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag("memory-leak-gc-root"),
                        style = ViewerTypography.bodyCompact,
                        color = colors.secondaryText,
                    )
                }
                if (leak.trace.isEmpty()) {
                    Text(
                        localizedStringResource(Res.string.leak_no_path, language),
                        Modifier.padding(8.dp),
                        style = ViewerTypography.secondary,
                    )
                }
            }
            itemsIndexed(leak.trace) { index, element ->
                LeakPathNode(element, index, state.selectedInstanceDetail?.objectId, actions, language)
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun LeakPathNode(
    element: LeakCanaryTraceElement,
    index: Int,
    selectedObjectId: Long?,
    actions: MemoryProfilerActions,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    val selected = element.objectId != null && element.objectId == selectedObjectId
    val leaking = element.leakingStatus.equals("LEAKING", ignoreCase = true)
    Column(Modifier.fillMaxWidth().padding(start = 8.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(if (selected) colors.selectedRow else colors.panel)
                .clickable(enabled = element.objectId != null) { element.objectId?.let(actions.onSelectInstance) }
                .testTag("memory-leak-path-object-$index")
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leaking) MemoryLeakWarningIcon(language)
                Text(
                    element.className,
                    style = ViewerTypography.bodyCompact,
                    color = if (leaking) colors.error else colors.primaryText,
                )
            }
            Text(
                element.objectId?.let(::leakObjectId) ?: localizedStringResource(
                    Res.string.leak_object_unavailable,
                    language,
                ),
                style = ViewerTypography.secondary,
                color = colors.secondaryText,
            )
            LeakPathNodeStatus(element, language)
            element.labels.forEach { Text(it, style = ViewerTypography.secondary, color = colors.secondaryText) }
        }
        if (element.referenceName != null || element.isSuspect) {
            LeakPathReference(element, index, language)
        }
        HorizontalDivider(color = colors.border)
    }
}

@Composable
private fun LeakPathNodeStatus(
    element: LeakCanaryTraceElement,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    val leaking = element.leakingStatus.equals("LEAKING", ignoreCase = true)
    val notLeaking = element.leakingStatus.equals("NOT_LEAKING", ignoreCase = true)
    val statusColor =
        when {
            leaking -> colors.error
            notLeaking -> colors.success
            else -> colors.secondaryText
        }
    val status =
        localizedStringResource(
            when {
                leaking -> Res.string.leaking
                notLeaking -> Res.string.not_leaking
                else -> Res.string.unknown_status
            },
            language,
        )
    Text(
        listOfNotNull(status, element.leakingStatusReason?.takeIf { it.isNotBlank() }).joinToString(": "),
        style = ViewerTypography.secondary,
        color = statusColor,
    )
}

@Composable
private fun LeakPathReference(
    element: LeakCanaryTraceElement,
    index: Int,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 4.dp, bottom = 6.dp)
            .testTag("memory-leak-path-edge-$index"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (element.isSuspect) {
            Icon(
                painterResource(UiRes.drawable.ic_stat_attention),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = colors.warning,
            )
        }
        Column {
            Text(
                listOfNotNull(element.referenceName, element.referenceType?.let { "[$it]" }).joinToString(" "),
                style = ViewerTypography.bodyCompact,
                color = if (element.isSuspect) colors.warning else colors.secondaryText,
            )
            if (element.isSuspect) {
                Text(
                    localizedStringResource(Res.string.leak_suspect_reference, language),
                    Modifier.testTag("memory-leak-suspect-edge-$index"),
                    style = ViewerTypography.label,
                    color = colors.warning,
                )
            }
        }
    }
}
