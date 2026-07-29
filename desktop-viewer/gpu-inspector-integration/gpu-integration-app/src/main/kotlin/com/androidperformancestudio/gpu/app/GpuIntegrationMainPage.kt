@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod")

package com.androidperformancestudio.gpu.app

import com.androidperformancestudio.ui.localizedStringResource
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
import javax.swing.JFileChooser

@Composable
public fun FrameWindowScope.GpuIntegrationMainPage(
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
                ArtifactOpenCapability.NONE -> error(localizedStringResource(Res.string.no_compatible_viewer, chinese))
            }
        }.onFailure {
            state = state.copy(error = it.message ?: localizedStringResource(Res.string.unable_to_open_artifact, chinese))
        }
    }

    Column(Modifier.fillMaxSize()) {
        ProfilerMacOsToolbar {
            ProfilerHomeButton(
                contentDescription = localizedStringResource(Res.string.back_to_home, chinese),
                onClick = onBack,
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.refresh_agi, chinese),
                onClick = {
                    state =
                        state.copy(
                            capability = locator.locate(),
                            message = localizedStringResource(Res.string.agi_capability_refreshed, chinese),
                            error = null,
                        )
                },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.configure_agi, chinese),
                onClick = {
                    chooseExecutable(window, chinese)?.let { file ->
                        state =
                            state.copy(
                                capability = locator.locate(file.toPath()),
                                message = localizedStringResource(Res.string.configured_file, chinese, file.name),
                                error = null,
                            )
                    }
                },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.launch_agi, chinese),
                enabled = state.capability?.launchSupported == true,
                onClick = {
                    runCatching { locator.launch(requireNotNull(state.capability)) }
                        .onFailure { state = state.copy(error = it.message) }
                },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.import_artifact, chinese),
                onClick = {
                    chooseArtifact(window, chinese)?.let { file ->
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
                                    message = localizedStringResource(Res.string.imported_file, chinese, file.name),
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
                                    localizedStringResource(Res.string.artifact_hash_verified, chinese)
                                } else {
                                    localizedStringResource(Res.string.artifact_missing_or_changed, chinese)
                                },
                        )
                },
            ),
            chinese,
            Modifier.weight(1f),
        )
    }
}

private fun chooseExecutable(parent: java.awt.Component, chinese: Boolean): File? =
    JFileChooser().run {
        dialogTitle = localizedStringResource(Res.string.select_android_gpu_inspector_executable, chinese)
        if (showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }

private fun chooseArtifact(parent: java.awt.Component, chinese: Boolean): File? =
    JFileChooser().run {
        dialogTitle = localizedStringResource(Res.string.import_agi_perfetto_artifact, chinese)
        if (showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }
