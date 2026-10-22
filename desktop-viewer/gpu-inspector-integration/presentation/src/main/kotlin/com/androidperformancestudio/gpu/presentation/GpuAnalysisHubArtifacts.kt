@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod", "TooManyFunctions")

package com.androidperformancestudio.gpu.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.gpu.model.AgiCapability
import com.androidperformancestudio.gpu.model.ArtifactLocationStatus
import com.androidperformancestudio.gpu.model.GpuArtifact
import com.androidperformancestudio.gpu.model.GpuArtifactKind
import com.androidperformancestudio.gpu.presentation.generated.resources.*
import com.androidperformancestudio.gpu.presentation.generated.resources.Res
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.search.CompactSearchField
import com.androidperformancestudio.ui.studio.StudioCard
import com.androidperformancestudio.ui.studio.StudioDataTable
import com.androidperformancestudio.ui.studio.StudioPanel
import com.androidperformancestudio.ui.studio.StudioPanelHeader
import com.androidperformancestudio.ui.studio.StudioStatusChip
import com.androidperformancestudio.ui.studio.StudioStatusTone
import com.androidperformancestudio.ui.studio.StudioTableColumn
import com.androidperformancestudio.ui.studio.StudioTokens
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_gpu_inspector
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_trace_analyzer
import com.androidperformancestudio.ui_components.generated.resources.ic_stat_artifact
import com.androidperformancestudio.ui_components.generated.resources.ic_stat_history
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import com.androidperformancestudio.ui_components.generated.resources.Res as UiComponentsRes

/**
 * Left rail: the indexed GPU artifacts as selectable capture cards with a local search filter.
 *
 * The reference dashboard lists capture sessions with a screenshot thumbnail; APS has no decoded
 * frame image for opaque AGI artifacts, so the card shows only recorded identity, timing, size and
 * the verification status that the index actually proved.
 */
@Composable
internal fun GpuArtifactRail(
    state: GpuIntegrationState,
    actions: GpuIntegrationActions,
    selectedArtifact: GpuArtifact?,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    val visible = filterArtifacts(state.artifacts, state.artifactFilter)
    // This inset surrounds child artifact cards; their own content padding remains unchanged.
    StudioPanel(modifier.testTag("gpu-artifact-rail"), contentPadding = StudioTokens.workspacePadding) {
        StudioPanelHeader(
            title = localizedStringResource(Res.string.gpu_artifacts, language),
            subtitle = localizedStringResource(Res.string.artifact_count, language, state.artifacts.size),
        )
        CompactSearchField(
            value = state.artifactFilter,
            placeholder = localizedStringResource(Res.string.artifact_search_placeholder, language),
            onValueChange = actions.onFilterArtifacts,
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            state.artifacts.isEmpty() ->
                Text(
                    localizedStringResource(Res.string.no_gpu_artifacts, language),
                    color = colors.secondaryText,
                    style = ViewerTypography.secondary,
                )
            visible.isEmpty() ->
                Text(
                    localizedStringResource(Res.string.no_matching_artifacts, language),
                    color = colors.secondaryText,
                    style = ViewerTypography.secondary,
                )
            else ->
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
                ) {
                    items(visible, key = GpuArtifact::id) { artifact ->
                        GpuArtifactCard(
                            artifact = artifact,
                            status = state.artifactStatus(artifact),
                            selected = artifact.id == selectedArtifact?.id,
                            language = language,
                            onSelect = { actions.onSelectArtifact(artifact.id) },
                        )
                    }
                }
        }
    }
}

@Composable
private fun GpuArtifactCard(
    artifact: GpuArtifact,
    status: ArtifactLocationStatus?,
    selected: Boolean,
    language: UiLanguage,
    onSelect: () -> Unit,
) {
    val colors = LocalViewerColors.current
    val statusColor = status.studioColor(colors)
    StudioCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onSelect)
                .testTag("gpu-artifact-card-${artifact.id}"),
        containerColor = if (selected) colors.selectedRow else null,
        borderColor = if (selected) colors.accent else null,
        contentPadding = 6.dp,
        contentSpacing = 2.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                painter = painterResource(artifact.kind.resourceIcon()),
                contentDescription = null,
                modifier = Modifier.size(StudioTokens.navigationIconSize),
                tint = statusColor,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    artifact.displayTitle(),
                    color = colors.primaryText,
                    style = ViewerTypography.bodyCompact,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${artifact.capturedAt.displayTimestamp()} · ${artifact.kind.displayName(language)}",
                    color = colors.secondaryText,
                    style = ViewerTypography.dense,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${status?.displayName(language) ?: localizedStringResource(Res.string.unavailable, language)} · ${formatArtifactSize(artifact.sizeBytes)}",
                    color = statusColor,
                    style = ViewerTypography.dense,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Panel body that scrolls only when its panel owns the remaining workspace height. Stacked layouts
 * leave scrolling to the page, because a scroll container may not be measured with infinite height.
 */
@Composable
private fun PanelBody(
    scrollBody: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val modifier =
        Modifier
            .fillMaxWidth()
            .then(if (scrollBody) Modifier.verticalScroll(rememberScrollState()) else Modifier)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap), content = content)
}

/** Page 09 §14.2 artifact table: Kind | File | Device | Package | API | Captured | Status | Action. */
@Composable
internal fun GpuArtifactTable(
    state: GpuIntegrationState,
    actions: GpuIntegrationActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
    scrollBody: Boolean = false,
) {
    val colors = LocalViewerColors.current
    StudioDataTable(
        columns = artifactTableColumns(language),
        title = localizedStringResource(Res.string.gpu_artifacts, language),
        modifier = modifier.testTag("gpu-artifact-table"),
    ) {
        // The rows scroll inside the bounded table so the shared header stays visible.
        PanelBody(scrollBody) {
            if (state.artifacts.isEmpty()) {
                Text(
                    localizedStringResource(Res.string.no_gpu_artifacts, language),
                    color = colors.secondaryText,
                    style = ViewerTypography.secondary,
                )
            } else {
                state.artifacts.forEach { artifact ->
                    GpuArtifactTableRow(
                        artifact = artifact,
                        availability = state.availabilityOf(artifact),
                        selected = artifact.id == state.selectedArtifactId,
                        isBusy = state.isBusy,
                        language = language,
                        onSelect = { actions.onSelectArtifact(artifact.id) },
                        onOpen = { actions.onOpenArtifact(artifact) },
                    )
                }
            }
        }
    }
}

private fun artifactTableColumns(language: UiLanguage): List<StudioTableColumn> =
    listOf(
        StudioTableColumn(localizedStringResource(Res.string.artifact_column_kind, language), 1.1f),
        StudioTableColumn(localizedStringResource(Res.string.artifact_column_file, language), 1.8f),
        StudioTableColumn(localizedStringResource(Res.string.artifact_column_device, language), 1.2f),
        StudioTableColumn(localizedStringResource(Res.string.artifact_column_package, language), 1.4f),
        StudioTableColumn(localizedStringResource(Res.string.artifact_column_api, language), 0.9f),
        StudioTableColumn(localizedStringResource(Res.string.artifact_column_captured, language), 1.2f),
        StudioTableColumn(localizedStringResource(Res.string.artifact_column_status, language), 1.1f),
        StudioTableColumn(localizedStringResource(Res.string.artifact_column_action, language), 1.0f),
    )

@Composable
private fun GpuArtifactTableRow(
    artifact: GpuArtifact,
    availability: GpuArtifactAvailability?,
    selected: Boolean,
    isBusy: Boolean,
    language: UiLanguage,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = LocalViewerColors.current
    val status = availability?.locationStatus
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .testTag("gpu-artifact-row-${artifact.id}")
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtifactCell(artifact.kind.displayName(language), 1.1f)
        ArtifactCell(artifact.displayTitle(), 1.8f)
        ArtifactCell(artifact.displayDevice(), 1.2f)
        ArtifactCell(artifact.packageName.orEmpty(), 1.4f)
        ArtifactCell(artifact.graphicsApi?.displayName(language) ?: localizedStringResource(Res.string.graphics_api_unknown, language), 0.9f)
        ArtifactCell(artifact.capturedAt.displayTimestamp(), 1.2f)
        ArtifactCell(
            text = status?.displayName(language) ?: localizedStringResource(Res.string.unavailable, language),
            weight = 1.1f,
            color = status.studioColor(colors),
        )
        Box(Modifier.weight(1.0f), contentAlignment = Alignment.CenterStart) {
            ProfilerCompactButton(
                text = availability?.primaryAction?.displayName(language) ?: localizedStringResource(Res.string.unavailable, language),
                modifier = Modifier.fillMaxWidth().testTag("gpu-artifact-row-action-${artifact.id}"),
                enabled = !isBusy && availability?.canOpen == true,
                onClick = onOpen,
            )
        }
    }
}

@Composable
private fun RowScope.ArtifactCell(
    text: String,
    weight: Float,
    color: Color? = null,
) {
    Text(
        text,
        modifier = Modifier.weight(weight).padding(horizontal = 3.dp),
        color = color ?: LocalViewerColors.current.primaryText,
        style = ViewerTypography.bodyCompact,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Page 09 §14.4 replacement for the reference frame-profile panels: artifact preview and metadata.
 * It renders only fields the index recorded; nothing is decoded from the artifact payload.
 */
@Composable
internal fun GpuArtifactPreview(
    state: GpuIntegrationState,
    actions: GpuIntegrationActions,
    artifact: GpuArtifact?,
    language: UiLanguage,
    modifier: Modifier = Modifier,
    scrollBody: Boolean = false,
) {
    val colors = LocalViewerColors.current
    val availability = state.availabilityOf(artifact)
    val related = state.relatedArtifacts(artifact)
    StudioPanel(modifier.testTag("gpu-artifact-preview")) {
        StudioPanelHeader(
            title = localizedStringResource(Res.string.artifact_preview, language),
            subtitle = artifact?.displayTitle() ?: localizedStringResource(Res.string.artifact_metadata, language),
            actions = {
                StudioStatusChip(
                    label = availability?.locationStatus?.displayName(language) ?: localizedStringResource(Res.string.unavailable, language),
                    tone = availability?.locationStatus?.studioTone() ?: StudioStatusTone.NEUTRAL,
                )
            },
        )
        PanelBody(scrollBody) {
            if (artifact == null) {
                Text(
                    localizedStringResource(Res.string.artifact_preview_empty, language),
                    color = colors.secondaryText,
                    style = ViewerTypography.secondary,
                )
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(StudioTokens.sectionGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(artifact.kind.displayName(language), color = colors.accent, style = ViewerTypography.subsectionTitle, fontWeight = FontWeight.SemiBold)
                    Text(
                        localizedStringResource(Res.string.artifact_related_count, language, related.size),
                        color = colors.secondaryText,
                        style = ViewerTypography.secondary,
                    )
                    Spacer(Modifier.weight(1f))
                    ProfilerCompactButton(
                        text = availability?.primaryAction?.displayName(language) ?: localizedStringResource(Res.string.unavailable, language),
                        modifier = Modifier.testTag("gpu-preview-open"),
                        enabled = !state.isBusy && availability?.canOpen == true,
                        onClick = { actions.onOpenArtifact(artifact) },
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.sectionGap)) {
                    ContextValue(localizedStringResource(Res.string.artifact_column_package, language), artifact.packageName, Modifier.weight(1.4f))
                    ContextValue(localizedStringResource(Res.string.artifact_column_device, language), artifact.displayDevice(), Modifier.weight(1.2f))
                    ContextValue(
                        localizedStringResource(Res.string.artifact_column_api, language),
                        artifact.graphicsApi?.displayName(language),
                        Modifier.weight(1f),
                    )
                    ContextValue(localizedStringResource(Res.string.artifact_size, language), formatArtifactSize(artifact.sizeBytes), Modifier.weight(0.9f))
                    ContextValue(localizedStringResource(Res.string.artifact_column_captured, language), artifact.capturedAt.displayTimestamp(), Modifier.weight(1.2f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.sectionGap)) {
                    ContextValue(localizedStringResource(Res.string.artifact_agi_version, language), artifact.agiVersion, Modifier.weight(1f))
                    ContextValue(localizedStringResource(Res.string.artifact_open_route, language), artifact.openRoute.displayName(language), Modifier.weight(1f))
                    ContextValue(
                        localizedStringResource(Res.string.graphics_implementation, language),
                        artifact.graphicsImplementation?.name,
                        Modifier.weight(1.4f),
                    )
                    ContextValue(localizedStringResource(Res.string.artifact_notes_label, language), artifact.notes ?: localizedStringResource(Res.string.no_artifact_notes, language), Modifier.weight(1.6f))
                }
                HorizontalDivider(color = colors.border)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(localizedStringResource(Res.string.artifact_hash, language), color = colors.secondaryText, style = ViewerTypography.dense)
                        Text(artifact.sha256, color = colors.primaryText, style = ViewerTypography.dense)
                        Text(localizedStringResource(Res.string.artifact_location, language), color = colors.secondaryText, style = ViewerTypography.dense)
                        Text(artifact.path.toString(), color = colors.primaryText, style = ViewerTypography.dense)
                    }
                }
            }
        }
    }
}

/** Page 09 §14.4 replacement for the reference shader/draw-call lists: related analysis by identity. */
@Composable
internal fun GpuRelatedEvidence(
    state: GpuIntegrationState,
    actions: GpuIntegrationActions,
    selectedArtifact: GpuArtifact?,
    language: UiLanguage,
    modifier: Modifier = Modifier,
    scrollBody: Boolean = false,
) {
    val colors = LocalViewerColors.current
    val related = state.relatedArtifacts(selectedArtifact)
    StudioPanel(modifier.testTag("gpu-related-evidence")) {
        StudioPanelHeader(
            title = localizedStringResource(Res.string.related_evidence, language),
            subtitle = localizedStringResource(Res.string.related_evidence_hint, language),
        )
        PanelBody(scrollBody) {
            if (related.isEmpty()) {
                Text(
                    localizedStringResource(Res.string.no_related_evidence, language),
                    color = colors.secondaryText,
                    style = ViewerTypography.secondary,
                )
            } else {
                relatedEvidenceGroups(related).forEach { (kind, group) ->
                    SectionTitle("${kind.displayName(language)} · ${group.size}")
                    group.forEach { candidate ->
                        RelatedEvidenceRow(
                            artifact = candidate,
                            status = state.artifactStatus(candidate),
                            language = language,
                            onSelect = { actions.onSelectArtifact(candidate.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RelatedEvidenceRow(
    artifact: GpuArtifact,
    status: ArtifactLocationStatus?,
    language: UiLanguage,
    onSelect: () -> Unit,
) {
    val colors = LocalViewerColors.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
    ) {
        Icon(
            painter = painterResource(artifact.kind.resourceIcon()),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = status.studioColor(colors),
        )
        Text(
            artifact.displayTitle(),
            modifier = Modifier.weight(1.6f),
            color = colors.primaryText,
            style = ViewerTypography.bodyCompact,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            artifact.displayDevice(),
            modifier = Modifier.weight(1.2f),
            color = colors.secondaryText,
            style = ViewerTypography.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            artifact.packageName.orEmpty(),
            modifier = Modifier.weight(1.2f),
            color = colors.secondaryText,
            style = ViewerTypography.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(artifact.capturedAt.displayTimestamp(), color = colors.secondaryText, style = ViewerTypography.secondary, maxLines = 1)
        Text(
            status?.displayName(language) ?: localizedStringResource(Res.string.unavailable, language),
            color = status.studioColor(colors),
            style = ViewerTypography.secondary,
            maxLines = 1,
        )
    }
}

/**
 * Page 09 §14.4 replacement for the reference texture/pipeline tabs: what the local AGI integration
 * can and cannot do today, including the panels that stay closed until a stable parser exists.
 */
@Composable
internal fun GpuToolCapability(
    capability: AgiCapability?,
    language: UiLanguage,
    modifier: Modifier = Modifier,
    scrollBody: Boolean = false,
) {
    val colors = LocalViewerColors.current
    StudioPanel(modifier.testTag("gpu-tool-capability")) {
        StudioPanelHeader(
            title = localizedStringResource(Res.string.tool_capability, language),
            subtitle = localizedStringResource(Res.string.hub_positioning, language),
        )
        PanelBody(scrollBody) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.sectionGap)) {
                ContextValue(localizedStringResource(Res.string.agi_version, language), capability?.version, Modifier.weight(1f))
                ContextValue(localizedStringResource(Res.string.launch_mode, language), capability?.launchMode?.displayName(language), Modifier.weight(1f))
                ContextValue(localizedStringResource(Res.string.agi_executable, language), capability?.executable?.toString(), Modifier.weight(2.4f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.sectionGap)) {
                Box(Modifier.weight(1f)) { CapabilityFlag(localizedStringResource(Res.string.capability_launch_supported, language), capability?.launchSupported == true, language) }
                Box(Modifier.weight(1f)) { CapabilityFlag(localizedStringResource(Res.string.capability_artifact_open_supported, language), capability?.artifactOpenSupported == true, language) }
                Box(Modifier.weight(2.4f)) {
                    ContextValue(
                        localizedStringResource(Res.string.capability_supported_arguments, language),
                        capability?.supportedArguments?.sorted()?.joinToString(" ")?.takeIf { it.isNotBlank() }
                            ?: localizedStringResource(Res.string.capability_no_arguments, language),
                        Modifier.fillMaxWidth(),
                    )
                }
            }
            HorizontalDivider(color = colors.border)
            SectionTitle(localizedStringResource(Res.string.closed_panels_title, language))
            closedPanels(language).forEach { panel ->
                Text("• $panel", color = colors.warning, style = ViewerTypography.secondary)
            }
            Text(
                localizedStringResource(Res.string.closed_panels_hint, language),
                color = colors.secondaryText,
                style = ViewerTypography.secondary,
            )
        }
    }
}

private fun closedPanels(language: UiLanguage): List<String> =
    listOf(
        Res.string.closed_panel_gpu_counters,
        Res.string.closed_panel_shader_hotspots,
        Res.string.closed_panel_render_passes,
        Res.string.closed_panel_draw_calls,
        Res.string.closed_panel_overdraw,
    ).map { localizedStringResource(it, language) }

internal fun ArtifactLocationStatus.studioTone(): StudioStatusTone =
    when (this) {
        ArtifactLocationStatus.AVAILABLE -> StudioStatusTone.SUCCESS
        ArtifactLocationStatus.MISSING,
        ArtifactLocationStatus.SIZE_CHANGED,
        ArtifactLocationStatus.CONTENT_CHANGED,
        ArtifactLocationStatus.UNREADABLE,
        -> StudioStatusTone.ERROR
    }

internal fun ArtifactLocationStatus?.studioColor(colors: ViewerColors): Color =
    when (this) {
        ArtifactLocationStatus.AVAILABLE -> colors.success
        null -> colors.secondaryText
        else -> colors.error
    }

internal fun GpuArtifactKind.resourceIcon(): DrawableResource =
    when (this) {
        GpuArtifactKind.AGI_SYSTEM_PROFILE,
        GpuArtifactKind.AGI_FRAME_PROFILE,
        -> UiComponentsRes.drawable.ic_nav_gpu_inspector
        GpuArtifactKind.PERFETTO_TRACE -> UiComponentsRes.drawable.ic_nav_trace_analyzer
        GpuArtifactKind.SCREENSHOT,
        GpuArtifactKind.EXTERNAL_REPORT,
        -> UiComponentsRes.drawable.ic_stat_artifact
        GpuArtifactKind.UNKNOWN -> UiComponentsRes.drawable.ic_stat_history
    }
