package com.androidperformancestudio.perfetto.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.perfetto.model.PerfettoCaptureConfig
import com.androidperformancestudio.perfetto.model.PerfettoCaptureState
import com.androidperformancestudio.perfetto.model.PerfettoTraceTemplate
import java.nio.file.Path

@Composable
fun PerfettoCapturePage(
    captureState: PerfettoCaptureState,
    adbPath: String,
    onAdbPathChange: (String) -> Unit,
    onStartCapture: (PerfettoCaptureConfig, String) -> Unit,
    onStopCapture: () -> Unit,
    onOpenTrace: (Path) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTemplate by remember { mutableStateOf(PerfettoTraceTemplate.SYSTEM_OVERVIEW) }
    var targetPackage by remember { mutableStateOf("") }
    var durationSeconds by remember { mutableStateOf(10) }
    var bufferSizeKb by remember { mutableStateOf(32768) }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ADB path
        OutlinedTextField(
            value = adbPath,
            onValueChange = onAdbPathChange,
            label = { Text("ADB Path") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // Template selector
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Trace Template", style = MaterialTheme.typography.titleSmall)
                PerfettoTraceTemplate.entries.forEach { template ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedTemplate == template, onClick = { selectedTemplate = template })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(template.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(template.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Configuration
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Configuration", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = targetPackage,
                    onValueChange = { targetPackage = it },
                    label = { Text("Target Package (empty = system-wide)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("e.g. com.example.app") },
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = durationSeconds.toString(),
                        onValueChange = { it.toIntOrNull()?.let { v -> durationSeconds = v.coerceIn(1, 600) } },
                        label = { Text("Duration (s)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = (bufferSizeKb / 1024).toString(),
                        onValueChange = { it.toIntOrNull()?.let { v -> bufferSizeKb = (v * 1024).coerceIn(1024, 1048576) } },
                        label = { Text("Buffer (MB)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
            }
        }

        // Controls
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val config = PerfettoCaptureConfig(
                        template = selectedTemplate,
                        targetPackage = targetPackage.ifBlank { null },
                        durationSeconds = durationSeconds,
                        bufferSizeKb = bufferSizeKb,
                    )
                    onStartCapture(config, "")
                },
                enabled = captureState is PerfettoCaptureState.Idle,
            ) { Text("Start Capture") }

            if (captureState is PerfettoCaptureState.Recording) {
                OutlinedButton(onClick = onStopCapture) { Text("Stop") }
            }
        }

        // Status
        when (val state = captureState) {
            is PerfettoCaptureState.Idle -> Text("Ready", color = MaterialTheme.colorScheme.onSurfaceVariant)
            is PerfettoCaptureState.Preparing -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Preparing...")
            }
            is PerfettoCaptureState.Recording -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Recording since ${state.startTime}")
            }
            is PerfettoCaptureState.Pulling -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Pulling trace...")
            }
            is PerfettoCaptureState.Completed -> {
                Text("Done: ${state.metadata.traceFileSizeBytes / 1024 / 1024}MB")
                Button(onClick = { onOpenTrace(state.traceFile) }) {
                    Text("Open in Perfetto UI")
                }
            }
            is PerfettoCaptureState.Failed -> Text("Failed: ${state.error.message}", color = MaterialTheme.colorScheme.error)
        }
    }
}
