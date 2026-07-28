package dev.agentperf.desktop

import org.jetbrains.compose.resources.stringResource

import dev.agentperf.desktop_app.generated.resources.Res
import dev.agentperf.desktop_app.generated.resources.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.androidperformancestudio.desktop.SimpleperfCaptureSettingsContext
import com.androidperformancestudio.desktop.SimpleperfUiSettings
import com.androidperformancestudio.presentation.CaptureSettingsSection
import com.androidperformancestudio.presentation.SimpleperfSettingsSectionContent

public enum class SettingsPage {
    GENERAL,
    LAYOUT_INSPECTOR,
    SIMPLEPERF,
}

@Composable
internal fun UnifiedSettingsDialog(
    selectedPage: SettingsPage,
    applicationSettings: ApplicationUiSettings,
    simpleperfSettings: SimpleperfUiSettings,
    simpleperfCaptureSettingsContext: SimpleperfCaptureSettingsContext?,
    simpleperfInitialSection: CaptureSettingsSection,
    darkTheme: Boolean,
    chinese: Boolean,
    onPageSelected: (SettingsPage) -> Unit,
    onApplicationSettingsChanged: (ApplicationUiSettings) -> Unit,
    onSimpleperfSettingsChanged: (SimpleperfUiSettings) -> Unit,
    onLayoutInspectorSettingsChanged: () -> Unit,
    onOpenUserGuide: () -> Unit,
    persistenceErrorPage: SettingsPage?,
    onDismiss: () -> Unit,
) {
    var simpleperfExpanded by remember {
        mutableStateOf(selectedPage == SettingsPage.SIMPLEPERF)
    }
    var activeSimpleperfSection by remember(simpleperfInitialSection) {
        mutableStateOf(simpleperfInitialSection)
    }
    LaunchedEffect(selectedPage) {
        if (selectedPage == SettingsPage.SIMPLEPERF) {
            simpleperfExpanded = true
        }
    }
    DialogWindow(
        onCloseRequest = onDismiss,
        title = stringResource(Res.string.settings),
        state =
            rememberDialogState(
                width = UNIFIED_SETTINGS_WIDTH_DP.dp,
                height = UNIFIED_SETTINGS_HEIGHT_DP.dp,
            ),
        resizable = true,
    ) {
        LaunchedEffect(selectedPage) {
            window.toFront()
            window.requestFocus()
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(Modifier.fillMaxSize()) {
                SettingsHeader(chinese, onDismiss)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(Modifier.weight(1f)) {
                    SettingsSidebar(
                        selectedPage = selectedPage,
                        selectedSimpleperfSection = activeSimpleperfSection,
                        simpleperfExpanded = simpleperfExpanded,
                        chinese = chinese,
                        onPageSelected = onPageSelected,
                        onSimpleperfExpandedChange = { simpleperfExpanded = it },
                        onSimpleperfSectionSelected = { section ->
                            activeSimpleperfSection = section
                            simpleperfExpanded = true
                            onPageSelected(SettingsPage.SIMPLEPERF)
                        },
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outline)
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(16.dp),
                    ) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (persistenceErrorPage != null) {
                                Text(
                                    stringResource(Res.string.settings_could_not_be_saved_the_current_session_still_uses, persistenceErrorPage.label(chinese)),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            when (selectedPage) {
                                SettingsPage.GENERAL ->
                                    GeneralSettingsContent(
                                        settings = applicationSettings,
                                        chinese = chinese,
                                        onSettingsChanged = onApplicationSettingsChanged,
                                        modifier = Modifier.weight(1f),
                                    )
                                SettingsPage.LAYOUT_INSPECTOR ->
                                    LayoutInspectorSettingsContent(
                                        chinese = chinese,
                                        onSettingsChanged = onLayoutInspectorSettingsChanged,
                                        modifier = Modifier.weight(1f),
                                    )
                                SettingsPage.SIMPLEPERF ->
                                    CompleteSimpleperfSettingsContent(
                                        settings = simpleperfSettings,
                                        context = simpleperfCaptureSettingsContext,
                                        section = activeSimpleperfSection,
                                        darkTheme = darkTheme,
                                        chinese = chinese,
                                        onSettingsChanged = onSimpleperfSettingsChanged,
                                        onOpenUserGuide = onOpenUserGuide,
                                        modifier = Modifier.weight(1f),
                                    )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHeader(chinese: Boolean, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.settings),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onDismiss) { Text(stringResource(Res.string.done)) }
    }
}

@Composable
private fun SettingsSidebar(
    selectedPage: SettingsPage,
    selectedSimpleperfSection: CaptureSettingsSection,
    simpleperfExpanded: Boolean,
    chinese: Boolean,
    onPageSelected: (SettingsPage) -> Unit,
    onSimpleperfExpandedChange: (Boolean) -> Unit,
    onSimpleperfSectionSelected: (CaptureSettingsSection) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .width(UNIFIED_SETTINGS_SIDEBAR_WIDTH_DP.dp)
                .fillMaxHeight()
                .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(SettingsPage.GENERAL, SettingsPage.LAYOUT_INSPECTOR).forEach { page ->
            SettingsSidebarRow(
                label = page.label(chinese),
                selected = page == selectedPage,
                onClick = { onPageSelected(page) },
            )
        }
        SettingsSidebarRow(
            label = SettingsPage.SIMPLEPERF.label(chinese),
            selected = selectedPage == SettingsPage.SIMPLEPERF && !simpleperfExpanded,
            leadingText = if (simpleperfExpanded) "⌄" else "›",
            fontWeight = FontWeight.Medium,
            onClick = {
                if (selectedPage == SettingsPage.SIMPLEPERF) {
                    onSimpleperfExpandedChange(!simpleperfExpanded)
                } else {
                    onPageSelected(SettingsPage.SIMPLEPERF)
                    onSimpleperfExpandedChange(true)
                }
            },
        )
        if (simpleperfExpanded) {
            CaptureSettingsSection.entries.forEach { section ->
                SettingsSidebarRow(
                    label = section.settingsLabel(chinese),
                    selected =
                        selectedPage == SettingsPage.SIMPLEPERF &&
                            section == selectedSimpleperfSection,
                    nested = true,
                    onClick = { onSimpleperfSectionSelected(section) },
                )
            }
        }
    }
}

@Composable
private fun SettingsSidebarRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    nested: Boolean = false,
    leadingText: String? = null,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    MaterialTheme.shapes.small,
                )
                .clickable(onClick = onClick)
                .padding(
                    start = if (nested) 32.dp else 12.dp,
                    end = 12.dp,
                    top = 7.dp,
                    bottom = 7.dp,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leadingText?.let {
            Text(
                text = it,
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else fontWeight,
        )
    }
}

@Composable
private fun GeneralSettingsContent(
    settings: ApplicationUiSettings,
    chinese: Boolean,
    onSettingsChanged: (ApplicationUiSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.general), style = MaterialTheme.typography.titleLarge)
        SettingsChoice(
            chinese = chinese,
            label = stringResource(Res.string.language),
            current = settings.language,
            options = ApplicationLanguagePreference.entries,
            optionLabel = { languagePreferenceLabel(it, chinese) },
            onSelected = { onSettingsChanged(settings.copy(language = it)) },
        )
        SettingsChoice(
            chinese = chinese,
            label = stringResource(Res.string.theme),
            current = settings.theme,
            options = ApplicationThemePreference.entries,
            optionLabel = { themePreferenceLabel(it, chinese) },
            onSelected = { onSettingsChanged(settings.copy(theme = it)) },
        )
    }
}

@Composable
private fun CompleteSimpleperfSettingsContent(
    settings: SimpleperfUiSettings,
    context: SimpleperfCaptureSettingsContext?,
    section: CaptureSettingsSection,
    darkTheme: Boolean,
    chinese: Boolean,
    onSettingsChanged: (SimpleperfUiSettings) -> Unit,
    onOpenUserGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (context == null) {
            Text(
                stringResource(Res.string.capture_parameters_connect_to_the_current_device_and_target_after),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SimpleperfSettingsSectionContent(
            section = section,
            setup = context?.setup,
            availableEvents = context?.availableEvents.orEmpty(),
            enabled = context?.enabled == true,
            darkTheme = darkTheme,
            flameTooltipMode = settings.flameTooltipMode,
            onFlameTooltipModeChange = {
                onSettingsChanged(settings.copy(flameTooltipMode = it))
            },
            simpleperfEngine = settings.simpleperfEngine,
            onSimpleperfEngineChange = {
                onSettingsChanged(settings.copy(simpleperfEngine = it))
            },
            onSelectTemplate = context?.onSelectTemplate ?: {},
            onUpdate = context?.onUpdateSamplingParameters ?: {},
            onOpenUserGuide = onOpenUserGuide,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun <T> SettingsChoice(
    chinese: Boolean,
    label: String,
    current: T,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.text, label, optionLabel(current)))
        }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsPage.label(chinese: Boolean): String =
    when (this) {
        SettingsPage.GENERAL -> stringResource(Res.string.general)
        SettingsPage.LAYOUT_INSPECTOR -> stringResource(Res.string.layout_inspector)
        SettingsPage.SIMPLEPERF -> stringResource(Res.string.simpleperf)
    }

@Composable
private fun CaptureSettingsSection.settingsLabel(chinese: Boolean): String =
    when (this) {
        CaptureSettingsSection.SAMPLING_TEMPLATE -> stringResource(Res.string.sampling_template)
        CaptureSettingsSection.CAPTURE_CONFIGURATION -> stringResource(Res.string.capture_configuration)
        CaptureSettingsSection.ADVANCED_PARAMETERS -> stringResource(Res.string.advanced_parameters)
        CaptureSettingsSection.FLAME_GRAPH -> stringResource(Res.string.flame_graph)
        CaptureSettingsSection.SIMPLEPERF_ENGINE -> stringResource(Res.string.simpleperf_engine)
        CaptureSettingsSection.USER_GUIDE -> stringResource(Res.string.user_guide)
    }

internal const val UNIFIED_SETTINGS_WIDTH_DP = 1100
internal const val UNIFIED_SETTINGS_HEIGHT_DP = 760
internal const val UNIFIED_SETTINGS_SIDEBAR_WIDTH_DP = 220
