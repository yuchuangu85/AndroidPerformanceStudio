package com.androidperformancestudio.perfetto.presentation

import org.jetbrains.compose.resources.stringResource

import com.androidperformancestudio.perfetto_presentation.generated.resources.Res
import com.androidperformancestudio.perfetto_presentation.generated.resources.*

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
import java.nio.file.Path

@Composable
@Suppress("ktlint:standard:function-naming")
fun PerfettoCapturePage(
    captureState: PerfettoCaptureState,
    chinese: Boolean = false,
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
            chinese = chinese,
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
            chinese = chinese,
        )
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun PerfettoTemplatePanel(
    selectedTemplate: PerfettoTraceTemplate,
    onSelectTemplate: (PerfettoTraceTemplate) -> Unit,
    modifier: Modifier = Modifier,
    chinese: Boolean,
) {
    PerfettoWorkspacePanel(
        title = stringResource(Res.string.trace_template),
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
                    chinese = chinese,
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
    chinese: Boolean,
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
            text = template.displayName(chinese),
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
            text = template.description(chinese),
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
    chinese: Boolean,
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
        title = stringResource(Res.string.configuration),
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
            CompactFieldLabel(stringResource(Res.string.target_package))
            PerfettoCompactTextField(
                value = targetPackage,
                onValueChange = onTargetPackageChange,
                placeholder = stringResource(Res.string.system_wide_for_example_com_example_app),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CompactFieldLabel(stringResource(Res.string.duration_seconds))
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
                    CompactFieldLabel(stringResource(Res.string.buffer_mb))
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

            CompactFieldLabel(stringResource(Res.string.additional_categories_events))
            PerfettoCompactTextField(
                value = additionalCategories,
                onValueChange = onAdditionalCategoriesChange,
                placeholder = stringResource(Res.string.comma_separated_atrace_categories_or_ftrace_events),
                modifier = Modifier.fillMaxWidth(),
            )

            if (selectedTemplate == PerfettoTraceTemplate.CUSTOM) {
                CompactFieldLabel(stringResource(Res.string.custom_traceconfig_text_protobuf))
                PerfettoCompactTextField(
                    value = customConfigText,
                    onValueChange = onCustomConfigTextChange,
                    placeholder = stringResource(Res.string.paste_a_valid_perfetto_traceconfig),
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
                    text = stringResource(Res.string.start_capture),
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
                    PerfettoCompactButton(text = stringResource(Res.string.stop), onClick = onStopCapture)
                }
                if (captureState is PerfettoCaptureState.Completed) {
                    PerfettoCompactButton(
                        text = stringResource(Res.string.open_in_perfetto_ui),
                        onClick = { onOpenTrace(captureState.traceFile) },
                    )
                }
            }

            CaptureStatus(captureState, chinese)
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
private fun CaptureStatus(state: PerfettoCaptureState, chinese: Boolean) {
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
                is PerfettoCaptureState.Idle -> stringResource(Res.string.ready)
                is PerfettoCaptureState.Preparing -> stringResource(Res.string.preparing_capture)
                is PerfettoCaptureState.Recording -> stringResource(Res.string.recording_since, state.startTime)
                is PerfettoCaptureState.Pulling -> stringResource(Res.string.pulling_trace_from_device)
                is PerfettoCaptureState.Completed ->
                    stringResource(Res.string.completed_mb, state.metadata.traceFileSizeBytes / 1024 / 1024)
                is PerfettoCaptureState.Failed -> stringResource(Res.string.failed, state.error.message)
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
private fun PerfettoTraceTemplate.displayName(chinese: Boolean): String =
    stringResource(when (this) {
            PerfettoTraceTemplate.SYSTEM_OVERVIEW -> Res.string.system_overview
            PerfettoTraceTemplate.APP_PERFORMANCE -> Res.string.app_performance
            PerfettoTraceTemplate.GFX_PIPELINE -> Res.string.graphics_pipeline
            PerfettoTraceTemplate.INPUT_LATENCY -> Res.string.input_latency
            PerfettoTraceTemplate.MEMORY_PROFILE -> Res.string.memory_profile
            PerfettoTraceTemplate.CUSTOM -> Res.string.custom
        }, )

@Composable
private fun PerfettoTraceTemplate.description(chinese: Boolean): String =
    stringResource(when (this) {
            PerfettoTraceTemplate.SYSTEM_OVERVIEW -> Res.string.system_overview_description
            PerfettoTraceTemplate.APP_PERFORMANCE -> Res.string.app_performance_description
            PerfettoTraceTemplate.GFX_PIPELINE -> Res.string.graphics_pipeline_description
            PerfettoTraceTemplate.INPUT_LATENCY -> Res.string.input_latency_description
            PerfettoTraceTemplate.MEMORY_PROFILE -> Res.string.memory_profile_description
            PerfettoTraceTemplate.CUSTOM -> Res.string.custom_description
        }, )
