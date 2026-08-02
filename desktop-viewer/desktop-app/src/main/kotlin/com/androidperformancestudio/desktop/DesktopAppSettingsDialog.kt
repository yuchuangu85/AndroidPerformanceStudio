package com.androidperformancestudio.desktop

import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui_components.generated.resources.Res as UiComponentsRes
import com.androidperformancestudio.desktop_app.generated.resources.Res
import com.androidperformancestudio.desktop_app.generated.resources.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import java.util.Locale
import java.io.File
import javax.swing.JFileChooser
import com.androidperformancestudio.presentation.CaptureSettingsSection
import com.androidperformancestudio.presentation.SimpleperfSettingsSectionContent
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.button.MacOSTextButton
import com.androidperformancestudio.ui_components.generated.resources.icon_collapse
import com.androidperformancestudio.ui_components.generated.resources.icon_expand
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

public enum class SettingsPage {
    GENERAL,
    LAYOUT_INSPECTOR,
    SIMPLEPERF,
    ABOUT,
}

@Composable
internal fun DesktopAppSettingsDialog(
    selectedPage: SettingsPage,
    applicationSettings: ApplicationUiSettings,
    simpleperfSettings: SimpleperfUiSettings,
    simpleperfCaptureSettingsContext: SimpleperfCaptureSettingsContext?,
    simpleperfInitialSection: CaptureSettingsSection,
    darkTheme: Boolean,
    language: UiLanguage,
    simpleperfLocale: Locale,
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
        title = localizedStringResource(Res.string.settings, language),
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
                Row(Modifier.weight(1f)) {
                    SettingsSidebar(
                        selectedPage = selectedPage,
                        selectedSimpleperfSection = activeSimpleperfSection,
                        simpleperfExpanded = simpleperfExpanded,
                        language = language,
                        onPageSelected = onPageSelected,
                        onSimpleperfExpandedChange = { simpleperfExpanded = it },
                        onSimpleperfSectionSelected = { section ->
                            activeSimpleperfSection = section
                            simpleperfExpanded = true
                            onPageSelected(SettingsPage.SIMPLEPERF)
                        },
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outline)
                    Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (persistenceErrorPage != null) {
                            Text(
                                localizedStringResource(
                                    Res.string.settings_could_not_be_saved_the_current_session_still_uses,
                                    language,
                                    persistenceErrorPage.label(language)
                                ),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        when (selectedPage) {
                            SettingsPage.GENERAL ->
                                GeneralSettingsContent(
                                    settings = applicationSettings,
                                    language = language,
                                    onSettingsChanged = onApplicationSettingsChanged,
                                    modifier = Modifier.weight(1f),
                                )

                            SettingsPage.LAYOUT_INSPECTOR ->
                                LayoutInspectorSettingsContent(
                                    language = language,
                                    onSettingsChanged = onLayoutInspectorSettingsChanged,
                                    modifier = Modifier.weight(1f),
                                )

                            SettingsPage.SIMPLEPERF ->
                                CompleteSimpleperfSettingsContent(
                                    settings = simpleperfSettings,
                                    context = simpleperfCaptureSettingsContext,
                                    section = activeSimpleperfSection,
                                    darkTheme = darkTheme,
                                    language = language,
                                    locale = simpleperfLocale,
                                    onSettingsChanged = onSimpleperfSettingsChanged,
                                    onOpenUserGuide = onOpenUserGuide,
                                    modifier = Modifier.weight(1f),
                                )

                            SettingsPage.ABOUT ->
                                AboutSettingsContent(
                                    language = language,
                                    modifier = Modifier.weight(1f),
                                )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingsFooter(language, onDismiss)
            }
        }
    }
}

@Composable
private fun SettingsFooter(language: UiLanguage, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        MacOSTextButton(onClick = onDismiss, label = localizedStringResource(Res.string.done, language), style = LocalViewerColors.current)
    }
}

@Composable
private fun SettingsSidebar(
    selectedPage: SettingsPage,
    selectedSimpleperfSection: CaptureSettingsSection,
    simpleperfExpanded: Boolean,
    language: UiLanguage,
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
                label = page.label(language),
                selected = page == selectedPage,
                onClick = { onPageSelected(page) },
            )
        }
        SettingsSidebarRow(
            label = SettingsPage.SIMPLEPERF.label(language),
            selected = selectedPage == SettingsPage.SIMPLEPERF && !simpleperfExpanded,
            leadingIcon =
                if (simpleperfExpanded) {
                    UiComponentsRes.drawable.icon_expand
                } else {
                    UiComponentsRes.drawable.icon_collapse
                },
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
                    label = section.settingsLabel(language),
                    selected =
                        selectedPage == SettingsPage.SIMPLEPERF &&
                                section == selectedSimpleperfSection,
                    nested = true,
                    onClick = { onSimpleperfSectionSelected(section) },
                )
            }
        }
        SettingsSidebarRow(
            label = SettingsPage.ABOUT.label(language),
            selected = selectedPage == SettingsPage.ABOUT,
            onClick = { onPageSelected(SettingsPage.ABOUT) },
        )
    }
}

@Composable
private fun SettingsSidebarRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    nested: Boolean = false,
    leadingIcon: DrawableResource? = null,
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
        if (leadingIcon != null) {
            Icon(
                painter = painterResource(leadingIcon),
                contentDescription = null,
                modifier = Modifier.width(16.dp),
                tint =
                    if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.SemiBold else fontWeight,
        )
    }
}

@Composable
private fun GeneralSettingsContent(
    settings: ApplicationUiSettings,
    language: UiLanguage,
    onSettingsChanged: (ApplicationUiSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(localizedStringResource(Res.string.general, language), style = MaterialTheme.typography.titleMedium)
        SettingsChoice(
            language = language,
            label = localizedStringResource(Res.string.language, language),
            current = settings.language,
            options = ApplicationLanguagePreference.entries,
            optionLabel = { languagePreferenceLabel(it, language) },
            onSelected = { onSettingsChanged(settings.copy(language = it)) },
        )
        SettingsChoice(
            language = language,
            label = localizedStringResource(Res.string.theme, language),
            current = settings.theme,
            options = ApplicationThemePreference.entries,
            optionLabel = { themePreferenceLabel(it, language) },
            onSelected = { onSettingsChanged(settings.copy(theme = it)) },
        )
        Text(
            localizedStringResource(Res.string.sdk_path, language),
            modifier = Modifier.padding(0.dp, 8.dp, 0.dp, 0.dp),
            style = MaterialTheme.typography.titleLarge
        )
        AndroidSdkPathSetting(
            settings = settings,
            language = language,
            onSettingsChanged = onSettingsChanged,
        )
    }
}

@Composable
private fun AboutSettingsContent(
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(localizedStringResource(Res.string.about, language), style = MaterialTheme.typography.titleLarge)
        Text(
            localizedStringResource(Res.string.android_performance_studio, language),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            localizedStringResource(Res.string.application_version, language, ApplicationVersion.current()),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun AndroidSdkPathSetting(
    settings: ApplicationUiSettings,
    language: UiLanguage,
    onSettingsChanged: (ApplicationUiSettings) -> Unit,
) {
    var draftPath by remember(settings.androidSdkPath) { mutableStateOf(settings.androidSdkPath.orEmpty()) }
    Column(modifier = Modifier.padding(8.dp, 0.dp, 8.dp, 0.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draftPath,
            onValueChange = { draftPath = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(localizedStringResource(Res.string.android_sdk_path, language)) },
            supportingText = { Text(localizedStringResource(Res.string.android_sdk_path_hint, language)) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    chooseAndroidSdkDirectory(
                        localizedStringResource(Res.string.select_android_sdk_directory, language),
                        draftPath,
                    )?.let { selected ->
                        draftPath = selected
                        onSettingsChanged(settings.copy(androidSdkPath = selected))
                    }
                },
            ) {
                Text(localizedStringResource(Res.string.browse, language))
            }
            OutlinedButton(
                onClick = {
                    val normalized = draftPath.trim().takeIf(String::isNotEmpty)
                    onSettingsChanged(settings.copy(androidSdkPath = normalized))
                },
                enabled = draftPath.trim().takeIf(String::isNotEmpty) != settings.androidSdkPath,
            ) {
                Text(localizedStringResource(Res.string.apply, language))
            }
            OutlinedButton(
                onClick = {
                    draftPath = ""
                    onSettingsChanged(settings.copy(androidSdkPath = null))
                },
                enabled = draftPath.isNotEmpty() || settings.androidSdkPath != null,
            ) {
                Text(localizedStringResource(Res.string.clear, language))
            }
        }
    }
}

private fun chooseAndroidSdkDirectory(
    title: String,
    currentPath: String,
): String? =
    JFileChooser().run {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        currentPath.trim().takeIf(String::isNotEmpty)?.let { path ->
            runCatching { File(path) }.getOrNull()?.takeIf(File::isDirectory)?.let { currentDirectory = it }
        }
        if (showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            selectedAndroidSdkDirectoryPath(selectedFile)
        } else {
            null
        }
    }

internal fun selectedAndroidSdkDirectoryPath(selectedDirectory: File?): String? =
    selectedDirectory
        ?.takeIf(File::isDirectory)
        ?.toPath()
        ?.toAbsolutePath()
        ?.normalize()
        ?.toString()

@Composable
private fun CompleteSimpleperfSettingsContent(
    settings: SimpleperfUiSettings,
    context: SimpleperfCaptureSettingsContext?,
    section: CaptureSettingsSection,
    darkTheme: Boolean,
    language: UiLanguage,
    locale: Locale,
    onSettingsChanged: (SimpleperfUiSettings) -> Unit,
    onOpenUserGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (context == null) {
            Text(
                localizedStringResource(
                    Res.string.capture_parameters_connect_to_the_current_device_and_target_after,
                    language
                ),
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
            locale = locale,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun <T> SettingsChoice(
    language: UiLanguage,
    label: String,
    current: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.wrapContentWidth().padding(8.dp, 0.dp, 8.dp, 0.dp)) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.width(320.dp)
        ) {
            Text(localizedStringResource(Res.string.text, language, label, optionLabel(current)))
        }
        DropdownMenu(
            expanded = expanded,
            modifier = Modifier.wrapContentHeight().width(200.dp),
            shape = RoundedCornerShape(10.dp),
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
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

private fun SettingsPage.label(language: UiLanguage): String =
    when (this) {
        SettingsPage.GENERAL -> localizedStringResource(Res.string.general, language)
        SettingsPage.LAYOUT_INSPECTOR -> localizedStringResource(Res.string.layout_inspector, language)
        SettingsPage.SIMPLEPERF -> localizedStringResource(Res.string.simpleperf, language)
        SettingsPage.ABOUT -> localizedStringResource(Res.string.about, language)
    }

private fun CaptureSettingsSection.settingsLabel(language: UiLanguage): String =
    when (this) {
        CaptureSettingsSection.SAMPLING_TEMPLATE -> localizedStringResource(Res.string.sampling_template, language)
        CaptureSettingsSection.CAPTURE_CONFIGURATION -> localizedStringResource(
            Res.string.capture_configuration,
            language
        )

        CaptureSettingsSection.ADVANCED_PARAMETERS -> localizedStringResource(Res.string.advanced_parameters, language)
        CaptureSettingsSection.FLAME_GRAPH -> localizedStringResource(Res.string.flame_graph, language)
        CaptureSettingsSection.SIMPLEPERF_ENGINE -> localizedStringResource(Res.string.simpleperf_engine, language)
        CaptureSettingsSection.USER_GUIDE -> localizedStringResource(Res.string.user_guide, language)
    }

internal const val UNIFIED_SETTINGS_WIDTH_DP = 1100
internal const val UNIFIED_SETTINGS_HEIGHT_DP = 760
internal const val UNIFIED_SETTINGS_SIDEBAR_WIDTH_DP = 220
