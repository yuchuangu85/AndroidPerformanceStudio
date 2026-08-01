package com.androidperformancestudio.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.desktop_app.generated.resources.*
import com.androidperformancestudio.desktop_app.generated.resources.Res
import com.androidperformancestudio.source.ResolutionConfidence
import com.androidperformancestudio.source.SourceProviderConfig
import com.androidperformancestudio.source.SourceLocation
import com.androidperformancestudio.source.SourceProviderKind
import com.androidperformancestudio.source.SourceSnapshotId
import com.androidperformancestudio.source.SourceWorkspace
import com.androidperformancestudio.source.SourceWorkspacePhase
import com.androidperformancestudio.ui.PROFILER_PRIMARY_TOOLBAR_HEIGHT_DP
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.nio.file.Path
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class RemoteWorkspaceDialog {
    GITHUB,
    AOSP,
}

private val READY_SUMMARY_PATTERN = Regex("""(\d+) files · (\d+) symbols""")

@Composable
internal fun SourceWorkspacesPage(
    language: UiLanguage,
    runtime: SourceWorkspaceRuntime,
    initialLocation: SourceLocation? = null,
    onNavigateHome: () -> Unit,
) {
    val workspaces by runtime.service.workspaces.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedWorkspaceId by remember { mutableStateOf(initialLocation?.workspaceId) }
    var selectedFile by remember { mutableStateOf<String?>(null) }
    var selectedLocation by remember { mutableStateOf(initialLocation) }
    var sourceText by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<RemoteWorkspaceDialog?>(null) }
    var showAiSettings by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialLocation) {
        initialLocation?.let { location ->
            selectedWorkspaceId = location.workspaceId
            selectedFile = location.relativePath
            selectedLocation = location
            sourceText = null
            runCatching { withContext(Dispatchers.IO) { runtime.service.read(location).text } }
                .onSuccess { sourceText = it }
                .onFailure { pageError = it.message }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).height(PROFILER_PRIMARY_TOOLBAR_HEIGHT_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onNavigateHome) { Text(localizedStringResource(Res.string.home, language)) }
                Text(
                    text = localizedStringResource(Res.string.source_workspaces, language),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showAiSettings = true }) {
                    Text(localizedStringResource(Res.string.source_ai_settings, language))
                }
                Button(onClick = {
                    chooseDirectory(localizedStringResource(Res.string.source_choose_local_directory, language))?.let { root ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runtime.service.add(
                                    root.fileName?.toString() ?: root.toString(),
                                    SourceProviderConfig.Local(root),
                                )
                            }
                        }
                    }
                }) { Text(localizedStringResource(Res.string.source_add_local, language)) }
                Button(onClick = { dialog = RemoteWorkspaceDialog.GITHUB }) {
                    Text(localizedStringResource(Res.string.source_add_github, language))
                }
                Button(onClick = { dialog = RemoteWorkspaceDialog.AOSP }) {
                    Text(localizedStringResource(Res.string.source_add_aosp, language))
                }
            }
            HorizontalDivider()
            pageError?.let { message ->
                Text(
                    localizedStringResource(Res.string.source_operation_failed, language, message),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.width(360.dp).fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (workspaces.isEmpty()) {
                        item {
                            Text(
                                localizedStringResource(Res.string.source_empty_hint, language),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(workspaces, key = { it.id.value }) { workspace ->
                        WorkspaceCard(
                            language = language,
                            workspace = workspace,
                            selected = selectedWorkspaceId == workspace.id,
                            onSelect = {
                                selectedWorkspaceId = workspace.id
                                selectedFile = null
                                selectedLocation = null
                                sourceText = null
                            },
                            onRefresh = { scope.launch { withContext(Dispatchers.IO) { runtime.service.refresh(workspace.id) } } },
                            onToggleAiUpload = {
                                runtime.service.setAiSourceUploadAllowed(workspace.id, !workspace.allowAiSourceUpload)
                            },
                            onRemove = {
                                runtime.service.remove(workspace.id)
                                if (selectedWorkspaceId == workspace.id) selectedWorkspaceId = null
                            },
                        )
                    }
                }
                VerticalDivider()
                val active = workspaces.firstOrNull { it.id == selectedWorkspaceId }
                if (active?.activeSnapshotId == null) {
                    Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(localizedStringResource(Res.string.source_select_indexed_workspace, language))
                    }
                } else {
                    val activeSnapshotId = requireNotNull(active.activeSnapshotId)
                    SourceBrowser(
                        language = language,
                        snapshotId = activeSnapshotId,
                        runtime = runtime,
                        selectedFile = selectedFile,
                        sourceText = sourceText,
                        selectedLocation = selectedLocation,
                        workspace = active,
                        onOpen = { path ->
                            selectedFile = path
                            val file = runtime.repository.files(activeSnapshotId).first { it.relativePath == path }
                            selectedLocation = SourceLocation(active.id, activeSnapshotId, path, null, file.contentHash)
                            scope.launch {
                                sourceText = null
                                runCatching {
                                    withContext(Dispatchers.IO) { runtime.service.read(
                                        com.androidperformancestudio.source.SourceLocation(
                                            active.id,
                                            activeSnapshotId,
                                            path,
                                            null,
                                            file.contentHash,
                                        ),
                                    ).text }
                                }.onSuccess { sourceText = it }.onFailure { pageError = it.message }
                            }
                        },
                    )
                }
            }
        }
    }

    if (showAiSettings) {
        AiCredentialDialog(
            language = language,
            configured = runtime.credential("openai:api-key") != null || !System.getenv("OPENAI_API_KEY").isNullOrBlank(),
            currentModel = runtime.aiModel(),
            currentEndpoint = runtime.aiEndpoint(),
            onDismiss = { showAiSettings = false },
            onSave = { apiKey, model, endpoint ->
                apiKey.takeIf(String::isNotBlank)?.let { runtime.saveCredential("openai:api-key", it) }
                runtime.saveAiConfiguration(model, endpoint)
                showAiSettings = false
            },
        )
    }

    dialog?.let { kind ->
        AddRemoteWorkspaceDialog(
            language = language,
            kind = kind,
            onDismiss = { dialog = null },
            onAdd = { name, config, credential ->
                dialog = null
                scope.launch {
                    runCatching {
                        credential?.let { (key, value) -> runtime.saveCredential(key, value) }
                        withContext(Dispatchers.IO) { runtime.service.add(name, config) }
                    }.onFailure { pageError = it.message }
                }
            },
        )
    }
}

@Composable
private fun WorkspaceCard(
    language: UiLanguage,
    workspace: SourceWorkspace,
    selected: Boolean,
    onSelect: () -> Unit,
    onRefresh: () -> Unit,
    onToggleAiUpload: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(workspace.displayName, fontWeight = FontWeight.SemiBold)
            Text(
                localizedStringResource(
                    Res.string.source_status_summary,
                    language,
                    workspace.config.kind.localizedLabel(language),
                    workspace.phase.localizedLabel(language),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (workspace.phase !in setOf(SourceWorkspacePhase.READY, SourceWorkspacePhase.FAILED)) {
                LinearProgressIndicator(progress = { workspace.progress }, modifier = Modifier.fillMaxWidth())
            }
            workspace.localizedMessage(language)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onRefresh) {
                    Text(localizedStringResource(Res.string.source_sync, language))
                }
                TextButton(onClick = onToggleAiUpload) {
                    Text(
                        localizedStringResource(
                            if (workspace.allowAiSourceUpload) {
                                Res.string.source_ai_upload_allowed
                            } else {
                                Res.string.source_ai_upload_blocked
                            },
                            language,
                        ),
                    )
                }
                TextButton(onClick = onRemove) {
                    Text(localizedStringResource(Res.string.source_remove, language))
                }
            }
        }
    }
}

@Composable
private fun SourceBrowser(
    language: UiLanguage,
    snapshotId: SourceSnapshotId,
    runtime: SourceWorkspaceRuntime,
    selectedFile: String?,
    sourceText: String?,
    selectedLocation: SourceLocation?,
    workspace: SourceWorkspace,
    onOpen: (String) -> Unit,
) {
    val files = remember(snapshotId) { runtime.repository.files(snapshotId) }
    Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.width(320.dp).fillMaxSize().padding(8.dp)) {
            items(files, key = { it.relativePath }) { file ->
                TextButton(onClick = { onOpen(file.relativePath) }, modifier = Modifier.fillMaxWidth()) {
                    Text(file.relativePath, modifier = Modifier.fillMaxWidth(), maxLines = 2)
                }
            }
        }
        VerticalDivider()
        Column(Modifier.weight(1f).fillMaxSize().padding(12.dp)) {
            val snapshot = workspace.activeSnapshotId?.let(runtime.repository::snapshot)
            val candidate = selectedLocation?.let(runtime::candidate)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildString {
                        append(selectedFile ?: localizedStringResource(Res.string.source_select_file, language))
                        selectedLocation?.range?.let { append(":").append(it.startLine) }
                    },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                selectedLocation?.let { location ->
                    TextButton(onClick = { copyLocation(workspace, snapshot?.immutableRevision, location) }) {
                        Text(localizedStringResource(Res.string.source_copy_location, language))
                    }
                    TextButton(onClick = { openExternal(workspace, snapshot?.immutableRevision, location) }) {
                        Text(
                            localizedStringResource(
                                if (workspace.config is SourceProviderConfig.Local) {
                                    Res.string.source_open_in_ide
                                } else {
                                    Res.string.source_open_online
                                },
                                language,
                            ),
                        )
                    }
                }
            }
            snapshot?.let {
                Text(
                    localizedStringResource(
                        Res.string.source_snapshot_summary,
                        language,
                        workspace.config.kind.localizedLabel(language),
                        it.immutableRevision.take(16),
                        candidate?.confidence?.localizedLabel(language)
                            ?: localizedStringResource(Res.string.source_indexed, language),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            candidate?.reasons?.takeIf { it.isNotEmpty() }?.let { reasons ->
                Text(
                    reasons.joinToString(" · ") { it.localizedResolutionReason(language) },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            when {
                selectedFile == null -> Unit
                sourceText == null -> CircularProgressIndicator()
                else -> Text(
                    sourceText,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

private fun copyLocation(
    workspace: SourceWorkspace,
    revision: String?,
    location: SourceLocation,
) {
    val value = "${workspace.displayName}@${revision.orEmpty()}:${location.relativePath}:${location.range?.startLine ?: 1}"
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
}

private fun openExternal(
    workspace: SourceWorkspace,
    revision: String?,
    location: SourceLocation,
) {
    if (!Desktop.isDesktopSupported()) return
    when (val config = workspace.config) {
        is SourceProviderConfig.Local -> Desktop.getDesktop().open(config.root.resolve(location.relativePath).toFile())
        is SourceProviderConfig.GitHub -> Desktop.getDesktop().browse(
            URI.create("https://github.com/${config.owner}/${config.repository}/blob/${revision ?: config.ref}/${location.relativePath}#L${location.range?.startLine ?: 1}"),
        )
        is SourceProviderConfig.Aosp -> Desktop.getDesktop().browse(
            URI.create("https://android.googlesource.com/${config.project}/+/${revision ?: config.ref}/${location.relativePath}#${location.range?.startLine ?: 1}"),
        )
    }
}

@Composable
private fun AiCredentialDialog(
    language: UiLanguage,
    configured: Boolean,
    currentModel: String,
    currentEndpoint: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(currentModel) }
    var endpoint by remember { mutableStateOf(currentEndpoint) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localizedStringResource(Res.string.source_ai_settings, language)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (configured) {
                        localizedStringResource(Res.string.source_ai_key_configured, language)
                    } else {
                        localizedStringResource(Res.string.source_ai_key_storage_notice, language)
                    },
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(localizedStringResource(Res.string.source_openai_api_key, language)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(localizedStringResource(Res.string.source_model, language)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text(localizedStringResource(Res.string.source_endpoint, language)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = model.isNotBlank() && endpoint.isNotBlank() && (configured || apiKey.isNotBlank()),
                onClick = { onSave(apiKey.trim(), model.trim(), endpoint.trim()) },
            ) {
                Text(localizedStringResource(Res.string.source_save, language))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(localizedStringResource(Res.string.source_cancel, language))
            }
        },
    )
}

@Composable
private fun AddRemoteWorkspaceDialog(
    language: UiLanguage,
    kind: RemoteWorkspaceDialog,
    onDismiss: () -> Unit,
    onAdd: (String, SourceProviderConfig, Pair<String, String>?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf("main") }
    var token by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && first.isNotBlank() && ref.isNotBlank() && (kind != RemoteWorkspaceDialog.GITHUB || second.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                localizedStringResource(
                    if (kind == RemoteWorkspaceDialog.GITHUB) {
                        Res.string.source_add_github_workspace
                    } else {
                        Res.string.source_add_aosp_workspace
                    },
                    language,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text(localizedStringResource(Res.string.source_name, language)) },
                )
                OutlinedTextField(
                    first,
                    { first = it },
                    label = {
                        Text(
                            localizedStringResource(
                                if (kind == RemoteWorkspaceDialog.GITHUB) {
                                    Res.string.source_owner
                                } else {
                                    Res.string.source_aosp_project
                                },
                                language,
                            ),
                        )
                    },
                )
                if (kind == RemoteWorkspaceDialog.GITHUB) {
                    OutlinedTextField(
                        second,
                        { second = it },
                        label = { Text(localizedStringResource(Res.string.source_repository, language)) },
                    )
                    OutlinedTextField(
                        token,
                        { token = it },
                        label = { Text(localizedStringResource(Res.string.source_token_optional, language)) },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                OutlinedTextField(
                    ref,
                    { ref = it },
                    label = { Text(localizedStringResource(Res.string.source_revision_hint, language)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    if (kind == RemoteWorkspaceDialog.GITHUB) {
                        val credentialKey = "github:${first.trim()}/${second.trim()}"
                        onAdd(
                            name.trim(),
                            SourceProviderConfig.GitHub(first.trim(), second.trim(), ref.trim(), credentialKey),
                            token.takeIf(String::isNotBlank)?.let { credentialKey to it },
                        )
                    } else {
                        onAdd(name.trim(), SourceProviderConfig.Aosp(first.trim(), ref.trim()), null)
                    }
                },
            ) { Text(localizedStringResource(Res.string.source_add, language)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(localizedStringResource(Res.string.source_cancel, language))
            }
        },
    )
}

private fun chooseDirectory(title: String): Path? {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}

private fun SourceProviderKind.localizedLabel(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            SourceProviderKind.LOCAL -> Res.string.source_provider_local
            SourceProviderKind.GITHUB -> Res.string.source_provider_github
            SourceProviderKind.AOSP -> Res.string.source_provider_aosp
        },
        language,
    )

private fun SourceWorkspacePhase.localizedLabel(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            SourceWorkspacePhase.REGISTERING -> Res.string.source_phase_registering
            SourceWorkspacePhase.RESOLVING_REVISION -> Res.string.source_phase_resolving_revision
            SourceWorkspacePhase.BUILDING_MANIFEST -> Res.string.source_phase_building_manifest
            SourceWorkspacePhase.INDEXING -> Res.string.source_phase_indexing
            SourceWorkspacePhase.READY -> Res.string.source_phase_ready
            SourceWorkspacePhase.PARTIAL -> Res.string.source_phase_partial
            SourceWorkspacePhase.FAILED -> Res.string.source_phase_failed
        },
        language,
    )

private fun ResolutionConfidence.localizedLabel(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            ResolutionConfidence.EXACT -> Res.string.source_confidence_exact
            ResolutionConfidence.PROBABLE -> Res.string.source_confidence_probable
            ResolutionConfidence.WEAK -> Res.string.source_confidence_weak
        },
        language,
    )

private fun SourceWorkspace.localizedMessage(language: UiLanguage): String? {
    val value = message ?: return null
    val readySummary = READY_SUMMARY_PATTERN.matchEntire(value) ?: return value
    return localizedStringResource(
        Res.string.source_ready_summary,
        language,
        readySummary.groupValues[1].toLong(),
        readySummary.groupValues[2].toLong(),
    )
}

private fun String.localizedResolutionReason(language: UiLanguage): String =
    when (this) {
        "Qualified type matched" -> Res.string.source_reason_qualified_type_matched
        "Android resource matched" -> Res.string.source_reason_android_resource_matched
        "Managed symbol matched" -> Res.string.source_reason_managed_symbol_matched
        "Method name matched" -> Res.string.source_reason_method_name_matched
        "Symbolizer source path matched" -> Res.string.source_reason_symbolizer_source_path_matched
        "Native symbol name matched" -> Res.string.source_reason_native_symbol_name_matched
        else -> null
    }?.let { localizedStringResource(it, language) } ?: this
