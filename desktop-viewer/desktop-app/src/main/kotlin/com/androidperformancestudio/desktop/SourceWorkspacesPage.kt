package com.androidperformancestudio.desktop

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.desktop_app.generated.resources.*
import com.androidperformancestudio.desktop_app.generated.resources.Res
import com.androidperformancestudio.source.ResolutionConfidence
import com.androidperformancestudio.source.SourceContentState
import com.androidperformancestudio.source.SourceProviderConfig
import com.androidperformancestudio.source.SourceLanguage
import com.androidperformancestudio.source.SourceLocation
import com.androidperformancestudio.source.SourceProviderKind
import com.androidperformancestudio.source.SourceSnapshotId
import com.androidperformancestudio.source.SourceWorkspace
import com.androidperformancestudio.source.SourceWorkspacePhase
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.HEADER_TOOL_BAR_HEIGHT
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.button.HomeButton
import com.androidperformancestudio.ui.button.MacOSTextButton
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.viewerOutlinedTextFieldColors
import java.awt.Cursor
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
    onOpenAiSettings: () -> Unit,
) {
    val workspaces by runtime.service.workspaces.collectAsState()
    val visibleWorkspaces = workspaces.filter { it.config.kind == SourceProviderKind.LOCAL }
    val scope = rememberCoroutineScope()
    var selectedWorkspaceId by remember { mutableStateOf(initialLocation?.workspaceId) }
    var selectedFile by remember { mutableStateOf<String?>(null) }
    var selectedLocation by remember { mutableStateOf(initialLocation) }
    var sourceText by remember { mutableStateOf<String?>(null) }
    var sourceState by remember { mutableStateOf<SourceContentState?>(null) }
    var dialog by remember { mutableStateOf<RemoteWorkspaceDialog?>(null) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var paneWidths by remember { mutableStateOf(SourceWorkspacePaneWidths()) }
    val colors = LocalViewerColors.current

    LaunchedEffect(initialLocation) {
        initialLocation?.let { location ->
            selectedWorkspaceId = location.workspaceId
            selectedFile = location.relativePath
            selectedLocation = location
            sourceText = null
            sourceState = null
            runCatching { withContext(Dispatchers.IO) { runtime.service.read(location) } }
                .onSuccess {
                    sourceText = it.text
                    sourceState = it.state
                }
                .onFailure { pageError = it.message }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(HEADER_TOOL_BAR_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HomeButton(
                    contentDescription = localizedStringResource(Res.string.home, language),
                    onClick = onNavigateHome,
                )
                Text(
                    text = localizedStringResource(Res.string.source_workspaces, language),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                MacOSTextButton(
                    localizedStringResource(Res.string.source_add_local, language),
                    onClick = { chooseDirectory(localizedStringResource(Res.string.source_choose_local_directory, language))?.let { root ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runtime.service.add(
                                    root.fileName?.toString() ?: root.toString(),
                                    SourceProviderConfig.Local(root),
                                )
                            }
                        }
                    } },
                    colors
                )
                if (REMOTE_SOURCE_WORKSPACES_VISIBLE) {
                    MacOSTextButton(
                        localizedStringResource(Res.string.source_add_github, language),
                        onClick = { dialog = RemoteWorkspaceDialog.GITHUB },
                        colors
                    )
                    MacOSTextButton(
                        localizedStringResource(Res.string.source_add_aosp, language),
                        onClick = { dialog = RemoteWorkspaceDialog.AOSP },
                        colors
                    )
                }
                MacOSTextButton(
                    localizedStringResource(Res.string.source_ai_settings, language),
                    onClick = onOpenAiSettings,
                    colors
                )
            }
            HorizontalDivider()
            pageError?.let { message ->
                Text(
                    localizedStringResource(Res.string.source_operation_failed, language, message),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp),
                )
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val availableWidthDp = maxWidth.value
                val fittedPaneWidths = SourceWorkspacePaneLayout.fit(paneWidths, availableWidthDp)
                Row(Modifier.fillMaxSize()) {
                    WorkspaceListPane(
                        language = language,
                        workspaces = visibleWorkspaces,
                        selectedWorkspaceId = selectedWorkspaceId,
                        onSelect = { workspace ->
                            selectedWorkspaceId = workspace.id
                            selectedFile = null
                            selectedLocation = null
                            sourceText = null
                            sourceState = null
                        },
                        onRefresh = { workspace ->
                            scope.launch { withContext(Dispatchers.IO) { runtime.service.refresh(workspace.id) } }
                        },
                        onToggleAiUpload = { workspace ->
                            runtime.service.setAiSourceUploadAllowed(workspace.id, !workspace.allowAiSourceUpload)
                        },
                        onRemove = { workspace ->
                            runtime.service.remove(workspace.id)
                            if (selectedWorkspaceId == workspace.id) selectedWorkspaceId = null
                        },
                        modifier = Modifier.width(fittedPaneWidths.workspaces.dp).fillMaxHeight(),
                    )
                    SourceWorkspaceResizeSeparator { deltaDp ->
                        paneWidths = SourceWorkspacePaneLayout.dragWorkspaces(
                            widths = SourceWorkspacePaneLayout.fit(paneWidths, availableWidthDp),
                            deltaDp = deltaDp,
                            availableWidthDp = availableWidthDp,
                        )
                    }
                    val active = visibleWorkspaces.firstOrNull { it.id == selectedWorkspaceId }
                    if (active?.activeSnapshotId == null) {
                        Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(localizedStringResource(Res.string.source_select_indexed_workspace, language))
                        }
                    } else {
                        val activeSnapshotId = requireNotNull(active.activeSnapshotId)
                        val browserSnapshotId = selectedLocation?.snapshotId ?: activeSnapshotId
                        SourceBrowser(
                            language = language,
                            snapshotId = browserSnapshotId,
                            runtime = runtime,
                            selectedFile = selectedFile,
                            sourceText = sourceText,
                            sourceState = sourceState,
                            selectedLocation = selectedLocation,
                            workspace = active,
                            filesPaneWidth = fittedPaneWidths.files,
                            onResizeFilesPane = { deltaDp ->
                                paneWidths = SourceWorkspacePaneLayout.dragFiles(
                                    widths = SourceWorkspacePaneLayout.fit(paneWidths, availableWidthDp),
                                    deltaDp = deltaDp,
                                    availableWidthDp = availableWidthDp,
                                )
                            },
                            onOpen = { path ->
                                selectedFile = path
                                val file = runtime.repository.files(browserSnapshotId).first { it.relativePath == path }
                                selectedLocation = SourceLocation(active.id, browserSnapshotId, path, null, file.contentHash)
                                scope.launch {
                                    sourceText = null
                                    sourceState = null
                                    runCatching {
                                        withContext(Dispatchers.IO) { runtime.service.read(
                                            com.androidperformancestudio.source.SourceLocation(
                                                active.id,
                                                browserSnapshotId,
                                                path,
                                                null,
                                                file.contentHash,
                                            ),
                                        ) }
                                    }.onSuccess {
                                        sourceText = it.text
                                        sourceState = it.state
                                    }.onFailure { pageError = it.message }
                                }
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
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

internal const val REMOTE_SOURCE_WORKSPACES_VISIBLE = false

@Composable
private fun WorkspaceListPane(
    language: UiLanguage,
    workspaces: List<SourceWorkspace>,
    selectedWorkspaceId: com.androidperformancestudio.source.SourceWorkspaceId?,
    onSelect: (SourceWorkspace) -> Unit,
    onRefresh: (SourceWorkspace) -> Unit,
    onToggleAiUpload: (SourceWorkspace) -> Unit,
    onRemove: (SourceWorkspace) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val horizontalScrollState = rememberScrollState()
    BoxWithConstraints(modifier) {
        val contentWidthDp = maxOf(
            maxWidth.value,
            workspaces.maxOfOrNull { workspace -> workspace.displayName.length * 8f + 48f } ?: 0f,
        )
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().horizontalScroll(horizontalScrollState)) {
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .width(contentWidthDp.dp)
                            .fillMaxHeight()
                            .padding(end = 12.dp, bottom = 12.dp)
                            .padding(12.dp),
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
                            onSelect = { onSelect(workspace) },
                            onRefresh = { onRefresh(workspace) },
                            onToggleAiUpload = { onToggleAiUpload(workspace) },
                            onRemove = { onRemove(workspace) },
                        )
                    }
                }
            }
            SourceWorkspacePaneScrollbars(listState, horizontalScrollState)
        }
    }
}

@Composable
private fun SourceFileListPane(
    files: List<com.androidperformancestudio.source.SourceFile>,
    selectedFile: String?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var collapsedDirectories by remember(files) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(selectedFile) {
        collapsedDirectories -= SourceFileTree.ancestorDirectories(selectedFile.orEmpty())
    }
    val treeRows = remember(files, collapsedDirectories) {
        SourceFileTree.rows(files, collapsedDirectories)
    }
    val listState = rememberLazyListState()
    val horizontalScrollState = rememberScrollState()
    BoxWithConstraints(modifier) {
        val contentWidthDp = maxOf(
            maxWidth.value,
            treeRows.maxOfOrNull { row -> row.name.length * 8f + (row.depth + 1) * 20f + 48f } ?: 0f,
        )
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().horizontalScroll(horizontalScrollState)) {
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .width(contentWidthDp.dp)
                            .fillMaxHeight()
                            .padding(end = 12.dp, bottom = 12.dp)
                            .padding(8.dp),
                ) {
                    items(treeRows, key = { it.key }) { row ->
                        when (row) {
                            is SourceFileTreeRow.Directory -> {
                                SourceDirectoryTreeRow(
                                    row = row,
                                    onToggle = {
                                        collapsedDirectories =
                                            if (row.expanded) collapsedDirectories + row.path else collapsedDirectories - row.path
                                    },
                                )
                            }
                            is SourceFileTreeRow.File -> {
                                SourceFileTreeFileRow(
                                    row = row,
                                    selected = selectedFile == row.sourceFile.relativePath,
                                    onOpen = { onOpen(row.sourceFile.relativePath) },
                                )
                            }
                        }
                    }
                }
            }
            SourceWorkspacePaneScrollbars(listState, horizontalScrollState)
        }
    }
}

@Composable
private fun SourceDirectoryTreeRow(
    row: SourceFileTreeRow.Directory,
    onToggle: () -> Unit,
) {
    TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = (row.depth * 16).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (row.expanded) "▾" else "▸", modifier = Modifier.width(16.dp))
            Text(row.name, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun SourceFileTreeFileRow(
    row: SourceFileTreeRow.File,
    selected: Boolean,
    onOpen: () -> Unit,
) {
    val colors = LocalViewerColors.current
    TextButton(
        onClick = onOpen,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (selected) colors.accent.copy(alpha = 0.16f) else colors.transparent),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = (row.depth * 16 + 16).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("·", modifier = Modifier.width(16.dp))
            Text(row.name, modifier = Modifier.fillMaxWidth(), maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun BoxScope.SourceWorkspacePaneScrollbars(
    listState: LazyListState,
    horizontalScrollState: ScrollState,
) {
    val scrollbarStyle = LocalScrollbarStyle.current
    HorizontalScrollbar(
        adapter = rememberScrollbarAdapter(horizontalScrollState),
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(end = 12.dp),
        style = scrollbarStyle,
    )
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(listState),
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = 12.dp),
        style = scrollbarStyle,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SourceWorkspaceResizeSeparator(onDrag: (Float) -> Unit) {
    val colors = LocalViewerColors.current
    val density = LocalDensity.current
    val currentOnDrag by rememberUpdatedState(onDrag)
    Box(
        modifier =
            Modifier
                .fillMaxHeight()
                .width(SourceWorkspacePaneLayout.SPLITTER_WIDTH_DP.dp)
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                .pointerInput(density) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        currentOnDrag(dragAmount / density.density)
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxHeight().width(1.dp).background(colors.border))
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
            Text(
                workspace.displayName,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
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
                MacOSTextButton(localizedStringResource(Res.string.source_sync, language), onClick = onRefresh)
                MacOSTextButton(
                    localizedStringResource(
                        if (workspace.allowAiSourceUpload) {
                            Res.string.source_ai_upload_allowed
                        } else {
                            Res.string.source_ai_upload_blocked
                        }, language
                    ), onClick = onToggleAiUpload
                )
                MacOSTextButton(localizedStringResource(Res.string.source_remove, language), onClick = onRemove)
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
    sourceState: SourceContentState?,
    selectedLocation: SourceLocation?,
    workspace: SourceWorkspace,
    filesPaneWidth: Float,
    onResizeFilesPane: (Float) -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val files = remember(snapshotId) { runtime.repository.files(snapshotId) }
    Row(modifier) {
        SourceFileListPane(
            files = files,
            selectedFile = selectedFile,
            onOpen = onOpen,
            modifier = Modifier.width(filesPaneWidth.dp).fillMaxHeight(),
        )
        SourceWorkspaceResizeSeparator(onResizeFilesPane)
        Column(Modifier.weight(1f).fillMaxSize().padding(12.dp)) {
            val snapshot = runtime.repository.snapshot(snapshotId)
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
                    MacOSTextButton(
                        localizedStringResource(Res.string.source_copy_location, language),
                        onClick = { copyLocation(workspace, snapshot?.immutableRevision, location) })
                    MacOSTextButton(
                        localizedStringResource(
                            if (workspace.config is SourceProviderConfig.Local) {
                                Res.string.source_open_in_ide
                            } else {
                                Res.string.source_open_online
                            },
                            language,
                        ), onClick = { openExternal(workspace, snapshot?.immutableRevision, location) })
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
            if (sourceState == SourceContentState.STALE) {
                Text(
                    localizedStringResource(Res.string.source_content_stale, language),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            when {
                selectedFile == null -> Unit
                sourceText == null -> CircularProgressIndicator()
                else -> {
                    val selectedSourceFile = files.firstOrNull { it.relativePath == selectedFile }
                    SourceCodeViewer(
                        sourceText = requireNotNull(sourceText),
                        language = selectedSourceFile?.language ?: SourceLanguage.OTHER,
                        highlightedRange = selectedLocation?.range,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
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
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(localizedStringResource(Res.string.source_name, language)) },
                    colors = viewerOutlinedTextFieldColors(),
                )
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
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
                    colors = viewerOutlinedTextFieldColors(),
                )
                if (kind == RemoteWorkspaceDialog.GITHUB) {
                    OutlinedTextField(
                        value = second,
                        onValueChange = { second = it },
                        label = { Text(localizedStringResource(Res.string.source_repository, language)) },
                        colors = viewerOutlinedTextFieldColors(),
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text(localizedStringResource(Res.string.source_token_optional, language)) },
                        visualTransformation = PasswordVisualTransformation(),
                        colors = viewerOutlinedTextFieldColors(),
                    )
                }
                OutlinedTextField(
                    value = ref,
                    onValueChange = { ref = it },
                    label = { Text(localizedStringResource(Res.string.source_revision_hint, language)) },
                    colors = viewerOutlinedTextFieldColors(),
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
