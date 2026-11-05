package com.androidperformancestudio.desktop

import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTextField
import com.androidperformancestudio.ui.ViewerTypography
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import java.util.Locale
import java.io.File
import com.androidperformancestudio.presentation.CaptureSettingsSection
import com.androidperformancestudio.presentation.SimpleperfSettingsSectionContent
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.CopyableText
import com.androidperformancestudio.ui.ProvideViewerThemeContext
import com.androidperformancestudio.ui.ViewerThemeContext
import com.androidperformancestudio.ui.viewerOutlinedTextFieldColors
import com.androidperformancestudio.ui.studio.StudioCard
import com.androidperformancestudio.ui.studio.StudioPanel
import com.androidperformancestudio.ui.studio.StudioTokens
import com.androidperformancestudio.ui.switch.MacOSSwitch
import com.androidperformancestudio.ui.button.MacOSTextButton
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.SegmentedSelector
import com.androidperformancestudio.ui.SegmentedSelectorStyle
import com.androidperformancestudio.ai.ImportedCredentialKind
import com.androidperformancestudio.ai.LocalAiConfigSource
import com.androidperformancestudio.ai.LocalAiConfigurationImport
import com.androidperformancestudio.ai.LocalAiConfigurationImporter
import com.androidperformancestudio.ai.LocalAiConfigurationReader
import com.androidperformancestudio.ai.LocalAiImportFailure
import com.androidperformancestudio.aiplatform.app.AiPlatformRuntime
import com.androidperformancestudio.aiplatform.app.AiPlatformSettingsStore
import com.androidperformancestudio.aiplatform.presentation.AiManagementSection
import com.androidperformancestudio.aiplatform.presentation.AiPlatformManagementSettings
import androidx.compose.ui.platform.testTag
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
import com.androidperformancestudio.ai.AiModelCatalogException
import com.androidperformancestudio.ai.AiModelCatalogFailure
import com.androidperformancestudio.ai.AiRequestException
import com.androidperformancestudio.ai.AiRequestFailureKind

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
    aiPlatformRuntime: AiPlatformRuntime? = null,
    aiPlatformSettingsStore: AiPlatformSettingsStore? = null,
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
                        Modifier.weight(1f).padding(StudioTokens.workspacePadding),
                        verticalArrangement = Arrangement.spacedBy(StudioTokens.compactContentPadding),
                    ) {
                        if (persistenceErrorPage != null) {
                            CopyableText(
                                localizedStringResource(
                                    Res.string.settings_could_not_be_saved_the_current_session_still_uses,
                                    language,
                                    persistenceErrorPage.label(language)
                                ),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                language = language,
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
                                AiSettingsPageContent(
                                    language = language,
                                    runtime = sourceWorkspaceRuntime,
                                    aiPlatformRuntime = aiPlatformRuntime,
                                    modifier = Modifier.weight(1f),
                                    platformSettingsStore = aiPlatformSettingsStore,
                                    initialCategory = applicationSettings.aiSettingsCategory,
                                    onCategorySelected = { category ->
                                        onApplicationSettingsChanged(applicationSettings.copy(aiSettingsCategory = category))
                                    },
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
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = LocalViewerColors.current.accent,
        style = style,
        modifier = modifier,
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

internal enum class AiSettingsCategory(val managementSection: AiManagementSection?) {
    PROVIDER(null),
    AGENT(AiManagementSection.AGENTS),
    SKILL(AiManagementSection.SKILLS),
    POLICY(AiManagementSection.POLICY),
    ;

    companion object {
        fun parse(value: String?): AiSettingsCategory {
            val normalized = value?.trim()?.uppercase(Locale.ROOT)
            if (normalized == "CONNECTION") return PROVIDER
            return entries.firstOrNull { it.name == normalized } ?: PROVIDER
        }
    }
}

@Composable
internal fun AiSettingsPageContent(
    language: UiLanguage,
    runtime: SourceWorkspaceRuntime,
    aiPlatformRuntime: AiPlatformRuntime? = null,
    modifier: Modifier = Modifier,
    localConfigReader: LocalAiConfigurationReader? = null,
    platformSettingsStore: AiPlatformSettingsStore? = null,
    initialCategory: AiSettingsCategory = AiSettingsCategory.PROVIDER,
    onCategorySelected: (AiSettingsCategory) -> Unit = {},
) {
    var category by remember(initialCategory) { mutableStateOf(initialCategory) }
    Column(
        modifier.fillMaxSize().testTag("ai-settings-page"),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.compactContentPadding),
    ) {
        SettingsPageTitle(
            text = localizedStringResource(Res.string.source_ai_settings, language),
            style = ViewerTypography.pageTitle,
            modifier = Modifier.testTag("ai-settings-title"),
        )
        Box(
            modifier = Modifier.fillMaxWidth().testTag("ai-settings-category-row"),
            contentAlignment = Alignment.Center,
        ) {
            SegmentedSelector(
                items = AiSettingsCategory.entries,
                selectedItem = category,
                onItemSelected = {
                    category = it
                    onCategorySelected(it)
                },
                itemLabel = {
                    when (it) {
                        AiSettingsCategory.PROVIDER -> "Provider"
                        AiSettingsCategory.AGENT -> "Agent"
                        AiSettingsCategory.SKILL -> "Skill"
                        AiSettingsCategory.POLICY -> if (language == UiLanguage.SIMPLIFIED_CHINESE) "执行策略" else "Policy"
                    }
                },
                selectorDescription = if (language == UiLanguage.SIMPLIFIED_CHINESE) "AI 设置分类" else "AI settings category",
                modifier = Modifier.testTag("ai-settings-category"),
                style = SegmentedSelectorStyle.COMPACT_OUTLINED,
                textStyle = ViewerTypography.body.copy(lineHeight = ViewerTypography.pageTitle.lineHeight),
                maxWidth = Dp.Infinity,
            )
        }
        val section = category.managementSection
        if (section == null) {
            AiSettingsContent(
                language = language,
                runtime = runtime,
                modifier = Modifier.weight(1f),
                localConfigReader = localConfigReader,
                platformSettingsStore = platformSettingsStore,
            )
        } else {
            AiPlatformManagementSettings(
                runtime = aiPlatformRuntime,
                section = section,
                language = language,
                modifier = Modifier.weight(1f),
                settingsStoreOverride = platformSettingsStore,
            )
        }
    }
}

@Composable
internal fun AiSettingsContent(
    language: UiLanguage,
    runtime: SourceWorkspaceRuntime,
    modifier: Modifier = Modifier,
    localConfigReader: LocalAiConfigurationReader? = null,
    platformSettingsStore: AiPlatformSettingsStore? = null,
) {
    val reader = remember(localConfigReader) { localConfigReader ?: LocalAiConfigurationImporter() }
    val settingsStore = remember(platformSettingsStore) { platformSettingsStore ?: AiPlatformSettingsStore() }
    val applied by settingsStore.settings.collectAsState()
    var selected by remember(runtime, settingsStore) { mutableStateOf(AiProviderConnection.fromProviderId(applied.providerId)) }
    val drafts = remember(runtime) { mutableMapOf<AiProviderConnection, AiProviderDraft>() }
    val draft = drafts.getOrPut(selected) { AiProviderDraft(runtime, selected) }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.compactContentPadding),
    ) {
        DropdownSelector(
            items = AiProviderConnection.entries,
            selectedItem = selected,
            onItemSelected = { selected = it },
            itemLabel = { providerLabel(it, language) },
            placeholder = "Provider",
            selectorDescription = "Provider",
            modifier = Modifier.width(320.dp).testTag("ai-provider-selector").semantics(mergeDescendants = true) {
                if (draft.working.value) disabled()
            },
            menuModifier = Modifier.width(320.dp).testTag("ai-provider-menu"),
            fillWidth = true,
            enabled = !draft.working.value,
        )
        Text(
            text = localizedStringResource(Res.string.source_ai_applied_provider, language, providerLabel(AiProviderConnection.fromProviderId(applied.providerId), language)),
            modifier = Modifier.testTag("ai-provider-applied"),
            color = LocalViewerColors.current.secondaryText,
            style = ViewerTypography.secondary,
        )
        key(selected) {
            AiConnectionSettingsGroup(selected, draft, language, runtime, reader, settingsStore)
        }
    }
}

private class AiProviderDraft(runtime: SourceWorkspaceRuntime, connection: AiProviderConnection) {
    val configured = mutableStateOf(runCatching { runtime.providerHasCredential(connection) }.getOrDefault(false))
    val apiKey = mutableStateOf("")
    val credentialKind = mutableStateOf(runtime.providerCredentialKind(connection))
    val model = mutableStateOf(runtime.providerModel(connection))
    val endpoint = mutableStateOf(runtime.providerEndpoint(connection))
    val modelsEndpoint = mutableStateOf(runtime.providerModelsEndpoint(connection))
    val models = mutableStateOf<List<String>>(emptyList())
    val modelsLoaded = mutableStateOf(false)
    val working = mutableStateOf(false)
    val modelsError = mutableStateOf<String?>(null)
    val saveError = mutableStateOf<String?>(null)
    val importFailure = mutableStateOf<LocalAiImportFailure?>(null)
    val imported = mutableStateOf(false)
    val importedWithoutKey = mutableStateOf(false)
}

private fun providerLabel(connection: AiProviderConnection, language: UiLanguage): String = when (connection) {
    AiProviderConnection.CODEX -> "Codex"
    AiProviderConnection.CLAUDE -> "Claude"
    AiProviderConnection.CUSTOM -> if (language == UiLanguage.SIMPLIFIED_CHINESE) "自定义（OpenAI 兼容）" else "Custom (OpenAI compatible)"
}

@Composable
private fun AiConnectionSettingsGroup(
    connection: AiProviderConnection,
    draft: AiProviderDraft,
    language: UiLanguage,
    runtime: SourceWorkspaceRuntime,
    reader: LocalAiConfigurationReader,
    settingsStore: AiPlatformSettingsStore,
) {
    val source = connection.localSource
    val groupId = connection.name.lowercase()
    val tagPrefix = "ai-$groupId"
    var configured by draft.configured
    var apiKey by draft.apiKey
    var credentialKind by draft.credentialKind
    var model by draft.model
    var endpoint by draft.endpoint
    var modelsEndpoint by draft.modelsEndpoint
    var models by draft.models
    var modelsLoaded by draft.modelsLoaded
    var working by draft.working
    var modelsError by draft.modelsError
    var saveError by draft.saveError
    var importFailure by draft.importFailure
    var imported by draft.imported
    var importedWithoutKey by draft.importedWithoutKey
    val scope = rememberCoroutineScope()

    suspend fun refreshModels() {
        working = true
        modelsError = null
        val requestedConnection = connection
        val requestedKey = apiKey.trim()
        val requestedEndpoint = endpoint.trim()
        val requestedModelsEndpoint = modelsEndpoint.trim()
        val requestedKind = credentialKind
        try {
            models = withContext(Dispatchers.IO) {
                runtime.providerModels(requestedConnection, requestedKey, requestedEndpoint, requestedModelsEndpoint, requestedKind)
            }
            modelsLoaded = true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            modelsError = modelCatalogFailureMessage(failure, language, requestedConnection)
        } finally {
            working = false
        }
    }

    suspend fun importConfiguration() {
        val selectedSource = source ?: return
        working = true
        importFailure = null
        imported = false
        try {
            when (val result = withContext(Dispatchers.IO) { reader.read(selectedSource) }) {
                is LocalAiConfigurationImport.Success -> {
                    val config = result.config
                    if (config.source != source) {
                        importFailure = LocalAiImportFailure.UNSUPPORTED_CONFIG
                        return
                    }
                    val nextModel = config.model ?: runtime.providerModel(connection)
                    val nextEndpoint = config.endpoint ?: runtime.providerEndpoint(connection)
                    val nextModelsEndpoint = modelsEndpoint.takeIf {
                        connection != AiProviderConnection.CLAUDE && nextEndpoint.trim() == endpoint.trim()
                    }.orEmpty()
                    apiKey = config.apiKey.orEmpty()
                    credentialKind = config.credentialKind
                    model = nextModel
                    endpoint = nextEndpoint
                    modelsEndpoint = nextModelsEndpoint
                    models = emptyList()
                    modelsLoaded = false
                    modelsError = null
                    saveError = null
                    // An imported endpoint without a key must not borrow a previous connection's credential.
                    configured = false
                    importedWithoutKey = apiKey.isBlank()
                    imported = true
                    if (apiKey.isNotBlank()) refreshModels()
                }
                is LocalAiConfigurationImport.Failure -> importFailure = result.reason
            }
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (_: Exception) {
            importFailure = LocalAiImportFailure.MALFORMED
        } finally {
            working = false
        }
    }

    LaunchedEffect(runtime, connection) {
        if (configured) refreshModels()
    }

    StudioCard(
        modifier = Modifier.fillMaxWidth().testTag("ai-connection-group-$groupId"),
        contentSpacing = StudioTokens.compactContentPadding,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = providerLabel(connection, language),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (source != null) MacOSTextButton(
                label = localizedStringResource(if (source == LocalAiConfigSource.CODEX) Res.string.source_ai_import_codex else Res.string.source_ai_import_claude, language),
                modifier = Modifier.testTag("ai-import-$groupId"),
                enabled = !working,
                onClick = { scope.launch { importConfiguration() } },
            )
        }
        if (imported) {
            Text(
                localizedStringResource(if (importedWithoutKey) Res.string.source_ai_imported_without_key else Res.string.source_ai_imported, language),
                modifier = Modifier.testTag("$tagPrefix-import-status"),
            )
        }
        importFailure?.let { reason ->
            val message = when (reason) {
                LocalAiImportFailure.NOT_FOUND -> Res.string.source_ai_import_not_found
                LocalAiImportFailure.MALFORMED -> Res.string.source_ai_import_malformed
                LocalAiImportFailure.PERMISSION_DENIED -> Res.string.source_ai_import_denied
                LocalAiImportFailure.UNSUPPORTED_AUTH -> Res.string.source_ai_import_auth
                LocalAiImportFailure.UNSUPPORTED_CONFIG -> Res.string.source_ai_import_unsupported
            }
            Text(
                localizedStringResource(message, language),
                modifier = Modifier.testTag("$tagPrefix-import-error"),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (connection == AiProviderConnection.CLAUDE) {
            DropdownSelector(
                items = ImportedCredentialKind.entries,
                selectedItem = credentialKind,
                onItemSelected = {
                    credentialKind = it
                    apiKey = ""
                    configured = runCatching { runtime.providerHasCredential(connection, endpoint, it) }.getOrDefault(false)
                    models = emptyList()
                    modelsLoaded = false
                    modelsError = null
                    if (configured) scope.launch { refreshModels() }
                },
                itemLabel = { localizedStringResource(if (it == ImportedCredentialKind.BEARER_TOKEN) Res.string.source_ai_claude_auth_token else Res.string.source_ai_claude_api_key, language) },
                placeholder = localizedStringResource(Res.string.source_ai_auth_kind, language),
                selectorDescription = localizedStringResource(Res.string.source_ai_auth_kind, language),
                modifier = Modifier.width(320.dp),
                menuModifier = Modifier.width(320.dp),
                fillWidth = true,
                enabled = !working,
            )
        }
        CopyableText(
            localizedStringResource(
                if (configured) Res.string.source_ai_key_configured else Res.string.source_ai_key_storage_notice,
                language,
            ),
        )
        ViewerTextField(
            colors = viewerOutlinedTextFieldColors(),
            value = apiKey,
            onValueChange = {
                apiKey = it
                models = emptyList()
                modelsLoaded = false
                modelsError = null
            },
            modifier = Modifier.width(320.dp).testTag("$tagPrefix-connection-key"),
            enabled = !working,
            label = { Text(localizedStringResource(
                if (connection != AiProviderConnection.CLAUDE) Res.string.source_openai_api_key
                else if (credentialKind == ImportedCredentialKind.BEARER_TOKEN) Res.string.source_ai_claude_auth_token
                else Res.string.source_ai_claude_api_key,
                language,
            )) },
            supportingText = {
                if (!configured && apiKey.isBlank()) {
                    CopyableText(localizedStringResource(if (connection != AiProviderConnection.CLAUDE) Res.string.source_ai_key_required else Res.string.source_ai_credential_required, language), language = language)
                }
            },
            isError = !configured && apiKey.isBlank(),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DropdownSelector(
                items = models,
                selectedItem = model.takeIf(models::contains),
                onItemSelected = { model = it },
                itemLabel = { it },
                placeholder = localizedStringResource(Res.string.source_choose_model, language, models.size),
                selectorDescription = localizedStringResource(Res.string.source_model, language),
                enabled = !working && models.isNotEmpty(),
                modifier = Modifier.weight(1f, fill = false).width(320.dp).testTag("$tagPrefix-model-picker").semantics(mergeDescendants = true) {
                    if (working || models.isEmpty()) disabled()
                },
                menuModifier = Modifier.width(320.dp).heightIn(max = 320.dp).testTag("$tagPrefix-model-menu"),
                fillWidth = true,
            )
            MacOSTextButton(
                label = localizedStringResource(Res.string.source_refresh_models, language),
                modifier = Modifier.testTag("$tagPrefix-model-refresh"),
                enabled = !working && endpoint.isNotBlank() && (configured || apiKey.isNotBlank()),
                onClick = { scope.launch { refreshModels() } },
            )
            if (working) {
                CircularProgressIndicator(Modifier.size(20.dp))
            }
        }
        modelsError?.let { message ->
            CopyableText(
                localizedStringResource(Res.string.source_models_load_failed, language, message),
                modifier = Modifier.testTag("$tagPrefix-model-error"),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                language = language,
            )
        }
        if (modelsLoaded && !working && models.isEmpty() && modelsError == null) {
            CopyableText(
                localizedStringResource(Res.string.source_models_empty, language),
                modifier = Modifier.testTag("$tagPrefix-model-empty"),
                style = MaterialTheme.typography.bodySmall,
                language = language,
            )
        }
        ViewerTextField(
            colors = viewerOutlinedTextFieldColors(),
            value = model,
            onValueChange = { model = it },
            modifier = Modifier.width(320.dp).testTag("$tagPrefix-connection-model"),
            enabled = !working,
            label = { Text(localizedStringResource(Res.string.source_model, language)) },
            singleLine = true,
        )
        ViewerTextField(
            colors = viewerOutlinedTextFieldColors(),
            value = endpoint,
            onValueChange = {
                endpoint = it
                configured = false
                modelsEndpoint = ""
                models = emptyList()
                modelsLoaded = false
                modelsError = null
            },
            modifier = Modifier.width(320.dp).testTag("$tagPrefix-connection-endpoint"),
            enabled = !working,
            label = { Text(localizedStringResource(Res.string.source_endpoint, language)) },
            singleLine = true,
        )
        if (connection != AiProviderConnection.CLAUDE) {
            ViewerTextField(
                colors = viewerOutlinedTextFieldColors(),
                value = modelsEndpoint,
                onValueChange = {
                    modelsEndpoint = it
                    models = emptyList()
                    modelsLoaded = false
                    modelsError = null
                },
                modifier = Modifier.width(320.dp).testTag("$tagPrefix-connection-models-endpoint"),
                enabled = !working,
                textStyle = MaterialTheme.typography.bodyMedium,
                label = { Text(localizedStringResource(Res.string.source_ai_models_endpoint, language)) },
                supportingText = {
                    Text(
                        localizedStringResource(Res.string.source_ai_models_endpoint_hint, language),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                singleLine = true,
            )
        }
        MacOSTextButton(
            label = localizedStringResource(Res.string.source_ai_save_apply, language),
            modifier = Modifier.testTag("$tagPrefix-connection-save"),
            enabled = !working && model.isNotBlank() && endpoint.isNotBlank() && (configured || apiKey.isNotBlank()),
            onClick = {
                saveError = null
                working = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            var savedRevision: String? = null
                            runtime.saveAndApplyProviderConnection(
                                connection,
                                model.trim(),
                                endpoint.trim(),
                                modelsEndpoint.trim(),
                                credentialKind,
                                apiKey.trim().takeIf(String::isNotBlank),
                            ) {
                                settingsStore.update { it.copy(providerId = connection.providerId, model = model.trim()) }
                                savedRevision = runtime.providerConnectionRevision(connection)
                            }
                            if (modelsLoaded && modelsError == null) {
                                // Cache publication must not turn a committed save into a reported failure.
                                runCatching { runtime.cacheProviderModels(connection, models, expectedRevision = savedRevision) }
                            }
                        }
                        configured = true
                        apiKey = ""
                        imported = false
                    } catch (failure: kotlinx.coroutines.CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        saveError = "E_AI_CONNECTION_SAVE"
                    } finally {
                        working = false
                    }
                }
            },
        )
        saveError?.let {
            CopyableText(
                localizedStringResource(Res.string.source_ai_save_failed, language),
                modifier = Modifier.testTag("$tagPrefix-save-error"),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                language = language,
            )
        }
    }
}

private fun modelCatalogFailureMessage(failure: Exception, language: UiLanguage, connection: AiProviderConnection): String {
    val chinese = language == UiLanguage.SIMPLIFIED_CHINESE
    fun text(en: String, zh: String): String = if (chinese) zh else en
    return when (failure) {
        is AiModelCredentialException -> if (failure.endpointMismatch) {
            text("The endpoint changed. Enter a key for this connection before refreshing models.", "接口地址已更改，请先填写此连接的密钥，再刷新模型。")
        } else {
            text("Enter an API key before refreshing models.", "请先填写 API Key，再刷新模型。")
        }
        is AiModelCatalogException -> when (failure.reason) {
            AiModelCatalogFailure.HTML_RESPONSE -> if (connection == AiProviderConnection.CLAUDE) text(
                "The server returned a web page instead of a model list. Check that this gateway supports the Claude model list API, or enter a model manually.",
                "服务器返回了网页，而不是模型列表。请确认网关支持 Claude 模型列表接口，也可以手动填写模型。",
            ) else text(
                "The server returned a web page instead of a model list. Check the gateway's model list URL and access permissions. Keep the Responses URL unchanged, or enter a model manually.",
                "服务器返回了网页，而不是模型列表。请检查网关独立的模型列表地址及访问权限；请保留原 Responses 地址，也可以手动填写模型。",
            )
            AiModelCatalogFailure.INVALID_RESPONSE -> text(
                "The server response is not a valid model list. Check that this connection supports the provider's model list API.",
                "服务器响应不是有效的模型列表，请确认该连接支持对应提供方的模型列表接口。",
            )
            AiModelCatalogFailure.INVALID_ENDPOINT -> text(
                "The API or model list address is invalid. Both must use the same origin and HTTPS, or an exact loopback address for local development.",
                "API 地址或模型列表地址无效。两者必须同源，并使用 HTTPS（本地回环地址除外）。",
            )
        }
        is AiRequestException -> when (failure.kind) {
            AiRequestFailureKind.AUTHENTICATION -> text("Authentication failed. Check the API key and connection permissions.", "认证失败，请检查 API Key 和连接权限。")
            AiRequestFailureKind.RATE_LIMIT -> text("The server rate limit was reached. Try again later.", "请求已达到服务器限额，请稍后重试。")
            AiRequestFailureKind.TIMEOUT -> text("The model request timed out. Check the connection and try again.", "模型请求超时，请检查连接后重试。")
            AiRequestFailureKind.NETWORK -> text("The server could not be reached. Check the network and API address.", "无法连接服务器，请检查网络和 API 地址。")
            AiRequestFailureKind.HTTP -> text("The server rejected the model request. Check the API address and gateway status.", "服务器拒绝了模型请求，请检查 API 地址和网关状态。")
        }
        else -> text("The model list could not be loaded safely. Check the connection and try again.", "无法安全读取模型列表，请检查连接后重试。")
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
        SettingsChoice(
            language = language,
            label = localizedStringResource(Res.string.cpu_analysis_engine, language),
            current = settings.cpuAnalysisEngine,
            options = CpuAnalysisEngine.entries,
            optionLabel = { engine ->
                localizedStringResource(
                    when (engine) {
                        CpuAnalysisEngine.LEGACY -> Res.string.cpu_engine_legacy
                        CpuAnalysisEngine.RUST -> Res.string.cpu_engine_rust_projection
                    },
                    language,
                )
            },
            onSelected = { onSettingsChanged(settings.copy(cpuAnalysisEngine = it)) },
        )
        SettingsChoice(
            language = language,
            label = localizedStringResource(Res.string.har_import_engine, language),
            current = settings.harImportEngine,
            options = HarImportEngine.entries,
            optionLabel = { engine ->
                localizedStringResource(
                    when (engine) {
                        HarImportEngine.LEGACY -> Res.string.har_engine_legacy
                        HarImportEngine.RUST -> Res.string.har_engine_rust
                    },
                    language,
                )
            },
            onSelected = { onSettingsChanged(settings.copy(harImportEngine = it)) },
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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
        ApplicationThemeColor.entries.chunked(THEME_SWATCH_COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
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
    ViewerTextField(
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
        ViewerTextField(
            colors = viewerOutlinedTextFieldColors(),
            value = draftPath,
            onValueChange = { draftPath = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(localizedStringResource(Res.string.android_sdk_path, language)) },
            supportingText = { CopyableText(localizedStringResource(Res.string.android_sdk_path_hint, language), language = language) },
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
            verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
        ) {
            items(CaptureSettingsSection.entries, key = CaptureSettingsSection::name) { section ->
                StudioPanel(modifier = Modifier.fillMaxWidth(), contentPadding = StudioTokens.workspacePadding) {
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


internal const val UNIFIED_SETTINGS_SIDEBAR_WIDTH_DP = 176

private val SETTINGS_SIDEBAR_ICON_SIZE = 16.dp
