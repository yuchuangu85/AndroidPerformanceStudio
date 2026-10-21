package com.androidperformancestudio.desktop

import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import java.io.File
import com.androidperformancestudio.presentation.CaptureSettingsSection
import com.androidperformancestudio.presentation.SimpleperfSettingsSectionContent
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ProvideViewerThemeContext
import com.androidperformancestudio.ui.ViewerThemeContext
import com.androidperformancestudio.ui.viewerOutlinedTextFieldColors
import com.androidperformancestudio.ui.studio.StudioCard
import com.androidperformancestudio.ui.studio.StudioPanel
import com.androidperformancestudio.ui.studio.StudioTokens
import com.androidperformancestudio.ui.switch.MacOSSwitch
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui_components.generated.resources.Res as UiComponentsRes
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_cpu_profiler
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_layout_inspector
import com.androidperformancestudio.ui_components.generated.resources.ic_settings_about
import com.androidperformancestudio.ui_components.generated.resources.ic_settings_ai
import com.androidperformancestudio.ui_components.generated.resources.ic_settings_configuration
import com.androidperformancestudio.ui_components.generated.resources.ic_settings_general
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

public enum class SettingsPage {
    GENERAL,
    CONFIGURATION,
    LAYOUT_INSPECTOR,
    SIMPLEPERF,
    AI,
    ABOUT,
}

@Composable
internal fun DesktopAppSettingsPage(
    selectedPage: SettingsPage,
    applicationSettings: ApplicationUiSettings,
    simpleperfSettings: SimpleperfUiSettings,
    simpleperfCaptureSettingsContext: SimpleperfCaptureSettingsContext?,
    simpleperfInitialSection: CaptureSettingsSection,
    viewerThemeContext: ViewerThemeContext,
    language: UiLanguage,
    simpleperfLocale: Locale,
    sourceWorkspaceRuntime: SourceWorkspaceRuntime,
    onPageSelected: (SettingsPage) -> Unit,
    onApplicationSettingsChanged: (ApplicationUiSettings) -> Unit,
    onSimpleperfSettingsChanged: (SimpleperfUiSettings) -> Unit,
    onLayoutInspectorSettingsChanged: () -> Unit,
    onOpenUserGuide: () -> Unit,
    persistenceErrorPage: SettingsPage?,
) {
    ProvideViewerThemeContext(viewerThemeContext) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = LocalViewerColors.current.workspace,
        ) {
            Column(Modifier.fillMaxSize()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(Modifier.weight(1f)) {
                    SettingsSidebar(
                        selectedPage = selectedPage,
                        language = language,
                        onPageSelected = onPageSelected,
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outline)
                    Column(
                        Modifier.weight(1f).padding(StudioTokens.compactContentPadding),
                        verticalArrangement = Arrangement.spacedBy(StudioTokens.compactContentPadding),
                    ) {
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

                            SettingsPage.CONFIGURATION ->
                                ConfigurationSettingsContent(
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
                                    initialSection = simpleperfInitialSection,
                                    darkTheme = viewerThemeContext.colors.isDark,
                                    language = language,
                                    locale = simpleperfLocale,
                                    onSettingsChanged = onSimpleperfSettingsChanged,
                                    onOpenUserGuide = onOpenUserGuide,
                                    modifier = Modifier.weight(1f),
                                )

                            SettingsPage.AI ->
                                AiSettingsContent(
                                    language = language,
                                    runtime = sourceWorkspaceRuntime,
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
            }
        }
    }
}

@Composable
private fun SettingsPageTitle(
    text: String,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    Text(
        text = text,
        color = LocalViewerColors.current.accent,
        style = style,
    )
}

@Composable
private fun SettingsSidebar(
    selectedPage: SettingsPage,
    language: UiLanguage,
    onPageSelected: (SettingsPage) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .width(UNIFIED_SETTINGS_SIDEBAR_WIDTH_DP.dp)
                .fillMaxHeight()
                .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(SettingsPage.GENERAL, SettingsPage.CONFIGURATION, SettingsPage.LAYOUT_INSPECTOR).forEach { page ->
            SettingsSidebarRow(
                label = page.label(language),
                selected = page == selectedPage,
                onClick = { onPageSelected(page) },
                leadingIcon = page.settingsIconResource,
            )
        }
        // Simpleperf is one page: every category is a card inside it instead of a nested entry.
        SettingsSidebarRow(
            label = SettingsPage.SIMPLEPERF.label(language),
            selected = selectedPage == SettingsPage.SIMPLEPERF,
            onClick = { onPageSelected(SettingsPage.SIMPLEPERF) },
            leadingIcon = SettingsPage.SIMPLEPERF.settingsIconResource,
        )
        SettingsSidebarRow(
            label = SettingsPage.AI.label(language),
            selected = selectedPage == SettingsPage.AI,
            onClick = { onPageSelected(SettingsPage.AI) },
            leadingIcon = SettingsPage.AI.settingsIconResource,
        )
        SettingsSidebarRow(
            label = SettingsPage.ABOUT.label(language),
            selected = selectedPage == SettingsPage.ABOUT,
            onClick = { onPageSelected(SettingsPage.ABOUT) },
            leadingIcon = SettingsPage.ABOUT.settingsIconResource,
        )
    }
}

@Composable
private fun AiSettingsContent(
    language: UiLanguage,
    runtime: SourceWorkspaceRuntime,
    modifier: Modifier = Modifier,
) {
    var configured by remember(runtime) { mutableStateOf(!runtime.credential(OPENAI_API_KEY).isNullOrBlank()) }
    var apiKey by remember { mutableStateOf("") }
    var model by remember(runtime) { mutableStateOf(runtime.aiModel()) }
    var endpoint by remember(runtime) { mutableStateOf(runtime.aiEndpoint()) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelsExpanded by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refreshModels() {
        working = true
        modelsError = null
        runCatching {
            withContext(Dispatchers.IO) { runtime.aiModels(apiKey.trim(), endpoint.trim()) }
        }.onSuccess { models = it }
            .onFailure { modelsError = it.message ?: it.javaClass.simpleName }
        working = false
    }

    LaunchedEffect(runtime) {
        if (configured) refreshModels()
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.compactContentPadding),
    ) {
        SettingsPageTitle(
            text = localizedStringResource(Res.string.source_ai_settings, language),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            localizedStringResource(
                if (configured) Res.string.source_ai_key_configured else Res.string.source_ai_key_storage_notice,
                language,
            ),
        )
        OutlinedTextField(
            colors = viewerOutlinedTextFieldColors(),
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(localizedStringResource(Res.string.source_openai_api_key, language)) },
            supportingText = {
                if (!configured && apiKey.isBlank()) {
                    Text(localizedStringResource(Res.string.source_ai_key_required, language))
                }
            },
            isError = !configured && apiKey.isBlank(),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            singleLine = true,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                TextButton(
                    enabled = models.isNotEmpty(),
                    onClick = { modelsExpanded = true },
                ) {
                    Text(localizedStringResource(Res.string.source_choose_model, language, models.size))
                }
                DropdownMenu(
                    expanded = modelsExpanded,
                    onDismissRequest = { modelsExpanded = false },
                ) {
                    models.forEach { modelId ->
                        DropdownMenuItem(
                            text = { Text(modelId) },
                            onClick = {
                                model = modelId
                                modelsExpanded = false
                            },
                        )
                    }
                }
            }
            TextButton(
                enabled = !working && endpoint.isNotBlank() && (configured || apiKey.isNotBlank()),
                onClick = { scope.launch { refreshModels() } },
            ) {
                Text(localizedStringResource(Res.string.source_refresh_models, language))
            }
            if (working) {
                CircularProgressIndicator(Modifier.size(20.dp))
            }
        }
        modelsError?.let { message ->
            Text(
                localizedStringResource(Res.string.source_models_load_failed, language, message),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedTextField(
            colors = viewerOutlinedTextFieldColors(),
            value = model,
            onValueChange = { model = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(localizedStringResource(Res.string.source_model, language)) },
            singleLine = true,
        )
        OutlinedTextField(
            colors = viewerOutlinedTextFieldColors(),
            value = endpoint,
            onValueChange = { endpoint = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(localizedStringResource(Res.string.source_endpoint, language)) },
            singleLine = true,
        )
        OutlinedButton(
            enabled = model.isNotBlank() && endpoint.isNotBlank() && (configured || apiKey.isNotBlank()),
            onClick = {
                saveError = null
                runCatching {
                    apiKey.trim().takeIf(String::isNotBlank)?.let { runtime.saveCredential(OPENAI_API_KEY, it) }
                    runtime.saveAiConfiguration(model.trim(), endpoint.trim())
                }.onSuccess {
                    configured = true
                    apiKey = ""
                }.onFailure { failure ->
                    saveError = failure.message ?: failure.javaClass.simpleName
                }
            },
        ) {
            Text(localizedStringResource(Res.string.source_save, language))
        }
        saveError?.let { message ->
            Text(
                localizedStringResource(Res.string.source_operation_failed, language, message),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
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
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
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
                modifier = Modifier.size(SETTINGS_SIDEBAR_ICON_SIZE),
                tint =
                    if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
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
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = LocalViewerColors.current
    Row(
        // The label reads as a section label: it uses the accent and lines up with the section
        // titles, which carry no horizontal inset. Only the switch keeps the row inset.
        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = colors.accent,
            style = MaterialTheme.typography.bodyLarge,
        )
        MacOSSwitch(checked = checked, onCheckedChange = onCheckedChange)
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
        verticalArrangement = Arrangement.spacedBy(StudioTokens.compactContentPadding),
    ) {
        SettingsPageTitle(localizedStringResource(Res.string.general, language))
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
        SettingsChoice(
            language = language,
            label = localizedStringResource(Res.string.display_size, language),
            current = settings.displayScale,
            options = ApplicationDisplayScale.entries,
            optionLabel = ::displayScalePreferenceLabel,
            onSelected = { onSettingsChanged(settings.copy(displayScale = it)) },
        )
        // Checked shows the sidebar labels (expanded); unchecked keeps the icon-only collapsed rail.
        SettingsToggleRow(
            label = localizedStringResource(Res.string.show_sidebar_titles, language),
            checked = !settings.navigationRailCollapsed,
            onCheckedChange = { showSidebarTitles ->
                onSettingsChanged(settings.copy(navigationRailCollapsed = !showSidebarTitles))
            },
        )
        ThemeColorSettingsContent(
            settings = settings,
            language = language,
            onSettingsChanged = onSettingsChanged,
        )
    }
}

@Composable
private fun ConfigurationSettingsContent(
    settings: ApplicationUiSettings,
    language: UiLanguage,
    onSettingsChanged: (ApplicationUiSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.compactContentPadding),
    ) {
        SettingsPageTitle(localizedStringResource(Res.string.configuration, language))
        SettingsPageTitle(
            text = localizedStringResource(Res.string.sdk_path, language),
            style = MaterialTheme.typography.titleLarge,
        )
        AndroidSdkPathSetting(
            settings = settings,
            language = language,
            onSettingsChanged = onSettingsChanged,
        )
    }
}

@Composable
private fun ThemeColorSettingsContent(
    settings: ApplicationUiSettings,
    language: UiLanguage,
    onSettingsChanged: (ApplicationUiSettings) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsPageTitle(localizedStringResource(Res.string.theme_color, language))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(THEME_SECTION_GAP),
            verticalAlignment = Alignment.Top,
        ) {
            ThemeColorPresets(
                selectedColor = settings.themeColor,
                language = language,
                onSelect = { color -> onSettingsChanged(settings.copy(themeColor = color)) },
                modifier = Modifier.weight(THEME_PRESET_WIDTH_FRACTION),
            )
            CustomThemeColorColumn(
                color = settings.themeColor,
                language = language,
                onColorChange = { color -> onSettingsChanged(settings.copy(themeColor = color)) },
                onResetDefault = { onSettingsChanged(settings.copy(themeColor = DEFAULT_THEME_COLOR)) },
            )
            // The picker sits one section gap after the bars; only the leftover width trails it, so
            // the preset grid still occupies half of the free width.
            Spacer(Modifier.weight(1f - THEME_PRESET_WIDTH_FRACTION))
        }
    }
}

private val THEME_SECTION_GAP = 24.dp

/** Share of the free width the preset grid occupies; the bars are sized from this. */
private const val THEME_PRESET_WIDTH_FRACTION = 0.5f

/** The eight built-in accents, two per row so the grid sits beside the picker. */
@Composable
private fun ThemeColorPresets(
    selectedColor: Color,
    language: UiLanguage,
    onSelect: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ApplicationThemeColor.entries.chunked(THEME_SWATCH_COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { preset ->
                    ThemeColorOption(
                        color = preset,
                        selected = preset.color == selectedColor,
                        label = themeColorPreferenceLabel(preset, language),
                        onClick = { onSelect(preset.color) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(THEME_SWATCH_COLUMNS - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Custom accent entry: the picker on top, the hex field and default reset underneath. */
@Composable
private fun CustomThemeColorColumn(
    color: Color,
    language: UiLanguage,
    onColorChange: (Color) -> Unit,
    onResetDefault: () -> Unit,
) {
    Column(
        modifier = Modifier.width(THEME_PICKER_WIDTH),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThemeColorPicker(color = color, onColorChange = onColorChange)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HexThemeColorField(
                color = color,
                label = localizedStringResource(Res.string.theme_color_custom, language),
                onColorChange = onColorChange,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onResetDefault) {
                Text(localizedStringResource(Res.string.theme_color_default, language))
            }
        }
    }
}

@Composable
private fun HexThemeColorField(
    color: Color,
    label: String,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(color.toHexString()) }
    // Follow swatches, the picker and the default reset without fighting the user mid-typing: an
    // unparsable draft never matches the active colour, so it is left alone.
    LaunchedEffect(color) {
        if (parseHexColor(text) != color) text = color.toHexString()
    }
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next
            parseHexColor(next)?.let(onColorChange)
        },
        modifier = modifier,
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text(HEX_COLOR_PLACEHOLDER) },
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = viewerOutlinedTextFieldColors(),
    )
}

private const val HEX_COLOR_PLACEHOLDER = "#RRGGBB"

private const val THEME_SWATCH_COLUMNS = 2

@Composable
private fun ThemeColorOption(
    color: ApplicationThemeColor,
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StudioCard(
        modifier = modifier.height(38.dp).clickable(onClick = onClick),
        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        borderColor = if (selected) color.color else MaterialTheme.colorScheme.outline,
        contentPadding = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(18.dp).background(color.color, CircleShape),
            )
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                Text(
                    text = "✓",
                    color = color.color,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

@Composable
private fun AboutSettingsContent(
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.compactContentPadding),
    ) {
        SettingsPageTitle(
            text = localizedStringResource(Res.string.about, language),
            style = MaterialTheme.typography.titleLarge,
        )
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
            colors = viewerOutlinedTextFieldColors(),
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
    com.androidperformancestudio.ui.chooseDirectory(
        parent = null,
        title = title,
        startDirectory = currentPath.trim().takeIf(String::isNotEmpty)?.let { path -> runCatching { File(path) }.getOrNull() },
    )?.let(::selectedAndroidSdkDirectoryPath)

internal fun selectedAndroidSdkDirectoryPath(selectedDirectory: File?): String? =
    selectedDirectory
        ?.takeIf(File::isDirectory)
        ?.toPath()
        ?.toAbsolutePath()
        ?.normalize()
        ?.toString()

/**
 * One Simpleperf page: every [CaptureSettingsSection] is a card, so the category is visible without
 * navigating a nested sidebar. [initialSection] is the deep link target and scrolls that card in.
 */
@Composable
private fun CompleteSimpleperfSettingsContent(
    settings: SimpleperfUiSettings,
    context: SimpleperfCaptureSettingsContext?,
    initialSection: CaptureSettingsSection,
    darkTheme: Boolean,
    language: UiLanguage,
    locale: Locale,
    onSettingsChanged: (SimpleperfUiSettings) -> Unit,
    onOpenUserGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(initialSection) {
        val index = CaptureSettingsSection.entries.indexOf(initialSection)
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }
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
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.compactContentPadding),
        ) {
            items(CaptureSettingsSection.entries, key = CaptureSettingsSection::name) { section ->
                StudioPanel(modifier = Modifier.fillMaxWidth()) {
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
                        modifier = Modifier.fillMaxWidth(),
                        embedded = true,
                    )
                }
            }
        }
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
    DropdownSelector(
        items = options,
        selectedItem = current,
        onItemSelected = onSelected,
        itemLabel = optionLabel,
        selectedItemLabel = { localizedStringResource(Res.string.text, language, label, optionLabel(it)) },
        placeholder = label,
        modifier = Modifier.padding(horizontal = 8.dp).width(320.dp),
        menuModifier = Modifier.width(200.dp),
        fillWidth = true,
    )
}

private fun SettingsPage.label(language: UiLanguage): String =
    when (this) {
        SettingsPage.GENERAL -> localizedStringResource(Res.string.general, language)
        SettingsPage.CONFIGURATION -> localizedStringResource(Res.string.configuration, language)
        SettingsPage.LAYOUT_INSPECTOR -> localizedStringResource(Res.string.layout_inspector, language)
        SettingsPage.SIMPLEPERF -> localizedStringResource(Res.string.simpleperf, language)
        SettingsPage.AI -> localizedStringResource(Res.string.source_ai_settings, language)
        SettingsPage.ABOUT -> localizedStringResource(Res.string.about, language)
    }

/** Sidebar icon for each settings page. */
private val SettingsPage.settingsIconResource: DrawableResource
    get() =
        when (this) {
            SettingsPage.GENERAL -> UiComponentsRes.drawable.ic_settings_general
            SettingsPage.CONFIGURATION -> UiComponentsRes.drawable.ic_settings_configuration
            SettingsPage.LAYOUT_INSPECTOR -> UiComponentsRes.drawable.ic_nav_layout_inspector
            SettingsPage.SIMPLEPERF -> UiComponentsRes.drawable.ic_nav_cpu_profiler
            SettingsPage.AI -> UiComponentsRes.drawable.ic_settings_ai
            SettingsPage.ABOUT -> UiComponentsRes.drawable.ic_settings_about
        }

private fun displayScalePreferenceLabel(scale: ApplicationDisplayScale): String =
    "${scale.storageValue}%"

private fun themeColorPreferenceLabel(color: ApplicationThemeColor, language: UiLanguage): String =
    when (color) {
        ApplicationThemeColor.BANANA_RED -> localizedStringResource(Res.string.theme_color_banana_red, language)
        ApplicationThemeColor.WARM_SUN_ORANGE -> localizedStringResource(Res.string.theme_color_warm_sun_orange, language)
        ApplicationThemeColor.CORNFLOWER_BLUE -> localizedStringResource(Res.string.theme_color_cornflower_blue, language)
        ApplicationThemeColor.JADE_GREEN -> localizedStringResource(Res.string.theme_color_jade_green, language)
        ApplicationThemeColor.MERLOT_PINK -> localizedStringResource(Res.string.theme_color_merlot_pink, language)
        ApplicationThemeColor.AZURE -> localizedStringResource(Res.string.theme_color_azure, language)
        ApplicationThemeColor.LEMON_YELLOW -> localizedStringResource(Res.string.theme_color_lemon_yellow, language)
        ApplicationThemeColor.ROYAL_PURPLE -> localizedStringResource(Res.string.theme_color_royal_purple, language)
    }

private const val OPENAI_API_KEY = "openai:api-key"

internal const val UNIFIED_SETTINGS_SIDEBAR_WIDTH_DP = 176

private val SETTINGS_SIDEBAR_ICON_SIZE = 16.dp
