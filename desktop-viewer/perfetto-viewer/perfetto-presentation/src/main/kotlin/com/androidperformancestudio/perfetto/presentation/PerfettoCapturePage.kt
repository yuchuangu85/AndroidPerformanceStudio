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
import java.nio.file.Path

@Composable
@Suppress("ktlint:standard:function-naming")
fun PerfettoCapturePage(
    captureState: PerfettoCaptureState,
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
        )
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun PerfettoTemplatePanel(
    selectedTemplate: PerfettoTraceTemplate,
    onSelectTemplate: (PerfettoTraceTemplate) -> Unit,
    modifier: Modifier = Modifier,
) {
    PerfettoWorkspacePanel(
        title = "TRACE TEMPLATE",
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
            text = template.displayName,
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
            text = template.description,
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
        title = "CONFIGURATION",
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
            CompactFieldLabel("Target package")
            PerfettoCompactTextField(
                value = targetPackage,
                onValueChange = onTargetPackageChange,
                placeholder = "System-wide (for example com.example.app)",
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CompactFieldLabel("Duration (seconds)")
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
                    CompactFieldLabel("Buffer (MB)")
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

            CompactFieldLabel("Additional categories / events")
            PerfettoCompactTextField(
                value = additionalCategories,
                onValueChange = onAdditionalCategoriesChange,
                placeholder = "Comma-separated atrace categories or ftrace events",
                modifier = Modifier.fillMaxWidth(),
            )

            if (selectedTemplate == PerfettoTraceTemplate.CUSTOM) {
                CompactFieldLabel("Custom TraceConfig (text protobuf)")
                PerfettoCompactTextField(
                    value = customConfigText,
                    onValueChange = onCustomConfigTextChange,
                    placeholder = "Paste a valid Perfetto TraceConfig",
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
                    text = "Start Capture",
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
                    PerfettoCompactButton(text = "Stop", onClick = onStopCapture)
                }
                if (captureState is PerfettoCaptureState.Completed) {
                    PerfettoCompactButton(
                        text = "Open in Perfetto UI",
                        onClick = { onOpenTrace(captureState.traceFile) },
                    )
                }
            }

            CaptureStatus(captureState)
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
private fun CaptureStatus(state: PerfettoCaptureState) {
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
                is PerfettoCaptureState.Idle -> "Ready"
                is PerfettoCaptureState.Preparing -> "Preparing capture…"
                is PerfettoCaptureState.Recording -> "Recording since ${state.startTime}"
                is PerfettoCaptureState.Pulling -> "Pulling trace from device…"
                is PerfettoCaptureState.Completed ->
                    "Completed · ${state.metadata.traceFileSizeBytes / 1024 / 1024} MB"
                is PerfettoCaptureState.Failed -> "Failed: ${state.error.message}"
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
