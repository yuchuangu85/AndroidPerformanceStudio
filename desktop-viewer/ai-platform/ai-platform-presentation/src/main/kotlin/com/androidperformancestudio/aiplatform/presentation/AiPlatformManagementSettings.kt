@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package com.androidperformancestudio.aiplatform.presentation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.aiplatform.app.AiPlatformRuntime
import com.androidperformancestudio.aiplatform.app.AiPlatformSettings
import com.androidperformancestudio.aiplatform.app.AiPlatformSettingsStore
import com.androidperformancestudio.aiplatform.model.ExtensionKind
import com.androidperformancestudio.aiplatform.model.ExtensionOrigin
import com.androidperformancestudio.aiplatform.model.TrustStatus
import com.androidperformancestudio.aiplatform.registry.ExtensionKey
import com.androidperformancestudio.aiplatform.registry.RegistryEntry
import com.androidperformancestudio.aiplatform.registry.RegistryException
import com.androidperformancestudio.ui.CopyableText
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTextField
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.studio.StudioCard
import com.androidperformancestudio.ui.studio.StudioTokens
import com.androidperformancestudio.ui.viewerOutlinedTextFieldColors
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowDownload
import io.github.composefluent.icons.regular.Delete
import io.github.composefluent.icons.regular.FolderOpen
import io.github.composefluent.icons.regular.History
import io.github.composefluent.icons.regular.ShieldCheckmark
import io.github.composefluent.icons.regular.ShieldDismiss
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

public enum class AiManagementSection { AGENTS, SKILLS, POLICY }

/** Management only: opening a section never prepares, approves or executes an AI run. */
@Composable
public fun AiPlatformManagementSettings(
    runtime: AiPlatformRuntime?,
    section: AiManagementSection,
    language: UiLanguage,
    modifier: Modifier = Modifier,
    settingsStoreOverride: AiPlatformSettingsStore? = null,
) {
    if (runtime == null) {
        Column(modifier.fillMaxSize().padding(StudioTokens.workspacePadding)) {
            StudioCard(Modifier.fillMaxWidth().testTag("ai-management-card-unavailable")) {
                Text(
                    managementLabel(language, "AI runtime unavailable. Management is not ready.", "AI 运行时不可用，暂时无法管理。"),
                    modifier = Modifier.testTag("ai-management-unavailable"),
                    color = LocalViewerColors.current.secondaryText,
                    style = ViewerTypography.body,
                )
            }
        }
        return
    }
    val settingsStore = remember(settingsStoreOverride) { settingsStoreOverride ?: AiPlatformSettingsStore() }
    val settings by settingsStore.settings.collectAsState()
    var saveError by remember(settingsStore) { mutableStateOf(false) }

    fun save(update: (AiPlatformSettings) -> AiPlatformSettings) {
        saveError = false
        try {
            // Read at the action boundary so a different settings window's unrelated fields survive.
            settingsStore.update(update)
        } catch (_: Exception) {
            saveError = true
        }
    }

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(StudioTokens.compactContentPadding)) {
        if (saveError) {
            CopyableText("E_AI_SETTINGS_SAVE", color = MaterialTheme.colorScheme.error, style = ViewerTypography.body, language = language)
        }
        key(runtime, section) {
            when (section) {
                AiManagementSection.AGENTS, AiManagementSection.SKILLS ->
                    ManagementExtensions(
                        runtime,
                        if (section == AiManagementSection.AGENTS) ExtensionKind.AGENT else ExtensionKind.SKILL,
                        settings.agentId,
                        language,
                        Modifier.weight(1f),
                        onSelectAgent = { id -> save { it.copy(agentId = id) } },
                    )
                AiManagementSection.POLICY -> ManagementPolicy(runtime, settings, language, Modifier.weight(1f)) { update -> save(update) }
            }
        }
    }
}

private data class ManagementRegistrySnapshot(
    val entries: List<RegistryEntry>,
    val selectedVersions: Set<ExtensionKey>,
    val issues: List<String>,
)

private fun managementRegistrySnapshot(
    runtime: AiPlatformRuntime,
    kind: ExtensionKind,
): ManagementRegistrySnapshot {
    val scan = runtime.registry.scan()
    val entries =
        scan.entries.filter { it.key.kind == kind }.sortedWith(
            compareBy({ it.key.id }, { it.key.version }, { it.key.origin.name }),
        )
    val selections =
        entries
            .map { it.key.id }
            .distinct()
            .mapNotNull { id ->
                try {
                    runtime.registry.resolve(kind, id).key
                } catch (_: RegistryException) {
                    // Ambiguous or deleted selections remain visibly unselected; never guess a version.
                    null
                }
            }.toSet()
    return ManagementRegistrySnapshot(entries, selections, scan.issues.map { it.code }.distinct())
}

@Composable
private fun ManagementExtensions(
    runtime: AiPlatformRuntime,
    kind: ExtensionKind,
    selectedAgentId: String,
    language: UiLanguage,
    modifier: Modifier,
    onSelectAgent: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<ManagementRegistrySnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var importPath by remember { mutableStateOf("") }
    val packageType = if (kind == ExtensionKind.AGENT) "Agent" else "Skill"

    fun action(
        onSuccess: () -> Unit = {},
        block: () -> Unit,
    ) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                snapshot =
                    withContext(Dispatchers.IO) {
                        block()
                        managementRegistrySnapshot(runtime, kind)
                    }
                onSuccess()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = managementErrorCode(failure)
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(runtime, kind) {
        try {
            snapshot = withContext(Dispatchers.IO) { managementRegistrySnapshot(runtime, kind) }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            error = managementErrorCode(failure)
        } finally {
            busy = false
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(
                rememberScrollState(),
            ).testTag("ai-management-extensions-${kind.name.lowercase()}")
            .padding(StudioTokens.workspacePadding),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
    ) {
        StudioCard(Modifier.fillMaxWidth().testTag("ai-management-card-import-${kind.name.lowercase()}")) {
            Text(
                managementLabel(language, "Import $packageType", "导入 $packageType"),
                color = LocalViewerColors.current.accent,
                style = ViewerTypography.subsectionTitle,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ViewerTextField(
                    value = importPath,
                    onValueChange = { importPath = it },
                    modifier = Modifier.weight(1f).testTag("ai-management-import-path"),
                    colors = viewerOutlinedTextFieldColors(),
                    enabled = !busy,
                    textStyle = ViewerTypography.body,
                    label = { Text(managementLabel(language, "Package directory", "包目录"), style = ViewerTypography.secondary) },
                    singleLine = true,
                )
                ManagementIconAction(
                    Icons.Regular.FolderOpen,
                    managementLabel(language, "Choose package directory", "选择包目录"),
                    "ai-management-browse",
                    !busy,
                ) {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val selected =
                                withContext(Dispatchers.IO) {
                                    var path: String? = null
                                    SwingUtilities.invokeAndWait {
                                        val chooser =
                                            JFileChooser().apply {
                                                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                            }
                                        if (chooser.showOpenDialog(null) ==
                                            JFileChooser.APPROVE_OPTION
                                        ) {
                                            path = chooser.selectedFile.absolutePath
                                        }
                                    }
                                    path
                                }
                            selected?.let { importPath = it }
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Exception) {
                            error = "E_EXTENSION_ACTION"
                        } finally {
                            busy = false
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    val path = importPath
                    action { runtime.registry.install(Path.of(path)) }
                },
                enabled = !busy && importPath.isNotBlank(),
                modifier = Modifier.testTag("ai-management-import"),
            ) {
                Icon(Icons.Regular.ArrowDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    managementLabel(language, "Import verified package directory", "导入可校验的包目录"),
                    Modifier.padding(start = StudioTokens.compactContentPadding),
                    style = ViewerTypography.bodyCompact,
                )
            }
            if (busy) Text(managementLabel(language, "Loading packages", "正在加载包"), style = ViewerTypography.secondary)
            error?.let {
                CopyableText(
                    it,
                    modifier = Modifier.testTag("ai-management-extension-error"),
                    color = MaterialTheme.colorScheme.error,
                    style = ViewerTypography.body,
                    language = language,
                )
            }
            snapshot?.issues?.forEach {
                CopyableText(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = ViewerTypography.body,
                    language = language,
                )
            }
            if (snapshot?.entries?.isEmpty() ==
                true
            ) {
                Text(managementLabel(language, "No packages installed", "尚未安装包"), style = ViewerTypography.body)
            }
        }
        snapshot?.entries?.forEach { entry ->
            val token = "${entry.key.id}-${entry.key.version}-${entry.key.origin.name.lowercase()}"
            StudioCard(Modifier.fillMaxWidth().testTag("ai-management-card-package-$token")) {
                Text(
                    "${entry.key.id} @ ${entry.key.version}",
                    color = LocalViewerColors.current.accent,
                    style = ViewerTypography.subsectionTitle,
                )
                Text(
                    "${entry.key.origin} / ${entry.trustStatus}",
                    modifier = Modifier.testTag("ai-management-status-$token"),
                    color = LocalViewerColors.current.secondaryText,
                    style = ViewerTypography.secondary,
                )
                CopyableText("SHA256 ${entry.contentHash}", style = ViewerTypography.secondary, language = language)
                if (kind == ExtensionKind.AGENT) {
                    val chatOnly = entry.manifest.contextKinds == setOf("chat")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = entry.key.id == selectedAgentId && entry.key in snapshot?.selectedVersions.orEmpty(),
                            onClick = { action(onSuccess = { onSelectAgent(entry.key.id) }) { runtime.registry.selectVersion(entry.key) } },
                            enabled = !busy && entry.usable && !chatOnly,
                            modifier = Modifier.testTag("ai-management-select-agent-$token"),
                        )
                        Text(
                            if (chatOnly) {
                                managementLabel(
                                    language,
                                    "Chat only (automatic)",
                                    "普通对话专用（自动使用）",
                                )
                            } else {
                                managementLabel(language, "Active agent", "当前 Agent")
                            },
                            style = ViewerTypography.bodyCompact,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = entry.key in snapshot?.selectedVersions.orEmpty(),
                        onClick = { action { runtime.registry.selectVersion(entry.key) } },
                        enabled = !busy,
                        modifier = Modifier.testTag("ai-management-version-$token"),
                    )
                    Text(managementLabel(language, "Selected version", "已选版本"), style = ViewerTypography.bodyCompact)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = entry.enabled,
                        onCheckedChange = { enabled ->
                            action { if (enabled) runtime.registry.enable(entry.key) else runtime.registry.disable(entry.key) }
                        },
                        enabled = !busy && entry.trustStatus == TrustStatus.TRUSTED,
                        modifier = Modifier.testTag("ai-management-enabled-$token"),
                    )
                    Text(managementLabel(language, "Enabled", "启用"), Modifier.weight(1f), style = ViewerTypography.bodyCompact)
                    ManagementIconAction(
                        Icons.Regular.ShieldCheckmark,
                        managementLabel(language, "Trust verified hash", "信任已校验哈希"),
                        "ai-management-trust-$token",
                        !busy && entry.trustStatus == TrustStatus.UNTRUSTED,
                    ) { action { runtime.registry.trust(entry.key) } }
                    ManagementIconAction(
                        Icons.Regular.ShieldDismiss,
                        managementLabel(language, "Revoke", "撤销"),
                        "ai-management-revoke-$token",
                        !busy && entry.trustStatus != TrustStatus.REVOKED,
                    ) { action { runtime.registry.revoke(entry.key) } }
                    if (entry.key.origin == ExtensionOrigin.IMPORTED) {
                        ManagementIconAction(
                            Icons.Regular.Delete,
                            managementLabel(language, "Delete imported version", "删除导入版本"),
                            "ai-management-delete-$token",
                            !busy,
                        ) {
                            action { runtime.registry.deleteImported(entry.key) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManagementPolicy(
    runtime: AiPlatformRuntime,
    settings: AiPlatformSettings,
    language: UiLanguage,
    modifier: Modifier,
    onChange: ((AiPlatformSettings) -> AiPlatformSettings) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var retaining by remember { mutableStateOf(false) }
    var removed by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(StudioTokens.workspacePadding),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
    ) {
        StudioCard(Modifier.fillMaxWidth().testTag("ai-management-card-permissions")) {
            Text(
                managementLabel(language, "Execution permissions", "执行权限"),
                color = LocalViewerColors.current.accent,
                style = ViewerTypography.subsectionTitle,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(settings.allowArtifacts, { value ->
                    onChange {
                        it.copy(allowArtifacts = value)
                    }
                }, modifier = Modifier.testTag("ai-management-allow-artifacts"))
                Text(
                    managementLabel(language, "Allow bounded local report artifacts after confirmation", "确认后允许写入有界本地报告"),
                    Modifier.weight(1f),
                    style = ViewerTypography.bodyCompact,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(settings.allowScripts, { value ->
                    onChange {
                        it.copy(allowScripts = value)
                    }
                }, modifier = Modifier.testTag("ai-management-allow-scripts"))
                Text(
                    managementLabel(language, "Allow registered scripts after sandbox preflight and confirmation", "沙箱预检和确认后允许注册脚本"),
                    Modifier.weight(1f),
                    style = ViewerTypography.bodyCompact,
                )
            }
            Text(
                managementLabel(
                    language,
                    "Source snippets require live workspace authorization. Scripts require a qualified sandbox; " +
                        "unavailable backends never fall back to ordinary shell execution.",
                    "源码片段需要工作区实时授权。脚本必须有通过验证的沙箱，不可用时不会回退普通 Shell。",
                ),
                style = ViewerTypography.secondary,
                color = LocalViewerColors.current.secondaryText,
            )
        }
        StudioCard(Modifier.fillMaxWidth().testTag("ai-management-card-history")) {
            Text(
                managementLabel(language, "Conversation history", "会话历史"),
                color = LocalViewerColors.current.accent,
                style = ViewerTypography.subsectionTitle,
            )
            OutlinedButton(
                onClick = {
                    retaining = true
                    removed = null
                    error = false
                    scope.launch {
                        try {
                            removed =
                                withContext(Dispatchers.IO) { runtime.service.retain(100, System.currentTimeMillis() - 30L * 86_400_000) }
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Exception) {
                            error = true
                        } finally {
                            retaining = false
                        }
                    }
                },
                enabled = !retaining,
                modifier = Modifier.testTag("ai-management-retain-history"),
            ) {
                Icon(Icons.Regular.History, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    managementLabel(language, "Retain at most 100 conversations / 30 days", "仅保留最近 30 天、最多 100 个会话"),
                    Modifier.weight(1f, fill = false).padding(start = StudioTokens.compactContentPadding),
                    style = ViewerTypography.bodyCompact,
                )
            }
            removed?.let {
                Text(
                    managementLabel(language, "Removed conversations: $it", "已移除会话：$it"),
                    Modifier.testTag("ai-management-retention-result"),
                    style = ViewerTypography.body,
                )
            }
            if (error) {
                CopyableText(
                    "E_AI_HISTORY_RETENTION",
                    color = MaterialTheme.colorScheme.error,
                    style = ViewerTypography.body,
                    language = language,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ManagementIconAction(
    icon: ImageVector,
    title: String,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TooltipArea(tooltip = {
        Surface { Text(title, Modifier.padding(StudioTokens.compactContentPadding), style = ViewerTypography.secondary) }
    }) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp).testTag(tag)) {
            Icon(icon, contentDescription = title, modifier = Modifier.size(18.dp))
        }
    }
}

private fun managementErrorCode(failure: Exception): String =
    (failure as? RegistryException)?.code?.takeIf { it.matches(Regex("E_[A-Z0-9_]{1,80}")) } ?: "E_EXTENSION_ACTION"

private fun managementLabel(
    language: UiLanguage,
    en: String,
    zh: String,
): String = if (language == UiLanguage.SIMPLIFIED_CHINESE) zh else en
