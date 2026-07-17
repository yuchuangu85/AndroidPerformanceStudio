@file:Suppress("TooManyFunctions")

package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.application.CaptureSetup
import com.androidperformancestudio.capture.CallGraphMode
import com.androidperformancestudio.capture.CaptureState
import com.androidperformancestudio.capture.EventScope
import com.androidperformancestudio.capture.SamplingParameters
import com.androidperformancestudio.capture.SamplingRate
import com.androidperformancestudio.capture.SamplingTemplate

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
internal fun CaptureConfigurationWorkspace(
    setup: CaptureSetup?,
    availableEvents: List<String>,
    style: MacOsDeviceTargetStyle,
    enabled: Boolean,
    onSelectTemplate: (SamplingTemplate) -> Unit,
    onUpdate: (SamplingParameters) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Capture Configuration",
            color = style.text,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (captureConfigurationLayout(maxWidth) == CaptureConfigurationLayout.HORIZONTAL) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    SamplingTemplatePanel(
                        setup = setup,
                        enabled = enabled,
                        onSelectTemplate = onSelectTemplate,
                        style = style,
                        modifier = Modifier.weight(TEMPLATE_PANEL_WEIGHT),
                    )
                    AdvancedCaptureParameters(
                        setup = setup,
                        availableEvents = availableEvents,
                        enabled = enabled,
                        onUpdate = onUpdate,
                        style = style,
                        modifier = Modifier.weight(PARAMETER_PANEL_WEIGHT),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SamplingTemplatePanel(setup, enabled, onSelectTemplate, style, Modifier.fillMaxWidth())
                    AdvancedCaptureParameters(setup, availableEvents, enabled, onUpdate, style, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun SamplingTemplatePanel(
    setup: CaptureSetup?,
    enabled: Boolean,
    onSelectTemplate: (SamplingTemplate) -> Unit,
    style: MacOsDeviceTargetStyle,
    modifier: Modifier,
) {
    MacOsPanel(modifier, style) {
        Text(
            "Sampling template",
            color = style.text,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        SamplingTemplate.entries.forEach { template ->
            TemplateChoice(
                template = template,
                selected = setup?.template == template,
                enabled = enabled,
                onClick = { onSelectTemplate(template) },
                style = style,
            )
        }
    }
}

@Composable
@Suppress(
    "FunctionName",
    "LongMethod",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "ComplexCondition",
    "ktlint:standard:function-naming",
)
private fun AdvancedCaptureParameters(
    setup: CaptureSetup?,
    availableEvents: List<String>,
    enabled: Boolean,
    onUpdate: (SamplingParameters) -> Unit,
    style: MacOsDeviceTargetStyle,
    modifier: Modifier,
) {
    if (setup == null) {
        EmptyParametersPanel(style, modifier)
        return
    }
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

    MacOsPanel(modifier.widthIn(max = 1200.dp), style) {
        Text(
            "Advanced parameters",
            color = style.text,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        MacOsTextField(
            label = "Event",
            value = event,
            enabled = enabled,
            onValueChange = {
                event = it
                commitNumericValues()
            },
            style = style,
            modifier = Modifier.fillMaxWidth(),
        )
        if (availableEvents.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                availableEvents.take(MAX_EVENT_CHIPS).forEach { candidate ->
                    MacOsChoiceChip(candidate, event == candidate, enabled, style) {
                        event = candidate
                        commitNumericValues()
                    }
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Rate mode", color = style.secondaryText, fontSize = 9.sp, lineHeight = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    MacOsChoiceChip("Frequency", !periodMode, enabled, style) {
                        rateValue.toIntOrNull()?.takeIf { it > 0 }?.let {
                            onUpdate(setup.parameters.copy(rate = SamplingRate.Frequency(it)))
                        }
                    }
                    MacOsChoiceChip("Period", periodMode, enabled, style) {
                        rateValue.toLongOrNull()?.takeIf { it > 0 }?.let {
                            onUpdate(setup.parameters.copy(rate = SamplingRate.Period(it)))
                        }
                    }
                }
            }
            MacOsTextField(
                label = if (periodMode) "Events per sample" else "Hz",
                value = rateValue,
                enabled = enabled,
                onValueChange = {
                    rateValue = it
                    commitNumericValues()
                },
                style = style,
                modifier = Modifier.weight(RATE_FIELD_WEIGHT),
            )
            MacOsTextField(
                label = "Duration seconds (blank = manual stop)",
                value = duration,
                enabled = enabled,
                onValueChange = {
                    duration = it
                    commitNumericValues()
                },
                style = style,
                modifier = Modifier.weight(DURATION_FIELD_WEIGHT),
            )
        }
        ParameterChoices("Call graph", CallGraphMode.entries, setup.parameters.callGraph, enabled, style) {
            onUpdate(setup.parameters.copy(callGraph = it))
        }
        ParameterChoices("Scope", EventScope.entries, setup.parameters.scope, enabled, style) {
            onUpdate(setup.parameters.copy(scope = it))
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun EmptyParametersPanel(
    style: MacOsDeviceTargetStyle,
    modifier: Modifier,
) {
    MacOsPanel(modifier, style) {
        Text(
            "Advanced parameters",
            color = style.text,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Select a target to configure sampling parameters.",
            color = style.secondaryText,
            fontSize = 10.sp,
            lineHeight = 13.sp,
        )
    }
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun MacOsTextField(
    label: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    style: MacOsDeviceTargetStyle,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = style.secondaryText, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(color = style.text, fontSize = 11.sp, lineHeight = 14.sp),
            cursorBrush = SolidColor(style.accent),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(MacOsDeviceTargetDimensions.selectorHeight)
                    .background(style.field, RoundedCornerShape(MacOsDeviceTargetDimensions.controlRadius))
                    .border(
                        MacOsDeviceTargetDimensions.hairline,
                        style.strongBorder,
                        RoundedCornerShape(MacOsDeviceTargetDimensions.controlRadius),
                    ).padding(horizontal = 8.dp, vertical = 7.dp),
        )
    }
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun <T : Enum<T>> ParameterChoices(
    label: String,
    values: List<T>,
    selected: T,
    enabled: Boolean,
    style: MacOsDeviceTargetStyle,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.widthIn(min = 66.dp),
            color = style.secondaryText,
            fontSize = 10.sp,
            lineHeight = 13.sp,
        )
        values.forEach { value ->
            MacOsChoiceChip(
                label = value.name.replace('_', ' '),
                selected = value == selected,
                enabled = enabled,
                style = style,
                onClick = { onSelect(value) },
            )
        }
    }
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun MacOsChoiceChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    style: MacOsDeviceTargetStyle,
    onClick: () -> Unit,
) {
    val background = if (selected) style.accent else style.field
    val content = if (selected) style.accentText else style.text
    Box(
        modifier =
            Modifier
                .height(26.dp)
                .background(background, RoundedCornerShape(5.dp))
                .border(
                    MacOsDeviceTargetDimensions.hairline,
                    if (selected) style.accent else style.strongBorder,
                    RoundedCornerShape(5.dp),
                ).clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = content.copy(alpha = if (enabled) 1f else DISABLED_CHIP_ALPHA),
            fontSize = 9.sp,
            lineHeight = 11.sp,
            maxLines = 1,
        )
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun TemplateChoice(
    template: SamplingTemplate,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    style: MacOsDeviceTargetStyle,
) {
    val border = if (selected) style.accent else style.border
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    if (selected) style.accent.copy(alpha = SELECTED_TEMPLATE_ALPHA) else style.field,
                    RoundedCornerShape(6.dp),
                ).border(MacOsDeviceTargetDimensions.hairline, border, RoundedCornerShape(6.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .height(8.dp)
                .widthIn(min = 8.dp, max = 8.dp)
                .background(if (selected) style.accent else style.border, RoundedCornerShape(4.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                template.displayName,
                color = style.text.copy(alpha = if (enabled) 1f else DISABLED_CHIP_ALPHA),
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
            Text(
                template.description,
                color = style.secondaryText.copy(alpha = if (enabled) 1f else DISABLED_CHIP_ALPHA),
                fontSize = 8.sp,
                lineHeight = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun MacOsPanel(
    modifier: Modifier,
    style: MacOsDeviceTargetStyle,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            modifier
                .background(style.panel, RoundedCornerShape(9.dp))
                .border(MacOsDeviceTargetDimensions.hairline, style.border, RoundedCornerShape(9.dp))
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

internal enum class CaptureConfigurationLayout {
    HORIZONTAL,
    STACKED,
}

internal fun captureConfigurationLayout(availableWidth: Dp): CaptureConfigurationLayout =
    if (availableWidth >= 900.dp) CaptureConfigurationLayout.HORIZONTAL else CaptureConfigurationLayout.STACKED

internal fun CaptureState.isCaptureActive(): Boolean =
    this is CaptureState.Preparing ||
        this is CaptureState.Recording ||
        this is CaptureState.Stopping ||
        this is CaptureState.Pulling

internal fun CaptureState.sessionPath(): String? = (this as? CaptureState.SessionState)?.sessionDirectory?.toString()

internal fun CaptureState.statusText(): String =
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

private fun SamplingRate.valueText(): String =
    when (this) {
        is SamplingRate.Frequency -> hertz.toString()
        is SamplingRate.Period -> events.toString()
    }

private const val MAX_EVENT_CHIPS = 5
private const val TEMPLATE_PANEL_WEIGHT = 0.34f
private const val PARAMETER_PANEL_WEIGHT = 0.66f
private const val RATE_FIELD_WEIGHT = 0.32f
private const val DURATION_FIELD_WEIGHT = 0.68f
private const val SELECTED_TEMPLATE_ALPHA = 0.11f
private const val DISABLED_CHIP_ALPHA = 0.46f
