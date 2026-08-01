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
import com.androidperformancestudio.source.SourceProviderConfig
import com.androidperformancestudio.source.SourceLocation
import com.androidperformancestudio.source.SourceSnapshotId
import com.androidperformancestudio.source.SourceWorkspace
import com.androidperformancestudio.source.SourceWorkspacePhase
import com.androidperformancestudio.ui.UiLanguage
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

@Composable
internal fun SourceWorkspacesPage(
    language: UiLanguage,
    runtime: SourceWorkspaceRuntime,
    initialLocation: SourceLocation? = null,
    onBack: () -> Unit,
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onBack) { Text(label(language, "返回", "Back")) }
                Text(
                    text = label(language, "源码工作区", "Source Workspaces"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showAiSettings = true }) { Text(label(language, "AI 设置", "AI Settings")) }
                Button(onClick = {
                    chooseDirectory()?.let { root ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runtime.service.add(
                                    root.fileName?.toString() ?: root.toString(),
                                    SourceProviderConfig.Local(root),
                                )
                            }
                        }
                    }
                }) { Text("+ Local") }
                Button(onClick = { dialog = RemoteWorkspaceDialog.GITHUB }) { Text("+ GitHub") }
                Button(onClick = { dialog = RemoteWorkspaceDialog.AOSP }) { Text("+ AOSP") }
            }
            HorizontalDivider()
            pageError?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            }
            Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.width(360.dp).fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (workspaces.isEmpty()) {
                        item {
                            Text(
                                label(language, "添加本地、GitHub 或 AOSP 源码开始建立可信定位。", "Add Local, GitHub, or AOSP source to enable trusted resolution."),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(workspaces, key = { it.id.value }) { workspace ->
                        WorkspaceCard(
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
                        Text(label(language, "选择已完成索引的源码工作区", "Select an indexed source workspace"))
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
                "${workspace.config.kind} · ${workspace.phase}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (workspace.phase !in setOf(SourceWorkspacePhase.READY, SourceWorkspacePhase.FAILED)) {
                LinearProgressIndicator(progress = { workspace.progress }, modifier = Modifier.fillMaxWidth())
            }
            workspace.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onRefresh) { Text("Sync") }
                TextButton(onClick = onToggleAiUpload) {
                    Text(if (workspace.allowAiSourceUpload) "AI source: allowed" else "AI source: blocked")
                }
                TextButton(onClick = onRemove) { Text("Remove") }
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
                        append(selectedFile ?: label(language, "选择源码文件", "Select a source file"))
                        selectedLocation?.range?.let { append(":").append(it.startLine) }
                    },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                selectedLocation?.let { location ->
                    TextButton(onClick = { copyLocation(workspace, snapshot?.immutableRevision, location) }) { Text("Copy Location") }
                    TextButton(onClick = { openExternal(workspace, snapshot?.immutableRevision, location) }) {
                        Text(if (workspace.config is SourceProviderConfig.Local) "Open in IDE" else "Open Online")
                    }
                }
            }
            snapshot?.let {
                Text(
                    "${workspace.config.kind} · ${it.immutableRevision.take(16)} · ${candidate?.confidence ?: "indexed"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            candidate?.reasons?.takeIf { it.isNotEmpty() }?.let { reasons ->
                Text(reasons.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
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
        title = { Text(label(language, "AI 设置", "AI Settings")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (configured) {
                        label(language, "API Key 已配置。输入新值可替换。", "An API key is configured. Enter a new value to replace it.")
                    } else {
                        label(language, "API Key 将保存到系统凭证存储。", "The API key will be stored in the system credential store.")
                    },
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("OpenAI API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(label(language, "模型", "Model")) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text(label(language, "接口地址", "Endpoint")) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = model.isNotBlank() && endpoint.isNotBlank() && (configured || apiKey.isNotBlank()),
                onClick = { onSave(apiKey.trim(), model.trim(), endpoint.trim()) },
            ) {
                Text(label(language, "保存", "Save"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(label(language, "取消", "Cancel")) } },
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
        title = { Text(if (kind == RemoteWorkspaceDialog.GITHUB) "Add GitHub Workspace" else "Add AOSP Workspace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(label(language, "名称", "Name")) })
                OutlinedTextField(first, { first = it }, label = { Text(if (kind == RemoteWorkspaceDialog.GITHUB) "Owner" else "AOSP project") })
                if (kind == RemoteWorkspaceDialog.GITHUB) {
                    OutlinedTextField(second, { second = it }, label = { Text("Repository") })
                    OutlinedTextField(
                        token,
                        { token = it },
                        label = { Text("Token (optional)") },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                OutlinedTextField(ref, { ref = it }, label = { Text("Commit / tag / branch") })
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
            ) { Text(label(language, "添加", "Add")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(label(language, "取消", "Cancel")) } },
    )
}

private fun chooseDirectory(): Path? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}

private fun label(
    language: UiLanguage,
    chinese: String,
    english: String,
): String = if (language == UiLanguage.SIMPLIFIED_CHINESE) chinese else english
