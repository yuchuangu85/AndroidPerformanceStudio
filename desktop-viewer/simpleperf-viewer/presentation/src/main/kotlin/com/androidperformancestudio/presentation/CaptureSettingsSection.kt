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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.ui.DISABLED_CHIP_ALPHA
import com.androidperformancestudio.ui.DURATION_FIELD_WEIGHT
import com.androidperformancestudio.ui.MAX_EVENT_CHIPS
import com.androidperformancestudio.ui.RATE_FIELD_WEIGHT
import com.androidperformancestudio.ui.SELECTED_TEMPLATE_ALPHA
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.ViewerDimensions
import com.androidperformancestudio.ui.button.MacOSTextButton
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.radiobutton.MacOSChoiceChip
import com.androidperformancestudio.ui.viewerColors

enum class CaptureSettingsSection {
    SAMPLING_TEMPLATE,
    CAPTURE_CONFIGURATION,
    ADVANCED_PARAMETERS,
    FLAME_GRAPH,
    SIMPLEPERF_ENGINE,
    USER_GUIDE,
}

/** Complete Simpleperf settings surface that can be embedded in the unified desktop settings window. */
@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
fun SimpleperfSettingsContent(
    setup: CaptureSetup?,
    availableEvents: List<String>,
    enabled: Boolean,
    darkTheme: Boolean,
    flameTooltipMode: FlameTooltipMode,
    onFlameTooltipModeChange: (FlameTooltipMode) -> Unit,
    simpleperfEngine: SimpleperfEngine,
    onSimpleperfEngineChange: (SimpleperfEngine) -> Unit,
    onSelectTemplate: (SamplingTemplate) -> Unit,
    onUpdate: (SamplingParameters) -> Unit,
    onOpenUserGuide: (() -> Unit)?,
    initialSection: CaptureSettingsSection = CaptureSettingsSection.SAMPLING_TEMPLATE,
    modifier: Modifier = Modifier,
) {
    val locale = currentSimpleperfLanguage().locale
    val style = viewerColors(darkTheme)
    var section by remember(initialSection) { mutableStateOf(initialSection) }
    Row(
        modifier =
            modifier
                .background(style.workspace, RoundedCornerShape(8.dp))
                .border(ViewerDimensions.hairline, style.border, RoundedCornerShape(8.dp)),
    ) {
        SettingsNavigation(
            section = section,
            style = style,
            onSectionChange = { section = it },
            showUserGuide = onOpenUserGuide != null,
            locale = locale,
        )
        SettingsPanel(
            section = section,
            setup = setup,
            availableEvents = availableEvents,
            style = style,
            enabled = enabled,
            onSelectTemplate = onSelectTemplate,
            onUpdate = onUpdate,
            onDismiss = null,
            flameTooltipMode = flameTooltipMode,
            onFlameTooltipModeChange = onFlameTooltipModeChange,
            simpleperfEngine = simpleperfEngine,
            onSimpleperfEngineChange = onSimpleperfEngineChange,
            onOpenUserGuide = onOpenUserGuide,
            locale = locale,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Content for one Simpleperf settings section without an embedded navigation sidebar. */
@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
fun SimpleperfSettingsSectionContent(
    section: CaptureSettingsSection,
    setup: CaptureSetup?,
    availableEvents: List<String>,
    enabled: Boolean,
    darkTheme: Boolean,
    flameTooltipMode: FlameTooltipMode,
    onFlameTooltipModeChange: (FlameTooltipMode) -> Unit,
    simpleperfEngine: SimpleperfEngine,
    onSimpleperfEngineChange: (SimpleperfEngine) -> Unit,
    onSelectTemplate: (SamplingTemplate) -> Unit,
    onUpdate: (SamplingParameters) -> Unit,
    onOpenUserGuide: (() -> Unit)?,
    modifier: Modifier = Modifier,
    locale: java.util.Locale = java.util.Locale.ENGLISH,
) {
    SettingsPanel(
        section = section,
        setup = setup,
        availableEvents = availableEvents,
        style = viewerColors(darkTheme),
        enabled = enabled,
        onSelectTemplate = onSelectTemplate,
        onUpdate = onUpdate,
        onDismiss = null,
        flameTooltipMode = flameTooltipMode,
        onFlameTooltipModeChange = onFlameTooltipModeChange,
        simpleperfEngine = simpleperfEngine,
        onSimpleperfEngineChange = onSimpleperfEngineChange,
        onOpenUserGuide = onOpenUserGuide,
        locale = locale,
        modifier = modifier,
    )
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
internal fun CaptureSettingsDialog(
    section: CaptureSettingsSection,
    setup: CaptureSetup?,
    availableEvents: List<String>,
    style: ViewerColors,
    enabled: Boolean,
    onSectionChange: (CaptureSettingsSection) -> Unit,
    onSelectTemplate: (SamplingTemplate) -> Unit,
    onUpdate: (SamplingParameters) -> Unit,
    onDismiss: () -> Unit,
    flameTooltipMode: FlameTooltipMode = FlameTooltipMode.FOLLOW_MOUSE,
    onFlameTooltipModeChange: (FlameTooltipMode) -> Unit = {},
    simpleperfEngine: SimpleperfEngine = SimpleperfEngine.LOCAL,
    onSimpleperfEngineChange: (SimpleperfEngine) -> Unit = {},
    onOpenUserGuide: (() -> Unit)? = null,
) {
    val locale = currentSimpleperfLanguage().locale
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
                    .border(ViewerDimensions.hairline, style.border, RoundedCornerShape(12.dp)),
            ) {
                SettingsNavigation(section, style, onSectionChange, showUserGuide = onOpenUserGuide != null, locale = locale)
                SettingsPanel(
                    section = section,
                    setup = setup,
                    availableEvents = availableEvents,
                    style = style,
                    enabled = enabled,
                    onSelectTemplate = onSelectTemplate,
                    onUpdate = onUpdate,
                    onDismiss = onDismiss,
                    flameTooltipMode = flameTooltipMode,
                    onFlameTooltipModeChange = onFlameTooltipModeChange,
                    simpleperfEngine = simpleperfEngine,
                    onSimpleperfEngineChange = onSimpleperfEngineChange,
                    onOpenUserGuide = onOpenUserGuide,
                    locale = locale,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SettingsNavigation(
    section: CaptureSettingsSection,
    style: ViewerColors,
    onSectionChange: (CaptureSettingsSection) -> Unit,
    showUserGuide: Boolean,
    locale: java.util.Locale,
) {
    Column(
        Modifier
            .width(190.dp)
            .fillMaxHeight()
            .background(style.toolbar, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_target_settings, locale),
            color = style.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(localizedStringResource(SimpleperfViewerRes.sp_target_application, locale), color = style.secondaryText, fontSize = 10.sp)
        Spacer(Modifier.height(8.dp))
        CaptureSettingsSection.entries.filter { item -> item != CaptureSettingsSection.USER_GUIDE || showUserGuide }.forEach { item ->
            val selected = item == section
            val label = item.label(locale)
            val navigationDescription =
                localizedStringResource(SimpleperfViewerRes.sp_settings_capture_settings_value_format, locale, label)
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
private fun SettingsPanel(
    section: CaptureSettingsSection,
    setup: CaptureSetup?,
    availableEvents: List<String>,
    style: ViewerColors,
    enabled: Boolean,
    onSelectTemplate: (SamplingTemplate) -> Unit,
    onUpdate: (SamplingParameters) -> Unit,
    onDismiss: (() -> Unit)?,
    flameTooltipMode: FlameTooltipMode,
    onFlameTooltipModeChange: (FlameTooltipMode) -> Unit,
    simpleperfEngine: SimpleperfEngine,
    onSimpleperfEngineChange: (SimpleperfEngine) -> Unit,
    onOpenUserGuide: (() -> Unit)?,
    locale: java.util.Locale,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxHeight().padding(22.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    section.title(locale),
                    color = style.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(section.subtitle(locale), color = style.secondaryText, fontSize = 10.sp)
            }
            onDismiss?.let {
                MacOSTextButton(
                    localizedStringResource(SimpleperfViewerRes.sp_target_done, locale),
                    it,
                    style,
                    primary = true,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (section) {
                CaptureSettingsSection.SAMPLING_TEMPLATE ->
                    SamplingTemplatePanel(setup, enabled, onSelectTemplate, style, locale, Modifier.fillMaxWidth())
                CaptureSettingsSection.CAPTURE_CONFIGURATION ->
                    CaptureConfigurationPanel(setup, availableEvents, enabled, onUpdate, style, locale, Modifier.fillMaxWidth())
                CaptureSettingsSection.ADVANCED_PARAMETERS ->
                    AdvancedCaptureParameters(setup, enabled, onUpdate, style, locale, Modifier.fillMaxWidth())
                CaptureSettingsSection.FLAME_GRAPH ->
                    FlameGraphSettingsPanel(flameTooltipMode, onFlameTooltipModeChange, style, locale)
                CaptureSettingsSection.SIMPLEPERF_ENGINE ->
                    SimpleperfEngineSettingsPanel(simpleperfEngine, onSimpleperfEngineChange, style, locale)
                CaptureSettingsSection.USER_GUIDE ->
                    onOpenUserGuide?.let { UserGuideSettingsPanel(it, style, locale) }
            }
        }
    }
}

private fun CaptureSettingsSection.label(locale: java.util.Locale): String =
    when (this) {
        CaptureSettingsSection.SAMPLING_TEMPLATE -> localizedStringResource(SimpleperfViewerRes.sp_settings_sampling_template, locale)
        CaptureSettingsSection.CAPTURE_CONFIGURATION ->
            localizedStringResource(
                SimpleperfViewerRes.sp_settings_capture_configuration_section,
                locale,
            )
        CaptureSettingsSection.ADVANCED_PARAMETERS -> localizedStringResource(SimpleperfViewerRes.sp_settings_advanced_parameters, locale)
        CaptureSettingsSection.FLAME_GRAPH -> localizedStringResource(SimpleperfViewerRes.sp_flame_flame_graph, locale)
        CaptureSettingsSection.SIMPLEPERF_ENGINE -> localizedStringResource(SimpleperfViewerRes.sp_settings_simpleperf_engine, locale)
        CaptureSettingsSection.USER_GUIDE -> localizedStringResource(SimpleperfViewerRes.sp_settings_user_guide, locale)
    }

private fun CaptureSettingsSection.title(locale: java.util.Locale): String = label(locale)

private fun CaptureSettingsSection.subtitle(locale: java.util.Locale): String =
    when (this) {
        CaptureSettingsSection.SAMPLING_TEMPLATE ->
            localizedStringResource(
                SimpleperfViewerRes.sp_settings_sampling_template_description,
                locale,
            )
        CaptureSettingsSection.CAPTURE_CONFIGURATION ->
            localizedStringResource(
                SimpleperfViewerRes.sp_settings_capture_configuration_description,
                locale,
            )
        CaptureSettingsSection.ADVANCED_PARAMETERS ->
            localizedStringResource(
                SimpleperfViewerRes.sp_settings_advanced_parameters_description,
                locale,
            )
        CaptureSettingsSection.FLAME_GRAPH -> localizedStringResource(SimpleperfViewerRes.sp_settings_frame_information_description, locale)
        CaptureSettingsSection.SIMPLEPERF_ENGINE ->
            localizedStringResource(
                SimpleperfViewerRes.sp_settings_analysis_engine_description,
                locale,
            )
        CaptureSettingsSection.USER_GUIDE -> localizedStringResource(SimpleperfViewerRes.sp_settings_user_guide_description, locale)
    }

@Composable
private fun UserGuideSettingsPanel(
    onOpenUserGuide: () -> Unit,
    style: ViewerColors,
    locale: java.util.Locale,
) {
    MacOsPanel(Modifier.fillMaxWidth(), style) {
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_settings_user_guide, locale),
            color = style.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_settings_user_guide_description, locale),
            color = style.secondaryText,
            fontSize = 10.sp,
        )
        MacOSTextButton(
            localizedStringResource(SimpleperfViewerRes.sp_settings_open_user_guide_browser, locale),
            onOpenUserGuide,
            style,
            primary = true,
        )
    }
}

@Composable
private fun FlameGraphSettingsPanel(
    selected: FlameTooltipMode,
    onSelect: (FlameTooltipMode) -> Unit,
    style: ViewerColors,
    locale: java.util.Locale,
) {
    MacOsPanel(Modifier.fillMaxWidth(), style) {
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_settings_frame_information_box, locale),
            color = style.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_settings_frame_information_behavior_description, locale),
            color = style.secondaryText,
            fontSize = 10.sp,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MacOSChoiceChip(
                localizedStringResource(SimpleperfViewerRes.sp_settings_fixed, locale),
                selected == FlameTooltipMode.FIXED,
                true,
                style,
            ) {
                onSelect(FlameTooltipMode.FIXED)
            }
            MacOSChoiceChip(
                localizedStringResource(SimpleperfViewerRes.sp_settings_follow_mouse, locale),
                selected == FlameTooltipMode.FOLLOW_MOUSE,
                true,
                style,
            ) {
                onSelect(FlameTooltipMode.FOLLOW_MOUSE)
            }
        }
    }
}

@Composable
private fun SimpleperfEngineSettingsPanel(
    selected: SimpleperfEngine,
    onSelect: (SimpleperfEngine) -> Unit,
    style: ViewerColors,
    locale: java.util.Locale,
) {
    MacOsPanel(Modifier.fillMaxWidth(), style) {
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_settings_analysis_engine_options_description, locale),
            color = style.secondaryText,
            style = MaterialTheme.typography.bodySmall,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MacOSChoiceChip(
                localizedStringResource(SimpleperfViewerRes.sp_settings_new_engine, locale),
                selected == SimpleperfEngine.LOCAL,
                true,
                style,
            ) {
                onSelect(SimpleperfEngine.LOCAL)
            }
            MacOSChoiceChip(
                localizedStringResource(SimpleperfViewerRes.sp_settings_firefox_profiler_local_engine, locale),
                selected == SimpleperfEngine.FIREFOX_PROFILER_LOCAL,
                true,
                style,
            ) {
                onSelect(SimpleperfEngine.FIREFOX_PROFILER_LOCAL)
            }
            MacOSChoiceChip(
                localizedStringResource(SimpleperfViewerRes.sp_settings_firefox_profiler, locale),
                selected == SimpleperfEngine.FIREFOX_PROFILER,
                true,
                style,
            ) {
                onSelect(SimpleperfEngine.FIREFOX_PROFILER)
            }
        }
    }
}

@Composable
private fun SamplingTemplatePanel(
    setup: CaptureSetup?,
    enabled: Boolean,
    onSelectTemplate: (SamplingTemplate) -> Unit,
    style: ViewerColors,
    locale: java.util.Locale,
    modifier: Modifier,
) {
    MacOsPanel(modifier, style) {
        SamplingTemplate.entries.forEach { template ->
            TemplateChoice(template, setup?.template == template, enabled, { onSelectTemplate(template) }, style, locale)
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
    style: ViewerColors,
    locale: java.util.Locale,
    modifier: Modifier,
) {
    if (setup == null) {
        EmptyParametersPanel(style, modifier, SimpleperfViewerRes.sp_settings_capture_configuration_section, locale)
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
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_capture_event_rate, locale),
            color = style.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        MacOsTextField(localizedStringResource(SimpleperfViewerRes.sp_capture_event, locale), event, enabled, {
            event = it
            commitValues()
        }, style, Modifier.fillMaxWidth())
        if (availableEvents.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                availableEvents.take(MAX_EVENT_CHIPS).forEach { candidate ->
                    MacOSChoiceChip(candidate, event == candidate, enabled, style) {
                        event = candidate
                        commitValues()
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    localizedStringResource(SimpleperfViewerRes.sp_capture_rate_mode, locale),
                    color = style.secondaryText,
                    fontSize = 9.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    MacOSChoiceChip(
                        localizedStringResource(SimpleperfViewerRes.sp_capture_frequency, locale),
                        !periodMode,
                        enabled,
                        style,
                    ) {
                        rateValue.toIntOrNull()?.takeIf { it > 0 }?.let {
                            onUpdate(
                                setup.parameters.copy(rate = SamplingRate.Frequency(it)),
                            )
                        }
                    }
                    MacOSChoiceChip(localizedStringResource(SimpleperfViewerRes.sp_capture_period, locale), periodMode, enabled, style) {
                        rateValue.toLongOrNull()?.takeIf { it > 0 }?.let { onUpdate(setup.parameters.copy(rate = SamplingRate.Period(it))) }
                    }
                }
            }
            MacOsTextField(
                if (periodMode) {
                    localizedStringResource(
                        SimpleperfViewerRes.sp_capture_events_per_sample,
                        locale,
                    )
                } else {
                    "Hz"
                },
                rateValue,
                enabled,
                {
                    rateValue = it
                    commitValues()
                },
                style,
                Modifier.weight(RATE_FIELD_WEIGHT),
            )
            MacOsTextField(localizedStringResource(SimpleperfViewerRes.sp_capture_duration_seconds_hint, locale), duration, enabled, {
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
    style: ViewerColors,
    locale: java.util.Locale,
    modifier: Modifier,
) {
    if (setup == null) {
        EmptyParametersPanel(style, modifier, SimpleperfViewerRes.sp_settings_advanced_parameters, locale)
        return
    }
    MacOsPanel(modifier, style) {
        ParameterChoices(
            SimpleperfViewerRes.sp_capture_call_graph,
            CallGraphMode.entries,
            setup.parameters.callGraph,
            enabled,
            style,
            locale,
        ) {
            onUpdate(setup.parameters.copy(callGraph = it))
        }
        ParameterChoices(SimpleperfViewerRes.sp_capture_scope, EventScope.entries, setup.parameters.scope, enabled, style, locale) {
            onUpdate(setup.parameters.copy(scope = it))
        }
    }
}

@Composable
private fun EmptyParametersPanel(
    style: ViewerColors,
    modifier: Modifier,
    title: org.jetbrains.compose.resources.StringResource,
    locale: java.util.Locale,
) {
    MacOsPanel(modifier, style) {
        Text(localizedStringResource(title, locale), color = style.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_capture_sampling_target_required_hint, locale),
            color = style.secondaryText,
            fontSize = 10.sp,
        )
    }
}

@Composable
internal fun MacOsTextField(
    label: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    style: ViewerColors,
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
                    .height(ViewerDimensions.selectorHeight)
                    .background(style.field, RoundedCornerShape(ViewerDimensions.controlRadius))
                    .border(
                        ViewerDimensions.hairline,
                        style.strongBorder,
                        RoundedCornerShape(ViewerDimensions.controlRadius),
                    ).semantics { contentDescription = label }
                    .padding(horizontal = 8.dp, vertical = 7.dp),
        )
    }
}

@Composable
internal fun MacOsInlineTextField(
    label: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    style: ViewerColors,
    fieldWidth: Dp,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = style.secondaryText, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(color = style.text, fontSize = 11.sp, lineHeight = 14.sp),
            cursorBrush = SolidColor(style.accent),
            modifier =
                Modifier
                    .requiredWidth(fieldWidth)
                    .height(20.dp)
                    .background(style.field, RoundedCornerShape(ViewerDimensions.controlRadius))
                    .border(
                        ViewerDimensions.hairline,
                        style.strongBorder,
                        RoundedCornerShape(ViewerDimensions.controlRadius),
                    ).semantics { contentDescription = label }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun <T : Enum<T>> ParameterChoices(
    label: org.jetbrains.compose.resources.StringResource,
    values: List<T>,
    selected: T,
    enabled: Boolean,
    style: ViewerColors,
    locale: java.util.Locale,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            localizedStringResource(label, locale),
            modifier = Modifier.widthIn(min = 66.dp),
            color = style.secondaryText,
            fontSize = 10.sp,
        )
        values.forEach { value ->
            val valueLabel =
                when (value) {
                    is CallGraphMode -> value.localizedLabel(locale)
                    is EventScope -> value.localizedLabel(locale)
                    else -> value.name.replace('_', ' ')
                }
            MacOSChoiceChip(valueLabel, value == selected, enabled, style) { onSelect(value) }
        }
    }
}

@Composable
private fun TemplateChoice(
    template: SamplingTemplate,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    style: ViewerColors,
    locale: java.util.Locale,
) {
    val border = if (selected) style.accent else style.border
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) style.accent.copy(alpha = SELECTED_TEMPLATE_ALPHA) else style.field,
                RoundedCornerShape(6.dp),
            ).border(
                ViewerDimensions.hairline,
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
                template.localizedTemplateName(locale),
                color = style.text.copy(alpha = if (enabled) 1f else DISABLED_CHIP_ALPHA),
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
            Text(
                template.localizedTemplateDescription(locale),
                color = style.secondaryText.copy(alpha = if (enabled) 1f else DISABLED_CHIP_ALPHA),
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun MacOsPanel(
    modifier: Modifier,
    style: ViewerColors,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .background(
                style.panel,
                RoundedCornerShape(9.dp),
            ).border(ViewerDimensions.hairline, style.border, RoundedCornerShape(9.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

internal fun CaptureState.isCaptureActive(): Boolean =
    this is CaptureState.Preparing || this is CaptureState.Recording || this is CaptureState.Stopping || this is CaptureState.Pulling

internal fun CaptureState.sessionPath(): String? = (this as? CaptureState.SessionState)?.sessionDirectory?.toString()

internal fun CaptureState.statusText(language: UiLanguage = UiLanguage.ENGLISH): String =
    when (this) {
        CaptureState.Idle -> localizedStringResource(SimpleperfViewerRes.sp_capture_ready_capture, language)
        is CaptureState.Preparing -> localizedStringResource(SimpleperfViewerRes.sp_capture_preparing_simpleperf, language)
        is CaptureState.Recording -> localizedStringResource(SimpleperfViewerRes.sp_capture_recording, language)
        is CaptureState.Stopping -> localizedStringResource(SimpleperfViewerRes.sp_capture_stopping_gracefully, language)
        is CaptureState.Pulling -> localizedStringResource(SimpleperfViewerRes.sp_capture_pulling_perf_data, language)
        is CaptureState.Completed ->
            localizedStringResource(
                SimpleperfViewerRes.sp_capture_completed_value_format,
                language,
                perfData.fileName,
            )
        is CaptureState.Cancelled -> localizedStringResource(SimpleperfViewerRes.sp_capture_cancelled_logs_retained_status, language)
        is CaptureState.Failed -> "${error.code}: ${error.message}"
    }

private fun SamplingRate.valueText(): String =
    when (this) {
        is SamplingRate.Frequency -> hertz.toString()
        is SamplingRate.Period -> events.toString()
    }

private fun CallGraphMode.localizedLabel(locale: java.util.Locale): String =
    when (this) {
        CallGraphMode.DWARF -> localizedStringResource(SimpleperfViewerRes.sp_capture_dwarf, locale)
        CallGraphMode.FRAME_POINTER -> localizedStringResource(SimpleperfViewerRes.sp_capture_frame_pointer, locale)
        CallGraphMode.NONE -> localizedStringResource(SimpleperfViewerRes.sp_capture_none, locale)
    }

private fun EventScope.localizedLabel(locale: java.util.Locale): String =
    when (this) {
        EventScope.USER -> localizedStringResource(SimpleperfViewerRes.sp_capture_user, locale)
        EventScope.KERNEL -> localizedStringResource(SimpleperfViewerRes.sp_capture_kernel, locale)
        EventScope.BOTH -> localizedStringResource(SimpleperfViewerRes.sp_capture_both, locale)
    }

private fun SamplingTemplate.localizedTemplateName(locale: java.util.Locale): String =
    localizedStringResource(
        when (this) {
            SamplingTemplate.APP_CPU_BASIC -> SimpleperfViewerRes.sp_capture_app_cpu_basic
            SamplingTemplate.UI_THREAD_FOCUS -> SimpleperfViewerRes.sp_capture_ui_thread_focus
            SamplingTemplate.NATIVE_HOTSPOT -> SimpleperfViewerRes.sp_capture_native_hotspot
            SamplingTemplate.LOW_OVERHEAD -> SimpleperfViewerRes.sp_capture_low_overhead
            SamplingTemplate.SYSTEM_PROCESS -> SimpleperfViewerRes.sp_capture_system_process
        },
        locale,
    )

private fun SamplingTemplate.localizedTemplateDescription(locale: java.util.Locale): String =
    localizedStringResource(
        when (this) {
            SamplingTemplate.APP_CPU_BASIC -> SimpleperfViewerRes.sp_capture_app_cpu_basic_description
            SamplingTemplate.UI_THREAD_FOCUS -> SimpleperfViewerRes.sp_capture_ui_thread_focus_description
            SamplingTemplate.NATIVE_HOTSPOT -> SimpleperfViewerRes.sp_capture_native_hotspot_description
            SamplingTemplate.LOW_OVERHEAD -> SimpleperfViewerRes.sp_capture_low_overhead_description
            SamplingTemplate.SYSTEM_PROCESS -> SimpleperfViewerRes.sp_capture_system_process_description
        },
        locale,
    )
