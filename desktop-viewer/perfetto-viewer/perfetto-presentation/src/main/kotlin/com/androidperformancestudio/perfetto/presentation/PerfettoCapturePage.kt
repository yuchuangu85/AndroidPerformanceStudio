package com.androidperformancestudio.perfetto.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.perfetto.model.PerfettoCaptureConfig
import com.androidperformancestudio.perfetto.model.PerfettoCaptureState
import com.androidperformancestudio.perfetto.model.PerfettoBufferPolicy
import com.androidperformancestudio.perfetto.model.PerfettoAtraceCategory
import com.androidperformancestudio.perfetto.model.PerfettoDeviceCapabilities
import com.androidperformancestudio.perfetto.model.PerfettoLogBuffer
import com.androidperformancestudio.perfetto.model.PerfettoProbe
import com.androidperformancestudio.perfetto.model.PerfettoProbeGroup
import com.androidperformancestudio.perfetto.model.PerfettoTraceTemplate
import com.androidperformancestudio.perfetto.model.defaultProbes
import com.androidperformancestudio.perfetto_presentation.generated.resources.Res
import com.androidperformancestudio.perfetto_presentation.generated.resources.additional_categories_events
import com.androidperformancestudio.perfetto_presentation.generated.resources.app_performance
import com.androidperformancestudio.perfetto_presentation.generated.resources.app_performance_description
import com.androidperformancestudio.perfetto_presentation.generated.resources.buffer_mb
import com.androidperformancestudio.perfetto_presentation.generated.resources.capability_query_failed
import com.androidperformancestudio.perfetto_presentation.generated.resources.comma_separated_atrace_categories_or_ftrace_events
import com.androidperformancestudio.perfetto_presentation.generated.resources.completed_mb
import com.androidperformancestudio.perfetto_presentation.generated.resources.configuration
import com.androidperformancestudio.perfetto_presentation.generated.resources.custom
import com.androidperformancestudio.perfetto_presentation.generated.resources.custom_description
import com.androidperformancestudio.perfetto_presentation.generated.resources.custom_traceconfig_text_protobuf
import com.androidperformancestudio.perfetto_presentation.generated.resources.data_sources
import com.androidperformancestudio.perfetto_presentation.generated.resources.fill_policy
import com.androidperformancestudio.perfetto_presentation.generated.resources.fill_policy_discard
import com.androidperformancestudio.perfetto_presentation.generated.resources.duration_seconds
import com.androidperformancestudio.perfetto_presentation.generated.resources.failed
import com.androidperformancestudio.perfetto_presentation.generated.resources.graphics_pipeline
import com.androidperformancestudio.perfetto_presentation.generated.resources.graphics_pipeline_description
import com.androidperformancestudio.perfetto_presentation.generated.resources.input_latency
import com.androidperformancestudio.perfetto_presentation.generated.resources.input_latency_description
import com.androidperformancestudio.perfetto_presentation.generated.resources.memory_profile
import com.androidperformancestudio.perfetto_presentation.generated.resources.memory_profile_description
import com.androidperformancestudio.perfetto_presentation.generated.resources.open_in_perfetto_ui
import com.androidperformancestudio.perfetto_presentation.generated.resources.export_config
import com.androidperformancestudio.perfetto_presentation.generated.resources.paste_a_valid_perfetto_traceconfig
import com.androidperformancestudio.perfetto_presentation.generated.resources.preparing_capture
import com.androidperformancestudio.perfetto_presentation.generated.resources.pulling_trace_from_device
import com.androidperformancestudio.perfetto_presentation.generated.resources.ready
import com.androidperformancestudio.perfetto_presentation.generated.resources.recording_since
import com.androidperformancestudio.perfetto_presentation.generated.resources.start_capture
import com.androidperformancestudio.perfetto_presentation.generated.resources.stop
import com.androidperformancestudio.perfetto_presentation.generated.resources.system_overview
import com.androidperformancestudio.perfetto_presentation.generated.resources.system_overview_description
import com.androidperformancestudio.perfetto_presentation.generated.resources.system_wide_for_example_com_example_app
import com.androidperformancestudio.perfetto_presentation.generated.resources.target_package
import com.androidperformancestudio.perfetto_presentation.generated.resources.trace_template
import com.androidperformancestudio.ui.CopyableText
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioCard
import com.androidperformancestudio.ui.studio.StudioTokens
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
@Suppress("ktlint:standard:function-naming")
fun PerfettoCapturePage(
    captureState: PerfettoCaptureState,
    language: UiLanguage = UiLanguage.ENGLISH,
    onStartCapture: (PerfettoCaptureConfig, String) -> Unit,
    onStopCapture: () -> Unit,
    onOpenTrace: (Path) -> Unit,
    onExportConfig: (PerfettoCaptureConfig) -> Unit = {},
    selectedDeviceSerial: String? = null,
    deviceCapabilities: PerfettoDeviceCapabilities? = null,
    modifier: Modifier = Modifier,
) {
    var selectedTemplate by remember { mutableStateOf(PerfettoTraceTemplate.SYSTEM_OVERVIEW) }
    var targetPackage by remember { mutableStateOf("") }
    var durationSeconds by remember { mutableStateOf(30) }
    var bufferSizeKb by remember { mutableStateOf(65536) }
    var additionalCategories by remember { mutableStateOf("") }
    var customConfigText by remember { mutableStateOf("") }
    var enabledProbes by remember { mutableStateOf(selectedTemplate.defaultProbes()) }
    var bufferPolicy by remember { mutableStateOf(PerfettoBufferPolicy.RING_BUFFER) }
    var pollIntervals by remember { mutableStateOf(PerfettoCaptureConfig.DEFAULT_POLL_INTERVALS) }
    var atraceCategories by remember { mutableStateOf(atracePreset(selectedTemplate)) }
    var logBuffers by remember { mutableStateOf<Set<PerfettoLogBuffer>>(emptySet()) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val currentConfigurationWidth =
            (
                maxWidth -
                    BASE_TEMPLATE_PANEL_WIDTH -
                    BASE_DATA_SOURCES_PANEL_WIDTH -
                    PANEL_DIVIDER_WIDTH -
                    PANEL_DIVIDER_WIDTH
            ).coerceAtLeast(0.dp)
        val redistributedWidth = currentConfigurationWidth / 3f
        val addedPanelWidth = redistributedWidth / 2f
        val configurationWidth = currentConfigurationWidth - redistributedWidth

        Row(Modifier.fillMaxSize()) {
            PerfettoTemplatePanel(
                selectedTemplate = selectedTemplate,
                onSelectTemplate = {
                    selectedTemplate = it
                    enabledProbes = it.defaultProbes()
                    pollIntervals = PerfettoCaptureConfig.DEFAULT_POLL_INTERVALS
                    atraceCategories = atracePreset(it)
                    logBuffers = emptySet()
                },
                modifier = Modifier.width(BASE_TEMPLATE_PANEL_WIDTH + addedPanelWidth).fillMaxHeight(),
                language = language,
            )
            VerticalDivider(
                modifier = Modifier.fillMaxHeight(),
                color = MaterialTheme.colorScheme.outline,
            )
            PerfettoDataSourcesPanel(
                selectedTemplate = selectedTemplate,
                enabledProbes = enabledProbes,
                onProbeChange = { probe, enabled ->
                    enabledProbes = if (enabled) enabledProbes + probe else enabledProbes - probe
                },
                pollIntervals = pollIntervals,
                onPollIntervalChange = { probe, ms ->
                    pollIntervals = pollIntervals + (probe to ms.coerceAtLeast(PerfettoCaptureConfig.MIN_POLL_INTERVAL_MS + 1))
                },
                atraceCategories = atraceCategories,
                onAtraceCategoriesChange = { atraceCategories = it },
                logBuffers = logBuffers,
                onLogBuffersChange = { logBuffers = it },
                targetPackage = targetPackage,
                capabilities = deviceCapabilities,
                modifier = Modifier.width(BASE_DATA_SOURCES_PANEL_WIDTH + addedPanelWidth).fillMaxHeight(),
                language = language,
            )
            VerticalDivider(
                modifier = Modifier.fillMaxHeight(),
                color = MaterialTheme.colorScheme.outline,
            )
            PerfettoConfigurationPanel(
                captureState = captureState,
                selectedTemplate = selectedTemplate,
                selectedDeviceSerial = selectedDeviceSerial,
                targetPackage = targetPackage,
                onTargetPackageChange = { targetPackage = it },
                durationSeconds = durationSeconds,
                onDurationSecondsChange = { durationSeconds = it },
                bufferSizeKb = bufferSizeKb,
                onBufferSizeKbChange = { bufferSizeKb = it },
                bufferPolicy = bufferPolicy,
                onBufferPolicyChange = { bufferPolicy = it },
                pollIntervals = pollIntervals,
                atraceCategories = atraceCategories,
                logBuffers = logBuffers,
                additionalCategories = additionalCategories,
                onAdditionalCategoriesChange = { additionalCategories = it },
                customConfigText = customConfigText,
                onCustomConfigTextChange = { customConfigText = it },
                enabledProbes = enabledProbes,
                deviceCapabilities = deviceCapabilities,
                onStartCapture = onStartCapture,
                onStopCapture = onStopCapture,
                onOpenTrace = onOpenTrace,
                onExportConfig = onExportConfig,
                modifier = Modifier.width(configurationWidth).fillMaxHeight(),
                language = language,
            )
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun PerfettoDataSourcesPanel(
    selectedTemplate: PerfettoTraceTemplate,
    enabledProbes: Set<PerfettoProbe>,
    onProbeChange: (PerfettoProbe, Boolean) -> Unit,
    pollIntervals: Map<PerfettoProbe, Int>,
    onPollIntervalChange: (PerfettoProbe, Int) -> Unit,
    atraceCategories: Set<String>,
    onAtraceCategoriesChange: (Set<String>) -> Unit,
    logBuffers: Set<PerfettoLogBuffer>,
    onLogBuffersChange: (Set<PerfettoLogBuffer>) -> Unit,
    targetPackage: String,
    capabilities: PerfettoDeviceCapabilities?,
    modifier: Modifier = Modifier,
    language: UiLanguage,
) {
    PerfettoWorkspacePanel(
        title = localizedStringResource(Res.string.data_sources, language),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(StudioTokens.workspacePadding),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
        ) {
            capabilities?.queryError?.let { error ->
                CopyableText(
                    text = localizedStringResource(Res.string.capability_query_failed, language, error),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = ViewerTypography.dense.fontSize,
                    lineHeight = ViewerTypography.dense.lineHeight,
                    language = language,
                )
            }
            if (selectedTemplate != PerfettoTraceTemplate.CUSTOM) {
                PerfettoProbeGroup.entries.forEach { group ->
                    PerfettoDataSourceGroupCard(
                        group = group,
                        probes = PerfettoProbe.entries.filter { it.group == group },
                        enabledProbes = enabledProbes,
                        targetPackage = targetPackage,
                        capabilities = capabilities,
                        onProbeChange = onProbeChange,
                        pollIntervals = pollIntervals,
                        onPollIntervalChange = onPollIntervalChange,
                        atraceCategories = atraceCategories,
                        onAtraceCategoriesChange = onAtraceCategoriesChange,
                        logBuffers = logBuffers,
                        onLogBuffersChange = onLogBuffersChange,
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
private fun PerfettoDataSourceGroupCard(
    group: PerfettoProbeGroup,
    probes: List<PerfettoProbe>,
    enabledProbes: Set<PerfettoProbe>,
    targetPackage: String,
    capabilities: PerfettoDeviceCapabilities?,
    onProbeChange: (PerfettoProbe, Boolean) -> Unit,
    pollIntervals: Map<PerfettoProbe, Int>,
    onPollIntervalChange: (PerfettoProbe, Int) -> Unit,
    atraceCategories: Set<String>,
    onAtraceCategoriesChange: (Set<String>) -> Unit,
    logBuffers: Set<PerfettoLogBuffer>,
    onLogBuffersChange: (Set<PerfettoLogBuffer>) -> Unit,
) {
    StudioCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        borderColor = MaterialTheme.colorScheme.outline,
        contentPadding = 7.dp,
        contentSpacing = 4.dp,
    ) {
        Text(
            text = group.displayName.uppercase(),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = ViewerTypography.secondary.fontSize,
            fontWeight = FontWeight.SemiBold,
        )
        probes.chunked(2).forEach { probeRow ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                probeRow.forEach { probe ->
                    val reason =
                        capabilities?.unsupportedReason(probe)
                            ?: if (probe.requiresTargetPackage && targetPackage.isBlank()) {
                                "Requires a target package"
                            } else {
                                null
                            }
                    PerfettoProbeOption(
                        probe = probe,
                        checked = probe in enabledProbes && reason == null,
                        reason = reason,
                        onProbeChange = onProbeChange,
                        pollIntervals = pollIntervals,
                        onPollIntervalChange = onPollIntervalChange,
                        atraceCategories = atraceCategories,
                        onAtraceCategoriesChange = onAtraceCategoriesChange,
                        logBuffers = logBuffers,
                        onLogBuffersChange = onLogBuffersChange,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (probeRow.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun PerfettoProbeOption(
    probe: PerfettoProbe,
    checked: Boolean,
    reason: String?,
    onProbeChange: (PerfettoProbe, Boolean) -> Unit,
    pollIntervals: Map<PerfettoProbe, Int>,
    onPollIntervalChange: (PerfettoProbe, Int) -> Unit,
    atraceCategories: Set<String>,
    onAtraceCategoriesChange: (Set<String>) -> Unit,
    logBuffers: Set<PerfettoLogBuffer>,
    onLogBuffersChange: (Set<PerfettoLogBuffer>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasSubParams =
        probe in PerfettoCaptureConfig.DEFAULT_POLL_INTERVALS ||
            probe == PerfettoProbe.ATRACE ||
            probe == PerfettoProbe.ANDROID_LOG
    var dialogOpen by remember { mutableStateOf(false) }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onProbeChange(probe, it) },
            enabled = reason == null,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 5.dp)) {
            Text(
                text = probe.displayName,
                color =
                    if (reason == null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                fontSize = ViewerTypography.label.fontSize,
                lineHeight = ViewerTypography.label.lineHeight,
            )
            reason?.let {
                CopyableText(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = ViewerTypography.micro.fontSize,
                    lineHeight = ViewerTypography.micro.lineHeight,
                )
            }
        }
        if (checked && reason == null && hasSubParams) {
            PerfettoCompactButton(
                text = "⚙",
                onClick = { dialogOpen = true },
                selected = false,
            )
        }
    }
    if (dialogOpen) {
        when (probe) {
            PerfettoProbe.ATRACE ->
                AtraceCategoryDialog(
                    initial = atraceCategories,
                    onConfirm = {
                        onAtraceCategoriesChange(it)
                        dialogOpen = false
                    },
                    onDismiss = { dialogOpen = false },
                )
            PerfettoProbe.ANDROID_LOG ->
                LogBufferDialog(
                    initial = logBuffers,
                    onConfirm = {
                        onLogBuffersChange(it)
                        dialogOpen = false
                    },
                    onDismiss = { dialogOpen = false },
                )
            else ->
                PollIntervalDialog(
                    probe = probe,
                    initialMs = pollIntervals[probe] ?: PerfettoCaptureConfig.DEFAULT_POLL_INTERVALS.getValue(probe),
                    onConfirm = {
                        onPollIntervalChange(probe, it)
                        dialogOpen = false
                    },
                    onDismiss = { dialogOpen = false },
                )
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun PollIntervalDialog(
    probe: PerfettoProbe,
    initialMs: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initialMs.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(probe.displayName) },
        text = {
            Column {
                Text(
                    text = "Poll interval (ms)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = ViewerTypography.label.fontSize,
                )
                PerfettoCompactTextField(
                    value = value,
                    onValueChange = { value = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                Text(
                    text = "Must be greater than ${PerfettoCaptureConfig.MIN_POLL_INTERVAL_MS} ms.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = ViewerTypography.micro.fontSize,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            PerfettoCompactButton(
                text = "OK",
                onClick = {
                    value.toIntOrNull()
                        ?.takeIf { it > PerfettoCaptureConfig.MIN_POLL_INTERVAL_MS }
                        ?.let(onConfirm)
                },
            )
        },
        dismissButton = {
            PerfettoCompactButton(text = "Cancel", onClick = onDismiss, selected = false)
        },
    )
}

@Composable
@Suppress("ktlint:standard:function-naming")
internal fun AtraceCategoryDialog(
    initial: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }
    val all = PerfettoAtraceCategory.entries
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 6.dp) {
            Column(modifier = Modifier.width(520.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Atrace categories",
                        fontSize = ViewerTypography.secondary.fontSize,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${selected.size}/${all.size}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = ViewerTypography.label.fontSize,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PerfettoCompactButton(text = "All", onClick = { selected = all.map { it.id }.toSet() })
                    PerfettoCompactButton(text = "None", onClick = { selected = emptySet() }, selected = false)
                    PerfettoCompactButton(
                        text = "Default",
                        onClick = { selected = PerfettoAtraceCategory.defaultIds.toSet() },
                        selected = false,
                    )
                }
                Box(modifier = Modifier.heightIn(max = 360.dp).padding(vertical = 4.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        all.chunked(ATRACE_DIALOG_COLUMNS).forEach { rowCategories ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                rowCategories.forEach { category ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Checkbox(
                                            checked = category.id in selected,
                                            onCheckedChange = { enabled ->
                                                selected = if (enabled) selected + category.id else selected - category.id
                                            },
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Text(
                                            text = category.displayName,
                                            fontSize = ViewerTypography.label.fontSize,
                                            lineHeight = ViewerTypography.label.lineHeight,
                                            modifier = Modifier.padding(start = 8.dp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                repeat(ATRACE_DIALOG_COLUMNS - rowCategories.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    PerfettoCompactButton(text = "Cancel", onClick = onDismiss, selected = false)
                    Spacer(Modifier.width(8.dp))
                    PerfettoCompactButton(text = "OK", onClick = { onConfirm(selected) })
                }
            }
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun LogBufferDialog(
    initial: Set<PerfettoLogBuffer>,
    onConfirm: (Set<PerfettoLogBuffer>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }
    val all = PerfettoLogBuffer.entries
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 6.dp) {
            Column(modifier = Modifier.width(460.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Log buffers",
                        fontSize = ViewerTypography.secondary.fontSize,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (selected.isEmpty()) "all" else "${selected.size}/${all.size}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = ViewerTypography.label.fontSize,
                    )
                }
                Text(
                    text = "Empty selection captures all buffers (default).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = ViewerTypography.micro.fontSize,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PerfettoCompactButton(text = "All", onClick = { selected = all.toSet() })
                    PerfettoCompactButton(text = "None", onClick = { selected = emptySet() }, selected = false)
                }
                Box(modifier = Modifier.heightIn(max = 300.dp).padding(vertical = 4.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        all.chunked(3).forEach { rowBuffers ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                rowBuffers.forEach { buffer ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Checkbox(
                                            checked = buffer in selected,
                                            onCheckedChange = { enabled ->
                                                selected = if (enabled) selected + buffer else selected - buffer
                                            },
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Text(
                                            text = buffer.displayName,
                                            fontSize = ViewerTypography.label.fontSize,
                                            lineHeight = ViewerTypography.label.lineHeight,
                                            modifier = Modifier.padding(start = 8.dp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                repeat(3 - rowBuffers.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    PerfettoCompactButton(text = "Cancel", onClick = onDismiss, selected = false)
                    Spacer(Modifier.width(8.dp))
                    PerfettoCompactButton(text = "OK", onClick = { onConfirm(selected) })
                }
            }
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun PerfettoTemplatePanel(
    selectedTemplate: PerfettoTraceTemplate,
    onSelectTemplate: (PerfettoTraceTemplate) -> Unit,
    modifier: Modifier = Modifier,
    language: UiLanguage,
) {
    PerfettoWorkspacePanel(
        title = localizedStringResource(Res.string.trace_template, language),
        modifier = modifier,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(StudioTokens.workspacePadding),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
        ) {
            PerfettoTraceTemplate.entries.forEach { template ->
                PerfettoTemplateOption(
                    template = template,
                    selected = selectedTemplate == template,
                    onClick = { onSelectTemplate(template) },
                    language = language,
                )
            }
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun PerfettoTemplateOption(
    template: PerfettoTraceTemplate,
    selected: Boolean,
    onClick: () -> Unit,
    language: UiLanguage,
) {
    StudioCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        containerColor =
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        borderColor = MaterialTheme.colorScheme.primary,
        contentPadding = 0.dp,
        contentSpacing = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = template.displayName(language),
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                fontSize = ViewerTypography.secondary.fontSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            CopyableText(
                text = template.description(language),
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)
                    },
                fontSize = ViewerTypography.label.fontSize,
                lineHeight = ViewerTypography.secondary.lineHeight,
            )
        }
    }
}

@Composable
@Suppress(
    "LongMethod",
    "LongParameterList",
    "ktlint:standard:function-naming",
)
private fun PerfettoConfigurationPanel(
    captureState: PerfettoCaptureState,
    selectedTemplate: PerfettoTraceTemplate,
    selectedDeviceSerial: String?,
    targetPackage: String,
    onTargetPackageChange: (String) -> Unit,
    durationSeconds: Int,
    onDurationSecondsChange: (Int) -> Unit,
    bufferSizeKb: Int,
    onBufferSizeKbChange: (Int) -> Unit,
    bufferPolicy: PerfettoBufferPolicy,
    onBufferPolicyChange: (PerfettoBufferPolicy) -> Unit,
    pollIntervals: Map<PerfettoProbe, Int>,
    atraceCategories: Set<String>,
    logBuffers: Set<PerfettoLogBuffer>,
    additionalCategories: String,
    onAdditionalCategoriesChange: (String) -> Unit,
    customConfigText: String,
    onCustomConfigTextChange: (String) -> Unit,
    enabledProbes: Set<PerfettoProbe>,
    deviceCapabilities: PerfettoDeviceCapabilities?,
    onStartCapture: (PerfettoCaptureConfig, String) -> Unit,
    onStopCapture: () -> Unit,
    onOpenTrace: (Path) -> Unit,
    onExportConfig: (PerfettoCaptureConfig) -> Unit,
    modifier: Modifier = Modifier,
    language: UiLanguage,
) {
    val customConfigReady =
        selectedTemplate != PerfettoTraceTemplate.CUSTOM || customConfigText.isNotBlank()
    val canStartCapture =
        isCaptureStartAllowed(
            state = captureState,
            selectedDeviceSerial = selectedDeviceSerial,
            customConfigReady = customConfigReady,
            capabilitiesReady = selectedDeviceSerial == null || deviceCapabilities != null,
        )

    PerfettoWorkspacePanel(
        title = localizedStringResource(Res.string.configuration, language),
        modifier = modifier,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactFieldLabel(localizedStringResource(Res.string.target_package, language))
            PerfettoCompactTextField(
                value = targetPackage,
                onValueChange = onTargetPackageChange,
                placeholder = localizedStringResource(Res.string.system_wide_for_example_com_example_app, language),
                modifier = Modifier.fillMaxWidth(),
            )

            CompactFieldLabel(localizedStringResource(Res.string.duration_seconds, language))
            PerfettoCompactTextField(
                value = durationSeconds.toString(),
                onValueChange = {
                    it.toIntOrNull()?.let { value ->
                        onDurationSecondsChange(value.coerceIn(1, 600))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            CompactFieldLabel(localizedStringResource(Res.string.buffer_mb, language))
            PerfettoCompactTextField(
                value = (bufferSizeKb / 1024).toString(),
                onValueChange = {
                    it.toIntOrNull()?.let { value ->
                        onBufferSizeKbChange((value * 1024).coerceIn(1024, 1048576))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            CompactFieldLabel(localizedStringResource(Res.string.fill_policy, language))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = bufferPolicy == PerfettoBufferPolicy.DISCARD,
                    onCheckedChange = {
                        onBufferPolicyChange(
                            if (it) PerfettoBufferPolicy.DISCARD else PerfettoBufferPolicy.RING_BUFFER,
                        )
                    },
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = localizedStringResource(Res.string.fill_policy_discard, language),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = ViewerTypography.label.fontSize,
                    lineHeight = ViewerTypography.label.lineHeight,
                    modifier = Modifier.padding(start = 5.dp),
                )
            }

            CompactFieldLabel(localizedStringResource(Res.string.additional_categories_events, language))
            PerfettoCompactTextField(
                value = additionalCategories,
                onValueChange = onAdditionalCategoriesChange,
                placeholder = localizedStringResource(Res.string.comma_separated_atrace_categories_or_ftrace_events, language),
                modifier = Modifier.fillMaxWidth(),
            )

            if (selectedTemplate == PerfettoTraceTemplate.CUSTOM) {
                CompactFieldLabel(localizedStringResource(Res.string.custom_traceconfig_text_protobuf, language))
                PerfettoCompactTextField(
                    value = customConfigText,
                    onValueChange = onCustomConfigTextChange,
                    placeholder = localizedStringResource(Res.string.paste_a_valid_perfetto_traceconfig, language),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    height = 150.dp,
                )
            }

            val isRecording = captureState is PerfettoCaptureState.Recording

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PerfettoCompactButton(
                    text = localizedStringResource(Res.string.export_config, language),
                    onClick = {
                        onExportConfig(
                            createCaptureConfig(
                                selectedTemplate = selectedTemplate,
                                targetPackage = targetPackage,
                                durationSeconds = durationSeconds,
                                bufferSizeKb = bufferSizeKb,
                                bufferPolicy = bufferPolicy,
                                androidSdk = deviceCapabilities?.androidSdk ?: 0,
                                pollIntervals = pollIntervals,
                                atraceCategories = atraceCategories,
                                logBuffers = logBuffers,
                                additionalCategories = additionalCategories,
                                customConfigText = customConfigText,
                                enabledProbes =
                                    enabledProbes.filterTo(mutableSetOf()) { probe ->
                                        deviceCapabilities?.unsupportedReason(probe) == null &&
                                            (!probe.requiresTargetPackage || targetPackage.isNotBlank())
                                    },
                            ),
                        )
                    },
                    selected = false,
                )
                // Single capture toggle: "开始抓取" when a capture can begin (or is in an
                // uninterruptible phase, in which case it is disabled), "停止抓取" while recording.
                // Both labels share the same width so the button does not shift on toggle.
                // The recording branch routes to onStopCapture; every other state routes to the
                // start lambda, which is a no-op when canStartCapture is false (disabled).
                PerfettoCompactButton(
                    text =
                        if (isRecording) {
                            localizedStringResource(Res.string.stop, language)
                        } else {
                            localizedStringResource(Res.string.start_capture, language)
                        },
                    onClick = {
                        if (isRecording) {
                            onStopCapture()
                        } else {
                            selectedDeviceSerial?.let { deviceSerial ->
                                onStartCapture(
                                    createCaptureConfig(
                                        selectedTemplate = selectedTemplate,
                                        targetPackage = targetPackage,
                                        durationSeconds = durationSeconds,
                                        bufferSizeKb = bufferSizeKb,
                                        bufferPolicy = bufferPolicy,
                                        androidSdk = deviceCapabilities?.androidSdk ?: 0,
                                        pollIntervals = pollIntervals,
                                        atraceCategories = atraceCategories,
                                        logBuffers = logBuffers,
                                        additionalCategories = additionalCategories,
                                        customConfigText = customConfigText,
                                        enabledProbes =
                                            enabledProbes.filterTo(mutableSetOf()) { probe ->
                                                deviceCapabilities?.unsupportedReason(probe) == null &&
                                                    (!probe.requiresTargetPackage || targetPackage.isNotBlank())
                                            },
                                    ),
                                    deviceSerial,
                                )
                            }
                        }
                    },
                    enabled = if (isRecording) true else canStartCapture,
                    selected = if (isRecording) false else canStartCapture,
                    danger = isRecording,
                )
                if (captureState is PerfettoCaptureState.Completed) {
                    PerfettoCompactButton(
                        text = localizedStringResource(Res.string.open_in_perfetto_ui, language),
                        onClick = { onOpenTrace(captureState.traceFile) },
                    )
                }
            }

            CaptureStatus(captureState, language)
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun CompactFieldLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = ViewerTypography.label.fontSize,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun CaptureStatus(
    state: PerfettoCaptureState,
    language: UiLanguage,
) {
    val inProgress =
        state is PerfettoCaptureState.Preparing ||
            state is PerfettoCaptureState.Recording ||
            state is PerfettoCaptureState.Pulling
    if (inProgress) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
    }
    CopyableText(
        text =
            when (state) {
                is PerfettoCaptureState.Idle -> localizedStringResource(Res.string.ready, language)
                is PerfettoCaptureState.Preparing -> localizedStringResource(Res.string.preparing_capture, language)
                is PerfettoCaptureState.Recording ->
                    localizedStringResource(Res.string.recording_since, language, formatCaptureTimestamp(state.startTime))
                is PerfettoCaptureState.Pulling -> localizedStringResource(Res.string.pulling_trace_from_device, language)
                is PerfettoCaptureState.Completed ->
                    localizedStringResource(Res.string.completed_mb, language, state.metadata.traceFileSizeBytes / 1024 / 1024)
                is PerfettoCaptureState.Failed -> localizedStringResource(Res.string.failed, language, state.error.message)
            },
        color =
            if (state is PerfettoCaptureState.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        fontSize = ViewerTypography.secondary.fontSize,
        language = language,
    )
}

private val BASE_TEMPLATE_PANEL_WIDTH = 180.dp
private val BASE_DATA_SOURCES_PANEL_WIDTH = 280.dp
private val PANEL_DIVIDER_WIDTH = 1.dp
private const val ATRACE_DIALOG_COLUMNS = 3

private fun createCaptureConfig(
    selectedTemplate: PerfettoTraceTemplate,
    targetPackage: String,
    durationSeconds: Int,
    bufferSizeKb: Int,
    bufferPolicy: PerfettoBufferPolicy,
    androidSdk: Int,
    pollIntervals: Map<PerfettoProbe, Int>,
    atraceCategories: Set<String>,
    logBuffers: Set<PerfettoLogBuffer>,
    additionalCategories: String,
    customConfigText: String,
    enabledProbes: Set<PerfettoProbe>,
): PerfettoCaptureConfig =
    PerfettoCaptureConfig(
        template = selectedTemplate,
        targetPackage = targetPackage.ifBlank { null },
        durationSeconds = durationSeconds,
        bufferSizeKb = bufferSizeKb,
        bufferPolicy = bufferPolicy,
        androidSdk = androidSdk,
        pollIntervals = pollIntervals,
        atraceCategories = atraceCategories,
        logBuffers = logBuffers,
        additionalCategories =
            additionalCategories
                .split(',', '\n')
                .map(String::trim)
                .filter(String::isNotEmpty),
        customConfigText =
            customConfigText.takeIf {
                selectedTemplate == PerfettoTraceTemplate.CUSTOM && it.isNotBlank()
            },
        enabledProbes = enabledProbes,
    )

/** The atrace category preset to pre-select when switching to [template]. */
private fun atracePreset(template: PerfettoTraceTemplate): Set<String> =
    when (template) {
        PerfettoTraceTemplate.SYSTEM_OVERVIEW -> PerfettoAtraceCategory.defaultIds.toSet()
        PerfettoTraceTemplate.APP_PERFORMANCE -> setOf("am", "binder_driver", "dalvik", "database", "gfx", "view", "wm")
        PerfettoTraceTemplate.GFX_PIPELINE -> setOf("gfx", "view", "wm")
        PerfettoTraceTemplate.INPUT_LATENCY -> setOf("input", "view", "wm")
        PerfettoTraceTemplate.MEMORY_PROFILE -> setOf("am", "dalvik")
        PerfettoTraceTemplate.CUSTOM -> emptySet()
    }

private fun isCaptureStartAllowed(
    state: PerfettoCaptureState,
    selectedDeviceSerial: String?,
    customConfigReady: Boolean,
    capabilitiesReady: Boolean,
): Boolean {
    val preparing = state is PerfettoCaptureState.Preparing
    val recording = state is PerfettoCaptureState.Recording
    val pulling = state is PerfettoCaptureState.Pulling
    return selectedDeviceSerial != null && capabilitiesReady && customConfigReady && !preparing && !recording && !pulling
}

@Composable
private fun PerfettoTraceTemplate.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            PerfettoTraceTemplate.SYSTEM_OVERVIEW -> Res.string.system_overview
            PerfettoTraceTemplate.APP_PERFORMANCE -> Res.string.app_performance
            PerfettoTraceTemplate.GFX_PIPELINE -> Res.string.graphics_pipeline
            PerfettoTraceTemplate.INPUT_LATENCY -> Res.string.input_latency
            PerfettoTraceTemplate.MEMORY_PROFILE -> Res.string.memory_profile
            PerfettoTraceTemplate.CUSTOM -> Res.string.custom
        },
        language,
    )

@Composable
private fun PerfettoTraceTemplate.description(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            PerfettoTraceTemplate.SYSTEM_OVERVIEW -> Res.string.system_overview_description
            PerfettoTraceTemplate.APP_PERFORMANCE -> Res.string.app_performance_description
            PerfettoTraceTemplate.GFX_PIPELINE -> Res.string.graphics_pipeline_description
            PerfettoTraceTemplate.INPUT_LATENCY -> Res.string.input_latency_description
            PerfettoTraceTemplate.MEMORY_PROFILE -> Res.string.memory_profile_description
            PerfettoTraceTemplate.CUSTOM -> Res.string.custom_description
        },
        language,
    )

/** Renders an instant as a readable local "yyyy-MM-dd HH:mm:ss" timestamp. */
internal fun formatCaptureTimestamp(
    capturedAt: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone).format(capturedAt)
