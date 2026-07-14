@file:Suppress("TooManyFunctions")

package com.androidperformancestudio.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.application.CaptureSetup
import com.androidperformancestudio.application.CaptureTarget
import com.androidperformancestudio.capture.CallGraphMode
import com.androidperformancestudio.capture.CaptureState
import com.androidperformancestudio.capture.EventScope
import com.androidperformancestudio.capture.SamplingParameters
import com.androidperformancestudio.capture.SamplingRate
import com.androidperformancestudio.capture.SamplingTemplate

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun CapturePage(
    target: CaptureTarget?,
    setup: CaptureSetup?,
    availableEvents: List<String>,
    captureState: CaptureState,
    actions: DeviceTargetActions,
) {
    val isActive = captureState.isActive()
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Capture Configuration", style = MaterialTheme.typography.headlineMedium)
                Text("Selected target: ${target.orEmptyLabel()}")
            }
            OutlinedButton(onClick = actions.onBack, enabled = !isActive) { Text("Back to Device & Target") }
        }
        CaptureControls(
            captureState = captureState,
            canStart = setup != null && !isActive,
            onStartCapture = actions.onStartCapture,
            onStopCapture = actions.onStopCapture,
            onCancelCapture = actions.onCancelCapture,
        )
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Sampling template", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SamplingTemplate.entries.forEach { template ->
                    TemplateCard(
                        template = template,
                        selected = setup?.template == template,
                        onClick = { actions.onSelectTemplate(template) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            setup?.let {
                CaptureDetails(it)
                AdvancedCaptureParameters(it, availableEvents, actions.onUpdateSamplingParameters)
            }
        }
    }
}

@Composable
@Suppress(
    "FunctionName",
    "LongMethod",
    "CyclomaticComplexMethod",
    "ComplexCondition",
    "ktlint:standard:function-naming",
)
private fun AdvancedCaptureParameters(
    setup: CaptureSetup,
    availableEvents: List<String>,
    onUpdate: (SamplingParameters) -> Unit,
) {
    var event by remember(setup.template) { mutableStateOf(setup.parameters.event) }
    var rateValue by remember(setup.template) { mutableStateOf(setup.parameters.rate.valueText()) }
    var duration by remember(setup.template) {
        mutableStateOf(
            setup.parameters.durationSeconds
                ?.toString()
                .orEmpty(),
        )
    }
    val periodMode = setup.parameters.rate is SamplingRate.Period

    fun commitNumericValues() {
        val numericRate = rateValue.toLongOrNull()?.takeIf { it > 0 }
        val parsedRate =
            when {
                numericRate == null -> null
                periodMode -> SamplingRate.Period(numericRate)
                numericRate <= Int.MAX_VALUE -> SamplingRate.Frequency(numericRate.toInt())
                else -> null
            }
        val durationSeconds = duration.toDoubleOrNull()?.takeIf { it > 0 }
        val durationValid = duration.isBlank() || durationSeconds != null
        if (parsedRate != null && durationValid && event.isNotBlank() && event.none(Char::isWhitespace)) {
            onUpdate(
                setup.parameters.copy(
                    event = event,
                    rate = parsedRate,
                    durationSeconds = durationSeconds,
                ),
            )
        }
    }

    Card(modifier = Modifier.fillMaxWidth().widthIn(max = 1200.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Advanced parameters", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = event,
                onValueChange = {
                    event = it
                    commitNumericValues()
                },
                label = { Text("Event") },
                singleLine = true,
            )
            if (availableEvents.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    availableEvents.take(MAX_EVENT_CHIPS).forEach { candidate ->
                        FilterChip(
                            selected = event == candidate,
                            onClick = {
                                event = candidate
                                commitNumericValues()
                            },
                            label = { Text(candidate) },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = !periodMode,
                    onClick = {
                        rateValue.toIntOrNull()?.takeIf { it > 0 }?.let {
                            onUpdate(setup.parameters.copy(rate = SamplingRate.Frequency(it)))
                        }
                    },
                    label = { Text("Frequency") },
                )
                FilterChip(
                    selected = periodMode,
                    onClick = {
                        rateValue.toLongOrNull()?.takeIf { it > 0 }?.let {
                            onUpdate(setup.parameters.copy(rate = SamplingRate.Period(it)))
                        }
                    },
                    label = { Text("Period") },
                )
                OutlinedTextField(
                    value = rateValue,
                    onValueChange = {
                        rateValue = it
                        commitNumericValues()
                    },
                    label = { Text(if (periodMode) "Events per sample" else "Hz") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = duration,
                    onValueChange = {
                        duration = it
                        commitNumericValues()
                    },
                    label = { Text("Duration seconds (blank = manual stop)") },
                    singleLine = true,
                )
            }
            ParameterChips("Call graph", CallGraphMode.entries, setup.parameters.callGraph) {
                onUpdate(setup.parameters.copy(callGraph = it))
            }
            ParameterChips("Scope", EventScope.entries, setup.parameters.scope) {
                onUpdate(setup.parameters.copy(scope = it))
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun <T : Enum<T>> ParameterChips(
    label: String,
    values: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label:")
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(value.name.replace('_', ' ')) },
            )
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun CaptureControls(
    captureState: CaptureState,
    canStart: Boolean,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onCancelCapture: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().widthIn(max = 1200.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Capture status", style = MaterialTheme.typography.titleMedium)
                Text(captureState.statusText())
                if (!captureState.isActive()) {
                    Text(
                        "Click Get data to run Simpleperf automatically and open the report. " +
                            "No command input is required.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                captureState.sessionPath()?.let {
                    SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (captureState.isActive()) {
                if (captureState is CaptureState.Recording) {
                    Button(onClick = onStopCapture) { Text("Stop and analyze") }
                }
                OutlinedButton(onClick = onCancelCapture) { Text("Cancel") }
            } else {
                Button(onClick = onStartCapture, enabled = canStart) { Text("Get data") }
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun TemplateCard(
    template: SamplingTemplate,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(template.displayName, fontWeight = FontWeight.SemiBold)
            Text(template.description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun CaptureDetails(setup: CaptureSetup) {
    Card(modifier = Modifier.fillMaxWidth().widthIn(max = 1200.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Parameters", style = MaterialTheme.typography.titleMedium)
            Text("Event: ${setup.parameters.event}")
            Text("Rate: ${setup.parameters.rate.label()}")
            Text("Duration: ${setup.parameters.durationSeconds?.let { "$it s" } ?: "Manual stop"}")
            Text("Call graph: ${setup.parameters.callGraph.name}")
            Text("Scope: ${setup.parameters.scope.name}")
            Text(
                "The application generates and executes the Simpleperf command automatically from these parameters.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun SamplingRate.label(): String =
    when (this) {
        is SamplingRate.Frequency -> "$hertz Hz"
        is SamplingRate.Period -> "every $events events"
    }

private fun SamplingRate.valueText(): String =
    when (this) {
        is SamplingRate.Frequency -> hertz.toString()
        is SamplingRate.Period -> events.toString()
    }

private fun CaptureTarget?.orEmptyLabel(): String =
    when (this) {
        is CaptureTarget.App -> packageName
        is CaptureTarget.Process -> "$name (PID $pid)"
        is CaptureTarget.Thread -> "$name (TID $tid)"
        null -> "None"
    }

private const val MAX_EVENT_CHIPS = 6

private fun CaptureState.isActive(): Boolean =
    this is CaptureState.Preparing ||
        this is CaptureState.Recording ||
        this is CaptureState.Stopping ||
        this is CaptureState.Pulling

private fun CaptureState.sessionPath(): String? = (this as? CaptureState.SessionState)?.sessionDirectory?.toString()

private fun CaptureState.statusText(): String =
    when (this) {
        CaptureState.Idle -> "Ready to capture"
        is CaptureState.Preparing -> "Preparing simpleperf…"
        is CaptureState.Recording -> "Recording…"
        is CaptureState.Stopping -> "Stopping gracefully…"
        is CaptureState.Pulling -> "Pulling perf.data…"
        is CaptureState.Completed -> "Completed: ${perfData.fileName}"
        is CaptureState.Cancelled -> "Capture cancelled; logs were retained"
        is CaptureState.Failed -> "${error.code}: ${error.message}"
    }
