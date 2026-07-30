package com.androidperformancestudio.perfetto.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.perfetto.model.PerfettoCaptureConfig
import com.androidperformancestudio.perfetto.model.PerfettoCaptureState
import com.androidperformancestudio.perfetto.model.PerfettoTraceTemplate
import com.androidperformancestudio.perfetto_presentation.generated.resources.Res
import com.androidperformancestudio.perfetto_presentation.generated.resources.additional_categories_events
import com.androidperformancestudio.perfetto_presentation.generated.resources.app_performance
import com.androidperformancestudio.perfetto_presentation.generated.resources.app_performance_description
import com.androidperformancestudio.perfetto_presentation.generated.resources.buffer_mb
import com.androidperformancestudio.perfetto_presentation.generated.resources.comma_separated_atrace_categories_or_ftrace_events
import com.androidperformancestudio.perfetto_presentation.generated.resources.completed_mb
import com.androidperformancestudio.perfetto_presentation.generated.resources.configuration
import com.androidperformancestudio.perfetto_presentation.generated.resources.custom
import com.androidperformancestudio.perfetto_presentation.generated.resources.custom_description
import com.androidperformancestudio.perfetto_presentation.generated.resources.custom_traceconfig_text_protobuf
import com.androidperformancestudio.perfetto_presentation.generated.resources.duration_seconds
import com.androidperformancestudio.perfetto_presentation.generated.resources.failed
import com.androidperformancestudio.perfetto_presentation.generated.resources.graphics_pipeline
import com.androidperformancestudio.perfetto_presentation.generated.resources.graphics_pipeline_description
import com.androidperformancestudio.perfetto_presentation.generated.resources.input_latency
import com.androidperformancestudio.perfetto_presentation.generated.resources.input_latency_description
import com.androidperformancestudio.perfetto_presentation.generated.resources.memory_profile
import com.androidperformancestudio.perfetto_presentation.generated.resources.memory_profile_description
import com.androidperformancestudio.perfetto_presentation.generated.resources.open_in_perfetto_ui
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
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Path

@Composable
@Suppress("ktlint:standard:function-naming")
fun PerfettoCapturePage(
    captureState: PerfettoCaptureState,
    language: UiLanguage = UiLanguage.ENGLISH,
    onStartCapture: (PerfettoCaptureConfig, String) -> Unit,
    onStopCapture: () -> Unit,
    onOpenTrace: (Path) -> Unit,
    selectedDeviceSerial: String? = null,
    modifier: Modifier = Modifier,
) {
    var selectedTemplate by remember { mutableStateOf(PerfettoTraceTemplate.SYSTEM_OVERVIEW) }
    var targetPackage by remember { mutableStateOf("") }
    var durationSeconds by remember { mutableStateOf(10) }
    var bufferSizeKb by remember { mutableStateOf(32768) }
    var additionalCategories by remember { mutableStateOf("") }
    var customConfigText by remember { mutableStateOf("") }

    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PerfettoTemplatePanel(
            selectedTemplate = selectedTemplate,
            onSelectTemplate = { selectedTemplate = it },
            modifier = Modifier.width(260.dp).fillMaxHeight(),
            language = language,
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
            additionalCategories = additionalCategories,
            onAdditionalCategoriesChange = { additionalCategories = it },
            customConfigText = customConfigText,
            onCustomConfigTextChange = { customConfigText = it },
            onStartCapture = onStartCapture,
            onStopCapture = onStopCapture,
            onOpenTrace = onOpenTrace,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            language = language,
        )
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
                    .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
    val shape = RoundedCornerShape(4.dp)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ).border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = template.displayName(language),
            color =
                if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = template.description(language),
            color =
                if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            fontSize = 10.sp,
            lineHeight = 13.sp,
        )
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
    additionalCategories: String,
    onAdditionalCategoriesChange: (String) -> Unit,
    customConfigText: String,
    onCustomConfigTextChange: (String) -> Unit,
    onStartCapture: (PerfettoCaptureConfig, String) -> Unit,
    onStopCapture: () -> Unit,
    onOpenTrace: (Path) -> Unit,
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                }
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PerfettoCompactButton(
                    text = localizedStringResource(Res.string.start_capture, language),
                    onClick = {
                        selectedDeviceSerial?.let { deviceSerial ->
                            onStartCapture(
                                createCaptureConfig(
                                    selectedTemplate = selectedTemplate,
                                    targetPackage = targetPackage,
                                    durationSeconds = durationSeconds,
                                    bufferSizeKb = bufferSizeKb,
                                    additionalCategories = additionalCategories,
                                    customConfigText = customConfigText,
                                ),
                                deviceSerial,
                            )
                        }
                    },
                    enabled = canStartCapture,
                    selected = canStartCapture,
                )
                if (captureState is PerfettoCaptureState.Recording) {
                    PerfettoCompactButton(text = localizedStringResource(Res.string.stop, language), onClick = onStopCapture)
                }
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
        fontSize = 10.sp,
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
    Text(
        text =
            when (state) {
                is PerfettoCaptureState.Idle -> localizedStringResource(Res.string.ready, language)
                is PerfettoCaptureState.Preparing -> localizedStringResource(Res.string.preparing_capture, language)
                is PerfettoCaptureState.Recording -> localizedStringResource(Res.string.recording_since, language, state.startTime)
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
        fontSize = 11.sp,
    )
}

private fun createCaptureConfig(
    selectedTemplate: PerfettoTraceTemplate,
    targetPackage: String,
    durationSeconds: Int,
    bufferSizeKb: Int,
    additionalCategories: String,
    customConfigText: String,
): PerfettoCaptureConfig =
    PerfettoCaptureConfig(
        template = selectedTemplate,
        targetPackage = targetPackage.ifBlank { null },
        durationSeconds = durationSeconds,
        bufferSizeKb = bufferSizeKb,
        additionalCategories =
            additionalCategories
                .split(',', '\n')
                .map(String::trim)
                .filter(String::isNotEmpty),
        customConfigText =
            customConfigText.takeIf {
                selectedTemplate == PerfettoTraceTemplate.CUSTOM && it.isNotBlank()
            },
    )

private fun isCaptureStartAllowed(
    state: PerfettoCaptureState,
    selectedDeviceSerial: String?,
    customConfigReady: Boolean,
): Boolean {
    val preparing = state is PerfettoCaptureState.Preparing
    val recording = state is PerfettoCaptureState.Recording
    val pulling = state is PerfettoCaptureState.Pulling
    return selectedDeviceSerial != null && customConfigReady && !preparing && !recording && !pulling
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
