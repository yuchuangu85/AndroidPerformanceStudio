@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod")

package com.androidperformancestudio.gpu.app

import org.jetbrains.compose.resources.stringResource

import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.Res
import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.*

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.gpu.artifact.AgiArtifactIndexer
import com.androidperformancestudio.gpu.artifact.JsonAgiArtifactStore
import com.androidperformancestudio.gpu.model.ArtifactOpenCapability
import com.androidperformancestudio.gpu.model.GpuArtifact
import com.androidperformancestudio.gpu.presentation.GpuIntegrationActions
import com.androidperformancestudio.gpu.presentation.GpuIntegrationScreen
import com.androidperformancestudio.gpu.presentation.GpuIntegrationState
import com.androidperformancestudio.gpu.toolchain.AgiLocator
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerHomeButton
import com.androidperformancestudio.ui.ProfilerMacOsToolbar
import com.androidperformancestudio.ui.ProfilerToolbarStatus
import java.awt.Desktop
import java.io.File
import java.nio.file.Path
import java.util.Locale
import javax.swing.JFileChooser

@Composable
public fun FrameWindowScope.GpuIntegrationWorkspace(
    chinese: Boolean = false,
    onBack: () -> Unit = {},
    onOpenTrace: (Path) -> Unit = {},
) {
    val locator = remember { AgiLocator() }
    val indexer = remember { AgiArtifactIndexer() }
    val store =
        remember {
            JsonAgiArtifactStore(
                Path.of(
                    System.getProperty("user.home"),
                    ".android-performance-studio",
                    "gpu-integration",
                    "artifacts.json",
                ),
            )
        }
    var state by
        remember {
            mutableStateOf(
                GpuIntegrationState(
                    capability = locator.locate(),
                    artifacts = store.load(),
                ),
            )
        }
    val noCompatibleViewer = stringResource(Res.string.no_compatible_viewer)
    val unableToOpenArtifact = stringResource(Res.string.unable_to_open_artifact)
    val capabilityRefreshed = stringResource(Res.string.agi_capability_refreshed)
    val configuredFileTemplate = stringResource(Res.string.configured_file)
    val importedFileTemplate = stringResource(Res.string.imported_file)
    val artifactHashVerified = stringResource(Res.string.artifact_hash_verified)
    val artifactMissingOrChanged = stringResource(Res.string.artifact_missing_or_changed)
    val executableDialogTitle = stringResource(Res.string.select_android_gpu_inspector_executable)
    val artifactDialogTitle = stringResource(Res.string.import_agi_perfetto_artifact)

    fun persist(artifacts: List<GpuArtifact>) {
        runCatching { store.save(artifacts) }
            .onFailure { state = state.copy(error = it.message) }
    }

    fun open(artifact: GpuArtifact) {
        runCatching {
            when (artifact.openCapability) {
                ArtifactOpenCapability.PERFETTO -> onOpenTrace(artifact.path)
                ArtifactOpenCapability.AGI ->
                    locator.launch(
                        requireNotNull(state.capability),
                        listOf(artifact.path.toString()),
                    )
                ArtifactOpenCapability.DESKTOP -> Desktop.getDesktop().open(artifact.path.toFile())
                ArtifactOpenCapability.NONE -> error(noCompatibleViewer)
            }
        }.onFailure {
            state = state.copy(error = it.message ?: unableToOpenArtifact)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ProfilerMacOsToolbar {
            ProfilerHomeButton(
                contentDescription = stringResource(Res.string.back_to_home),
                onClick = onBack,
            )
            ProfilerCompactButton(
                text = stringResource(Res.string.refresh_agi),
                onClick = {
                    state =
                        state.copy(
                            capability = locator.locate(),
                            message = capabilityRefreshed,
                            error = null,
                        )
                },
            )
            ProfilerCompactButton(
                text = stringResource(Res.string.configure_agi),
                onClick = {
                    chooseExecutable(window, executableDialogTitle)?.let { file ->
                        state =
                            state.copy(
                                capability = locator.locate(file.toPath()),
                                message = String.format(Locale.ROOT, configuredFileTemplate, file.name),
                                error = null,
                            )
                    }
                },
            )
            ProfilerCompactButton(
                text = stringResource(Res.string.launch_agi),
                enabled = state.capability?.launchSupported == true,
                onClick = {
                    runCatching { locator.launch(requireNotNull(state.capability)) }
                        .onFailure { state = state.copy(error = it.message) }
                },
            )
            ProfilerCompactButton(
                text = stringResource(Res.string.import_artifact),
                onClick = {
                    chooseArtifact(window, artifactDialogTitle)?.let { file ->
                        runCatching {
                            indexer.import(
                                file.toPath(),
                                agiVersion = state.capability?.version,
                            )
                        }.onSuccess { artifact ->
                            val updated =
                                (listOf(artifact) + state.artifacts)
                                    .distinctBy { it.sha256 }
                            persist(updated)
                            state =
                                state.copy(
                                    artifacts = updated,
                                    message = String.format(Locale.ROOT, importedFileTemplate, file.name),
                                    error = null,
                                )
                        }.onFailure {
                            state = state.copy(error = it.message)
                        }
                    }
                },
            )
            Spacer(Modifier.weight(1f))
            ProfilerToolbarStatus(state.message, state.error)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        GpuIntegrationScreen(
            state,
            GpuIntegrationActions(
                onOpenArtifact = ::open,
                onVerifyArtifact = { artifact ->
                    state =
                        state.copy(
                            message =
                                if (indexer.verify(artifact)) {
                                    artifactHashVerified
                                } else {
                                    artifactMissingOrChanged
                                },
                        )
                },
            ),
            chinese,
            Modifier.weight(1f),
        )
    }
}

private fun chooseExecutable(parent: java.awt.Component, dialogTitle: String): File? =
    JFileChooser().run {
        this.dialogTitle = dialogTitle
        if (showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }

private fun chooseArtifact(parent: java.awt.Component, dialogTitle: String): File? =
    JFileChooser().run {
        this.dialogTitle = dialogTitle
        if (showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }
