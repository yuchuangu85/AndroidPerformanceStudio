@file:Suppress(
    "TooManyFunctions",
    "FunctionName",
    "LongParameterList",
    "MaxLineLength",
    "MagicNumber",
    "ktlint:standard:function-naming",
)

package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.androidperformancestudio.application.CaptureSetup
import com.androidperformancestudio.capture.CallGraphMode
import com.androidperformancestudio.capture.CaptureState
import com.androidperformancestudio.capture.EventScope
import com.androidperformancestudio.capture.SamplingParameters
import com.androidperformancestudio.capture.SamplingRate
import com.androidperformancestudio.capture.SamplingTemplate

enum class CaptureSettingsSection {
    SAMPLING_TEMPLATE,
    CAPTURE_CONFIGURATION,
    ADVANCED_PARAMETERS,
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
internal fun CaptureSettingsDialog(
    section: CaptureSettingsSection,
    setup: CaptureSetup?,
    availableEvents: List<String>,
    style: MacOsDeviceTargetStyle,
    enabled: Boolean,
    onSectionChange: (CaptureSettingsSection) -> Unit,
    onSelectTemplate: (SamplingTemplate) -> Unit,
    onUpdate: (SamplingParameters) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(34.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .widthIn(max = 1080.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(0.84f)
                    .background(style.workspace, RoundedCornerShape(12.dp))
                    .border(MacOsDeviceTargetDimensions.hairline, style.border, RoundedCornerShape(12.dp)),
            ) {
                SettingsNavigation(section, style, onSectionChange)
                SettingsPanel(
                    section = section,
                    setup = setup,
                    availableEvents = availableEvents,
                    style = style,
                    enabled = enabled,
                    onSelectTemplate = onSelectTemplate,
                    onUpdate = onUpdate,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun SettingsNavigation(
    section: CaptureSettingsSection,
    style: MacOsDeviceTargetStyle,
    onSectionChange: (CaptureSettingsSection) -> Unit,
) {
    Column(
        Modifier
            .width(190.dp)
            .fillMaxHeight()
            .background(style.toolbar, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("Settings", color = style.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("Capture setup", color = style.secondaryText, fontSize = 10.sp)
        Spacer(Modifier.height(8.dp))
        CaptureSettingsSection.entries.forEach { item ->
            val selected = item == section
            val label = item.label()
            val navigationDescription = localizedSimpleperfText("Capture settings: $label")
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (selected) style.accent.copy(alpha = 0.16f) else style.toolbar,
                        RoundedCornerShape(6.dp),
                    ).clickable(onClick = { onSectionChange(item) })
                    .semantics {
                        contentDescription = navigationDescription
                        this.selected = selected
                    }.padding(horizontal = 9.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    color = if (selected) style.accent else style.text,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun RowScope.SettingsPanel(
    section: CaptureSettingsSection,
    setup: CaptureSetup?,
    availableEvents: List<String>,
    style: MacOsDeviceTargetStyle,
    enabled: Boolean,
    onSelectTemplate: (SamplingTemplate) -> Unit,
    onUpdate: (SamplingParameters) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.weight(1f).fillMaxHeight().padding(22.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(section.title(), color = style.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(section.subtitle(), color = style.secondaryText, fontSize = 10.sp)
            }
            MacOsButton("Done", onDismiss, style, primary = true)
        }
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (section) {
                CaptureSettingsSection.SAMPLING_TEMPLATE ->
                    SamplingTemplatePanel(setup, enabled, onSelectTemplate, style, Modifier.fillMaxWidth())
                CaptureSettingsSection.CAPTURE_CONFIGURATION ->
                    CaptureConfigurationPanel(setup, availableEvents, enabled, onUpdate, style, Modifier.fillMaxWidth())
                CaptureSettingsSection.ADVANCED_PARAMETERS ->
                    AdvancedCaptureParameters(setup, enabled, onUpdate, style, Modifier.fillMaxWidth())
            }
        }
    }
}

private fun CaptureSettingsSection.label(): String =
    when (this) {
        CaptureSettingsSection.SAMPLING_TEMPLATE -> "Sampling template"
        CaptureSettingsSection.CAPTURE_CONFIGURATION -> "Capture configuration"
        CaptureSettingsSection.ADVANCED_PARAMETERS -> "Advanced parameters"
    }

private fun CaptureSettingsSection.title(): String =
    when (this) {
        CaptureSettingsSection.SAMPLING_TEMPLATE -> "Sampling template"
        CaptureSettingsSection.CAPTURE_CONFIGURATION -> "Capture configuration"
        CaptureSettingsSection.ADVANCED_PARAMETERS -> "Advanced parameters"
    }

private fun CaptureSettingsSection.subtitle(): String =
    when (this) {
        CaptureSettingsSection.SAMPLING_TEMPLATE -> "Choose a starting point for Simpleperf capture."
        CaptureSettingsSection.CAPTURE_CONFIGURATION -> "Configure event, rate, and duration."
        CaptureSettingsSection.ADVANCED_PARAMETERS -> "Tune call graph collection and event scope."
    }

@Composable
private fun SamplingTemplatePanel(
    setup: CaptureSetup?,
    enabled: Boolean,
    onSelectTemplate: (SamplingTemplate) -> Unit,
    style: MacOsDeviceTargetStyle,
    modifier: Modifier,
) {
    MacOsPanel(modifier, style) {
        Text("Sampling template", color = style.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        SamplingTemplate.entries.forEach { template ->
            TemplateChoice(template, setup?.template == template, enabled, { onSelectTemplate(template) }, style)
        }
    }
}

@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList", "CyclomaticComplexMethod", "ComplexCondition")
private fun CaptureConfigurationPanel(
    setup: CaptureSetup?,
    availableEvents: List<String>,
    enabled: Boolean,
    onUpdate: (SamplingParameters) -> Unit,
    style: MacOsDeviceTargetStyle,
    modifier: Modifier,
) {
    if (setup == null) {
        EmptyParametersPanel(style, modifier, "Capture configuration")
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

    fun commitValues() {
        val numericRate = rateValue.toLongOrNull()?.takeIf { it > 0 }
        val parsedRate =
            when {
                numericRate == null -> null
                periodMode -> SamplingRate.Period(numericRate)
                numericRate <= Int.MAX_VALUE -> SamplingRate.Frequency(numericRate.toInt())
                else -> null
            }
        val durationSeconds = duration.toDoubleOrNull()?.takeIf { it > 0 }
        if (parsedRate != null && (duration.isBlank() || durationSeconds != null) && event.isNotBlank() && event.none(Char::isWhitespace)) {
            onUpdate(setup.parameters.copy(event = event, rate = parsedRate, durationSeconds = durationSeconds))
        }
    }

    MacOsPanel(modifier, style) {
        Text("Event and rate", color = style.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        MacOsTextField("Event", event, enabled, {
            event = it
            commitValues()
        }, style, Modifier.fillMaxWidth())
        if (availableEvents.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                availableEvents.take(MAX_EVENT_CHIPS).forEach { candidate ->
                    MacOsChoiceChip(candidate, event == candidate, enabled, style) {
                        event = candidate
                        commitValues()
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Rate mode", color = style.secondaryText, fontSize = 9.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    MacOsChoiceChip("Frequency", !periodMode, enabled, style) {
                        rateValue.toIntOrNull()?.takeIf { it > 0 }?.let {
                            onUpdate(
                                setup.parameters.copy(rate = SamplingRate.Frequency(it)),
                            )
                        }
                    }
                    MacOsChoiceChip("Period", periodMode, enabled, style) {
                        rateValue.toLongOrNull()?.takeIf { it > 0 }?.let { onUpdate(setup.parameters.copy(rate = SamplingRate.Period(it))) }
                    }
                }
            }
            MacOsTextField(if (periodMode) "Events per sample" else "Hz", rateValue, enabled, {
                rateValue = it
                commitValues()
            }, style, Modifier.weight(RATE_FIELD_WEIGHT))
            MacOsTextField("Duration seconds (blank = manual stop)", duration, enabled, {
                duration = it
                commitValues()
            }, style, Modifier.weight(DURATION_FIELD_WEIGHT))
        }
    }
}

@Composable
private fun AdvancedCaptureParameters(
    setup: CaptureSetup?,
    enabled: Boolean,
    onUpdate: (SamplingParameters) -> Unit,
    style: MacOsDeviceTargetStyle,
    modifier: Modifier,
) {
    if (setup == null) {
        EmptyParametersPanel(style, modifier, "Advanced parameters")
        return
    }
    MacOsPanel(modifier, style) {
        Text("Advanced parameters", color = style.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        ParameterChoices("Call graph", CallGraphMode.entries, setup.parameters.callGraph, enabled, style) {
            onUpdate(setup.parameters.copy(callGraph = it))
        }
        ParameterChoices("Scope", EventScope.entries, setup.parameters.scope, enabled, style) {
            onUpdate(setup.parameters.copy(scope = it))
        }
    }
}

@Composable
private fun EmptyParametersPanel(
    style: MacOsDeviceTargetStyle,
    modifier: Modifier,
    title: String,
) {
    MacOsPanel(modifier, style) {
        Text(title, color = style.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text("Select a target to configure sampling parameters.", color = style.secondaryText, fontSize = 10.sp)
    }
}

@Composable
private fun MacOsTextField(
    label: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    style: MacOsDeviceTargetStyle,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = style.secondaryText, fontSize = 9.sp, maxLines = 1)
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
private fun <T : Enum<T>> ParameterChoices(
    label: String,
    values: List<T>,
    selected: T,
    enabled: Boolean,
    style: MacOsDeviceTargetStyle,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.widthIn(min = 66.dp), color = style.secondaryText, fontSize = 10.sp)
        values.forEach { value -> MacOsChoiceChip(value.name.replace('_', ' '), value == selected, enabled, style) { onSelect(value) } }
    }
}

@Composable
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
        Modifier
            .height(
                26.dp,
            ).background(
                background,
                RoundedCornerShape(5.dp),
            ).border(
                MacOsDeviceTargetDimensions.hairline,
                if (selected) style.accent else style.strongBorder,
                RoundedCornerShape(5.dp),
            ).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = content.copy(alpha = if (enabled) 1f else DISABLED_CHIP_ALPHA), fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun TemplateChoice(
    template: SamplingTemplate,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    style: MacOsDeviceTargetStyle,
) {
    val border = if (selected) style.accent else style.border
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) style.accent.copy(alpha = SELECTED_TEMPLATE_ALPHA) else style.field,
                RoundedCornerShape(6.dp),
            ).border(
                MacOsDeviceTargetDimensions.hairline,
                border,
                RoundedCornerShape(6.dp),
            ).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.height(8.dp).width(8.dp).background(if (selected) style.accent else style.border, RoundedCornerShape(4.dp)))
        Column(Modifier.weight(1f)) {
            Text(
                template.displayName,
                color = style.text.copy(alpha = if (enabled) 1f else DISABLED_CHIP_ALPHA),
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
            Text(
                template.description,
                color = style.secondaryText.copy(alpha = if (enabled) 1f else DISABLED_CHIP_ALPHA),
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MacOsPanel(
    modifier: Modifier,
    style: MacOsDeviceTargetStyle,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .background(
                style.panel,
                RoundedCornerShape(9.dp),
            ).border(MacOsDeviceTargetDimensions.hairline, style.border, RoundedCornerShape(9.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

internal enum class CaptureConfigurationLayout { HORIZONTAL, STACKED }

internal fun captureConfigurationLayout(availableWidth: Dp): CaptureConfigurationLayout =
    if (availableWidth >=
        900.dp
    ) {
        CaptureConfigurationLayout.HORIZONTAL
    } else {
        CaptureConfigurationLayout.STACKED
    }

internal fun CaptureState.isCaptureActive(): Boolean =
    this is CaptureState.Preparing || this is CaptureState.Recording || this is CaptureState.Stopping || this is CaptureState.Pulling

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
private const val RATE_FIELD_WEIGHT = 0.32f
private const val DURATION_FIELD_WEIGHT = 0.68f
private const val SELECTED_TEMPLATE_ALPHA = 0.11f
private const val DISABLED_CHIP_ALPHA = 0.46f
