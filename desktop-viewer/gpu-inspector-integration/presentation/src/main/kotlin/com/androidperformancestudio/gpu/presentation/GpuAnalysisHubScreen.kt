@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod", "TooManyFunctions")

package com.androidperformancestudio.gpu.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.gpu.model.AgiCapability
import com.androidperformancestudio.gpu.model.GpuArtifact
import com.androidperformancestudio.gpu.model.GpuDeviceContext
import com.androidperformancestudio.gpu.model.GraphicsApi
import com.androidperformancestudio.gpu.presentation.generated.resources.*
import com.androidperformancestudio.gpu.presentation.generated.resources.Res
import com.androidperformancestudio.ui.DeviceDropdownSelector
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.LocalTitleBarTargetHost
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.SegmentedSelector
import com.androidperformancestudio.ui.SegmentedSelectorStyle
import com.androidperformancestudio.ui.TitleBarTargetOrInline
import com.androidperformancestudio.ui.TitleBarRefreshOrInline
import com.androidperformancestudio.ui.TitleBarTargetSlot
import com.androidperformancestudio.ui.TitleBarTargetWidth
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioMetricCard
import com.androidperformancestudio.ui.studio.StudioPanel
import com.androidperformancestudio.ui.studio.StudioPanelHeader
import com.androidperformancestudio.ui.studio.StudioStatusChip
import com.androidperformancestudio.ui.studio.StudioStatusTone
import com.androidperformancestudio.ui.studio.StudioTokens
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_gpu_inspector
import com.androidperformancestudio.ui_components.generated.resources.ic_stat_attention
import com.androidperformancestudio.ui_components.generated.resources.ic_stat_capability
import com.androidperformancestudio.ui_components.generated.resources.ic_stat_verified
import org.jetbrains.compose.resources.DrawableResource
import com.androidperformancestudio.ui_components.generated.resources.Res as UiComponentsRes

/** Below this workspace width the artifact rail, analysis center and insight rail stack vertically. */
private val GPU_WIDE_WORKSPACE_MIN_WIDTH = 1100.dp
private val GPU_TWO_ROW_SUMMARY_MAX_WIDTH = 900.dp
private val GPU_ARTIFACT_RAIL_WIDTH = 264.dp
private val GPU_INSIGHT_RAIL_WIDTH = 300.dp
private val GPU_ARTIFACT_RAIL_COMPACT_HEIGHT = 236.dp
private val GPU_SUMMARY_CARD_HEIGHT = 104.dp
private val GPU_TAB_STRIP_MAX_WIDTH = 560.dp

/**
 * GPU Analysis Hub — page 09.
 *
 * The workspace keeps the reference dashboard structure (analysis toolbar, summary row, artifact
 * rail, tabbed analysis center, insight rail) while rendering only sources that exist today: the
 * local artifact index, runtime verification results, the AGI executable capability probe and the
 * connected device graphics context. No AGI frame-profile metric is inferred or fabricated.
 */
@Composable
public fun GpuAnalysisHubScreen(
    state: GpuIntegrationState,
    actions: GpuIntegrationActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    val titleBarTargets = LocalTitleBarTargetHost.current
    LaunchedEffect(titleBarTargets, titleBarTargets?.selectedDeviceSerial, state.devices, state.selectedDeviceSerial) {
        val host = titleBarTargets ?: return@LaunchedEffect
        val sharedSerial = host.selectedDeviceSerial
        if (sharedSerial == null) {
            state.devices.firstOrNull { it.serial == state.selectedDeviceSerial }?.let { device ->
                device.serial?.let { host.chooseDevice(it, device.selectorLabel()) }
            }
        } else if (sharedSerial != state.selectedDeviceSerial && state.devices.any { it.serial == sharedSerial }) {
            actions.onSelectDevice(sharedSerial)
        }
    }
    val selectedArtifact = state.artifacts.firstOrNull { it.id == state.selectedArtifactId }
    val summary = state.hubSummary()
    BoxWithConstraints(modifier.fillMaxSize().background(colors.workspace).padding(StudioTokens.workspacePadding)) {
        val wide = maxWidth >= GPU_WIDE_WORKSPACE_MIN_WIDTH
        val stackedSummary = maxWidth < GPU_TWO_ROW_SUMMARY_MAX_WIDTH
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
            GpuSummaryRow(summary, state.capability, language, stackedSummary)
            if (wide) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
                    GpuArtifactRail(state, actions, selectedArtifact, language, Modifier.width(GPU_ARTIFACT_RAIL_WIDTH).fillMaxHeight())
                    GpuHubWorkspace(
                        state,
                        actions,
                        selectedArtifact,
                        language,
                        Modifier.weight(1f).fillMaxHeight(),
                        expandContent = true,
                    )
                    Column(
                        Modifier.width(GPU_INSIGHT_RAIL_WIDTH).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
                    ) {
                        GpuCapabilityRail(state.capability, language)
                        GpuOpenWithRail(state, actions, selectedArtifact, language)
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
                ) {
                    GpuArtifactRail(
                        state,
                        actions,
                        selectedArtifact,
                        language,
                        Modifier.fillMaxWidth().height(GPU_ARTIFACT_RAIL_COMPACT_HEIGHT),
                    )
                    GpuHubWorkspace(state, actions, selectedArtifact, language, Modifier.fillMaxWidth(), expandContent = false)
                    GpuCapabilityRail(state.capability, language)
                    GpuOpenWithRail(state, actions, selectedArtifact, language)
                }
            }
        }
    }
}

/**
 * Reference dashboard "analysis tools" strip: the three integration actions plus artifact choice.
 *
 * The desktop shell hosts this row in its top header, ahead of the Refresh AGI / Configure AGI
 * actions, so the GPU workspace starts directly with its summary evidence.
 */
@Composable
public fun GpuAnalysisToolbar(
    state: GpuIntegrationState,
    actions: GpuIntegrationActions,
    selectedArtifact: GpuArtifact?,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("gpu-analysis-toolbar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
    ) {
        Text(
            localizedStringResource(Res.string.analysis_tools, language),
            color = colors.secondaryText,
            style = ViewerTypography.subsectionTitle,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        ProfilerCompactButton(
            text = localizedStringResource(Res.string.launch_agi, language),
            modifier = Modifier.testTag("gpu-action-launch-agi"),
            enabled = !state.isBusy && state.capability?.launchSupported == true,
            onClick = actions.onLaunchAgi,
        )
        ProfilerCompactButton(
            text = localizedStringResource(Res.string.import_gpu_trace, language),
            modifier = Modifier.testTag("gpu-action-import-artifact"),
            enabled = !state.isBusy,
            onClick = actions.onImportArtifact,
        )
        ProfilerCompactButton(
            text = localizedStringResource(Res.string.open_trace_analyzer, language),
            modifier = Modifier.testTag("gpu-action-open-trace-analyzer"),
            enabled = !state.isBusy,
            onClick = actions.onOpenTraceAnalyzer,
        )
        DropdownSelector(
            items = state.artifacts,
            selectedItem = selectedArtifact,
            onItemSelected = { actions.onSelectArtifact(it.id) },
            itemLabel = GpuArtifact::displayTitle,
            selectedItemLabel = GpuArtifact::displayTitle,
            placeholder = localizedStringResource(Res.string.select_analysis_artifact, language),
            selectorDescription = localizedStringResource(Res.string.select_analysis_artifact, language),
            modifier = Modifier.widthIn(min = 220.dp, max = 380.dp).testTag("gpu-artifact-selector"),
            enabled = !state.isBusy && state.artifacts.isNotEmpty(),
        )
        ProfilerCompactButton(
            text = localizedStringResource(Res.string.show_in_files, language),
            modifier = Modifier.testTag("gpu-action-reveal"),
            enabled = !state.isBusy && selectedArtifact != null,
            onClick = { selectedArtifact?.let(actions.onRevealArtifact) },
        )
    }
}

/**
 * Summary cards count the local artifact index; the reference GPU counters, frame time and overdraw
 * cards stay closed because no stable parser can prove those values.
 */
@Composable
private fun GpuSummaryRow(
    summary: GpuHubSummary,
    capability: AgiCapability?,
    language: UiLanguage,
    stacked: Boolean,
) {
    val colors = LocalViewerColors.current
    val kindBreakdown =
        summary.kindCounts
            .joinToString(" · ") { (kind, count) -> "${kind.displayName(language)} $count" }
            .ifEmpty { localizedStringResource(Res.string.no_gpu_artifacts, language) }
    val attentionSupport =
        when {
            summary.attentionCount > 0 -> localizedStringResource(Res.string.summary_attention_support, language, summary.attentionCount, summary.indexedCount)
            summary.unverifiedCount > 0 -> localizedStringResource(Res.string.summary_attention_unverified, language, summary.unverifiedCount)
            summary.indexedCount > 0 -> localizedStringResource(Res.string.summary_attention_none, language)
            else -> localizedStringResource(Res.string.no_gpu_artifacts, language)
        }
    val availableSupport = localizedStringResource(Res.string.summary_available_support, language, summary.availableCount, summary.indexedCount)
    val capabilityValue =
        capability?.version
            ?: if (capability == null) localizedStringResource(Res.string.agi_is_not_configured, language) else localizedStringResource(Res.string.unknown_version, language)
    val capabilitySupport =
        localizedStringResource(
            Res.string.capability_summary,
            language,
            capability?.launchMode?.displayName(language) ?: localizedStringResource(Res.string.unavailable, language),
            capability?.executable?.fileName?.toString() ?: "—",
        )

    val firstRow = @Composable { cardModifier: Modifier ->
        GpuSummaryCard(
            id = "indexed",
            label = localizedStringResource(Res.string.summary_indexed_artifacts, language),
            value = summary.indexedCount.toString(),
            supportingText = kindBreakdown,
            icon = UiComponentsRes.drawable.ic_nav_gpu_inspector,
            tone = colors.accent,
            modifier = cardModifier,
        )
        GpuSummaryCard(
            id = "available",
            label = localizedStringResource(Res.string.summary_available_artifacts, language),
            value = summary.availableCount.toString(),
            supportingText = availableSupport,
            icon = UiComponentsRes.drawable.ic_stat_verified,
            tone = colors.success,
            modifier = cardModifier,
        )
    }
    val secondRow = @Composable { cardModifier: Modifier ->
        GpuSummaryCard(
            id = "attention",
            label = localizedStringResource(Res.string.summary_attention_artifacts, language),
            value = summary.attentionCount.toString(),
            supportingText = attentionSupport,
            icon = UiComponentsRes.drawable.ic_stat_attention,
            tone = if (summary.attentionCount > 0) colors.warning else colors.secondaryText,
            modifier = cardModifier,
        )
        GpuSummaryCard(
            id = "capability",
            label = localizedStringResource(Res.string.summary_capability, language),
            value = capabilityValue,
            supportingText = capabilitySupport,
            icon = UiComponentsRes.drawable.ic_stat_capability,
            tone = if (capability?.launchSupported == true) colors.info else colors.warning,
            modifier = cardModifier,
        )
    }

    if (!stacked) {
        Row(Modifier.fillMaxWidth().height(GPU_SUMMARY_CARD_HEIGHT), horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
            // Compact cards wrap their supporting line instead of dropping evidence at narrow widths.
            firstRow(Modifier.weight(1f).fillMaxHeight())
            secondRow(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
            Row(Modifier.fillMaxWidth().height(GPU_SUMMARY_CARD_HEIGHT), horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
                firstRow(Modifier.weight(1f).fillMaxHeight())
            }
            Row(Modifier.fillMaxWidth().height(GPU_SUMMARY_CARD_HEIGHT), horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
                secondRow(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun GpuSummaryCard(
    id: String,
    label: String,
    value: String,
    supportingText: String,
    icon: DrawableResource,
    tone: Color,
    modifier: Modifier,
) {
    StudioMetricCard(
        label = label,
        value = value,
        modifier = modifier.testTag("gpu-summary-$id"),
        supportingText = supportingText,
        icon = icon,
        tone = tone,
    )
}

/**
 * Device / Graphics Context (page 09 §14.2) plus the workspace tab strip and the selected view.
 *
 * [expandContent] hands the remaining workspace height to the selected panel, which scrolls its own
 * body, so the analysis center fills the window like the reference dashboard. Stacked layouts keep
 * content-sized panels inside the page scroll instead.
 */
@Composable
private fun GpuHubWorkspace(
    state: GpuIntegrationState,
    actions: GpuIntegrationActions,
    selectedArtifact: GpuArtifact?,
    language: UiLanguage,
    modifier: Modifier,
    expandContent: Boolean,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
        GpuDeviceContextPanel(state, actions, language)
        GpuHubTabStrip(state, actions, selectedArtifact, language)
        val contentModifier =
            if (expandContent) {
                Modifier.fillMaxWidth().weight(1f)
            } else {
                Modifier.fillMaxWidth()
            }
        when (state.hubTab) {
            GpuHubTab.OVERVIEW -> GpuArtifactPreview(state, actions, selectedArtifact, language, contentModifier, expandContent)
            GpuHubTab.ARTIFACTS -> GpuArtifactTable(state, actions, language, contentModifier, expandContent)
            GpuHubTab.RELATED_EVIDENCE -> GpuRelatedEvidence(state, actions, selectedArtifact, language, contentModifier, expandContent)
            GpuHubTab.TOOL_CAPABILITY -> GpuToolCapability(state.capability, language, contentModifier, expandContent)
        }
    }
}

@Composable
private fun GpuHubTabStrip(
    state: GpuIntegrationState,
    actions: GpuIntegrationActions,
    selectedArtifact: GpuArtifact?,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    Row(
        Modifier.fillMaxWidth().testTag("gpu-hub-tabs"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
    ) {
        SegmentedSelector(
            items = GpuHubTab.entries,
            selectedItem = state.hubTab,
            onItemSelected = actions.onSelectHubTab,
            itemLabel = { it.displayName(language) },
            style = SegmentedSelectorStyle.COMPACT_OUTLINED,
            selectorDescription = localizedStringResource(Res.string.gpu_hub_tabs, language),
            maxWidth = GPU_TAB_STRIP_MAX_WIDTH,
        )
        Spacer(Modifier.weight(1f))
        Text(
            selectedArtifact?.displayTitle() ?: localizedStringResource(Res.string.no_gpu_artifacts, language),
            modifier = Modifier.widthIn(max = 280.dp),
            color = colors.secondaryText,
            style = ViewerTypography.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GpuDeviceContextPanel(
    state: GpuIntegrationState,
    actions: GpuIntegrationActions,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    val titleBarTargets = LocalTitleBarTargetHost.current
    val selectedDevice = state.devices.firstOrNull { it.serial == state.selectedDeviceSerial }
        ?.takeIf { titleBarTargets?.deviceReady(it.serial) != false }
    StudioPanel(Modifier.fillMaxWidth()) {
        StudioPanelHeader(
            title = localizedStringResource(Res.string.device_graphics_context, language),
            actions = {
                val inTitleBar = titleBarTargets != null
                TitleBarTargetOrInline(TitleBarTargetSlot.DEVICE) {
                    DeviceDropdownSelector(
                        language = language,
                        items = state.devices,
                        selectedItem = selectedDevice,
                        onItemSelected = { device ->
                            device.serial?.let { serial ->
                                titleBarTargets?.chooseDevice(serial, device.selectorLabel())
                                actions.onSelectDevice(serial)
                            }
                        },
                        itemLabel = GpuDeviceContext::selectorLabel,
                        selectedItemLabel = GpuDeviceContext::selectorLabel,
                        modifier = if (inTitleBar) Modifier.width(TitleBarTargetWidth) else Modifier.widthIn(min = 200.dp, max = 360.dp),
                        fillWidth = inTitleBar,
                        displayTextOverride = titleBarTargets?.selectedDeviceLabel,
                        enabled = !state.isRefreshingDevices && state.devices.isNotEmpty(),
                    )
                }
                TitleBarRefreshOrInline(
                    enabled = !state.isRefreshingDevices,
                    onRefresh = actions.onRefreshDevices,
                ) {
                    ProfilerCompactButton(
                        text = localizedStringResource(Res.string.refresh_devices, language),
                        enabled = !state.isRefreshingDevices,
                        onClick = actions.onRefreshDevices,
                    )
                }
            },
        )
        if (state.isInspectingDevice || state.isRefreshingDevices) {
            Text(localizedStringResource(Res.string.reading_device_context, language), color = colors.secondaryText, style = ViewerTypography.secondary)
        }
        if (state.devices.isEmpty() && !state.isRefreshingDevices) {
            Text(localizedStringResource(Res.string.no_connected_devices, language), color = colors.secondaryText, style = ViewerTypography.secondary)
        }
        state.deviceError?.let { Text(it, color = colors.error, style = ViewerTypography.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.sectionGap)) {
            ContextValue(localizedStringResource(Res.string.gpu_renderer, language), selectedDevice?.gpuRenderer, Modifier.weight(1.3f))
            ContextValue(localizedStringResource(Res.string.gpu_vendor, language), selectedDevice?.gpuVendor, Modifier.weight(1f))
            ContextValue(localizedStringResource(Res.string.gpu_driver, language), selectedDevice?.driverVersion, Modifier.weight(1f))
            ContextValue(
                localizedStringResource(Res.string.graphics_api, language),
                selectedDevice?.graphicsApis?.takeIf { it.isNotEmpty() }?.joinToString { it.displayName(language) },
                Modifier.weight(1f),
            )
            ContextValue(localizedStringResource(Res.string.graphics_implementation, language), selectedDevice?.graphicsImplementation?.name, Modifier.weight(1.3f))
        }
    }
}

/** Right rail: the probed AGI executable capability, including every recorded warning. */
@Composable
internal fun GpuCapabilityRail(
    capability: AgiCapability?,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    StudioPanel(Modifier.fillMaxWidth().testTag("gpu-capability-rail")) {
        StudioPanelHeader(
            title = localizedStringResource(Res.string.agi_capability, language),
            subtitle = localizedStringResource(Res.string.hub_positioning, language),
            actions = {
                StudioStatusChip(
                    label = capability?.launchMode?.displayName(language) ?: localizedStringResource(Res.string.unavailable, language),
                    tone = if (capability?.launchSupported == true) StudioStatusTone.SUCCESS else StudioStatusTone.WARNING,
                )
            },
        )
        ContextValue(localizedStringResource(Res.string.agi_version, language), capability?.version, Modifier.fillMaxWidth())
        ContextValue(localizedStringResource(Res.string.agi_executable, language), capability?.executable?.toString(), Modifier.fillMaxWidth())
        CapabilityFlag(localizedStringResource(Res.string.capability_launch_supported, language), capability?.launchSupported == true, language)
        CapabilityFlag(localizedStringResource(Res.string.capability_artifact_open_supported, language), capability?.artifactOpenSupported == true, language)
        SectionTitle(localizedStringResource(Res.string.capability_supported_arguments, language))
        val arguments = capability?.supportedArguments.orEmpty().sorted()
        if (arguments.isEmpty()) {
            Text(localizedStringResource(Res.string.capability_no_arguments, language), color = colors.secondaryText, style = ViewerTypography.secondary)
        } else {
            Text(arguments.joinToString(" "), color = colors.primaryText, style = ViewerTypography.secondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        SectionTitle(localizedStringResource(Res.string.capability_warnings, language))
        val warnings = capability?.warnings.orEmpty()
        if (warnings.isEmpty()) {
            Text(localizedStringResource(Res.string.capability_no_warnings, language), color = colors.secondaryText, style = ViewerTypography.secondary)
        } else {
            warnings.forEach { warning ->
                Text("• ${localizedWarning(warning, language)}", color = colors.warning, style = ViewerTypography.secondary)
            }
        }
    }
}

/** Right rail: every route that can actually open the selected artifact, plus its recovery actions. */
@Composable
internal fun GpuOpenWithRail(
    state: GpuIntegrationState,
    actions: GpuIntegrationActions,
    artifact: GpuArtifact?,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    val availability = state.availabilityOf(artifact)
    StudioPanel(Modifier.fillMaxWidth().testTag("gpu-open-with-rail")) {
        StudioPanelHeader(
            title = localizedStringResource(Res.string.open_with, language),
            subtitle = artifact?.displayTitle(),
            actions = {
                StudioStatusChip(
                    label = availability?.locationStatus?.displayName(language) ?: localizedStringResource(Res.string.unavailable, language),
                    tone = availability?.locationStatus?.studioTone() ?: StudioStatusTone.NEUTRAL,
                )
            },
        )
        Text(localizedStringResource(Res.string.tool_responsibility_hint, language), color = colors.secondaryText, style = ViewerTypography.secondary)
        HorizontalDivider(color = colors.border)
        ProfilerCompactButton(
            text = availability?.primaryAction?.displayName(language) ?: localizedStringResource(Res.string.unavailable, language),
            modifier = Modifier.fillMaxWidth().testTag("gpu-open-primary"),
            enabled = !state.isBusy && availability?.canOpen == true,
            onClick = { artifact?.let(actions.onOpenArtifact) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.panelGap)) {
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.verify, language),
                modifier = Modifier.testTag("gpu-open-verify"),
                enabled = !state.isBusy && artifact != null && availability != null,
                onClick = { artifact?.let(actions.onVerifyArtifact) },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.show_in_files, language),
                modifier = Modifier.testTag("gpu-open-reveal"),
                enabled = !state.isBusy && artifact != null,
                onClick = { artifact?.let(actions.onRevealArtifact) },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.relocate, language),
                modifier = Modifier.testTag("gpu-open-relocate"),
                enabled = !state.isBusy && artifact != null,
                onClick = { artifact?.let(actions.onRelocateArtifact) },
            )
        }
    }
}

@Composable
internal fun CapabilityFlag(label: String, supported: Boolean, language: UiLanguage) {
    val colors = LocalViewerColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.panelGap), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = colors.secondaryText, style = ViewerTypography.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            localizedStringResource(if (supported) Res.string.yes else Res.string.no, language),
            color = if (supported) colors.success else colors.warning,
            style = ViewerTypography.secondary,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = ViewerTypography.subsectionTitle, fontWeight = FontWeight.SemiBold, color = LocalViewerColors.current.primaryText)
}

@Composable
internal fun ContextValue(label: String, value: String?, modifier: Modifier = Modifier) {
    val colors = LocalViewerColors.current
    Column(modifier.padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, color = colors.secondaryText, style = ViewerTypography.dense, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value ?: "—", color = colors.primaryText, style = ViewerTypography.bodyCompact, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal fun GpuDeviceContext.selectorLabel(): String =
    listOfNotNull(displayName?.takeIf(String::isNotBlank), model?.takeIf(String::isNotBlank), serial?.takeIf(String::isNotBlank))
        .distinct()
        .joinToString(" · ")

internal fun GraphicsApi.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            GraphicsApi.VULKAN -> Res.string.graphics_api_vulkan
            GraphicsApi.OPENGL_ES -> Res.string.graphics_api_opengl_es
            GraphicsApi.WEBGPU -> Res.string.graphics_api_webgpu
            GraphicsApi.UNKNOWN -> Res.string.graphics_api_unknown
        },
        language,
    )
