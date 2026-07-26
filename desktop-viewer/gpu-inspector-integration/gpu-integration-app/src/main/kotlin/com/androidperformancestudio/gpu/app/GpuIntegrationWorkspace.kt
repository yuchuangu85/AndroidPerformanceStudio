@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod")

package com.androidperformancestudio.gpu.app

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
                ArtifactOpenCapability.NONE -> error("No compatible viewer is available")
            }
        }.onFailure {
            state = state.copy(error = it.message ?: "Unable to open artifact")
        }
    }

    Column(Modifier.fillMaxSize()) {
        ProfilerMacOsToolbar {
            ProfilerHomeButton(
                contentDescription = if (chinese) "返回主页" else "Back to home",
                onClick = onBack,
            )
            ProfilerCompactButton(
                text = if (chinese) "刷新 AGI" else "Refresh AGI",
                onClick = {
                    state =
                        state.copy(
                            capability = locator.locate(),
                            message = "AGI capability refreshed.",
                            error = null,
                        )
                },
            )
            ProfilerCompactButton(
                text = if (chinese) "配置 AGI" else "Configure AGI",
                onClick = {
                    chooseExecutable(window)?.let { file ->
                        state =
                            state.copy(
                                capability = locator.locate(file.toPath()),
                                message = "Configured ${file.name}",
                                error = null,
                            )
                    }
                },
            )
            ProfilerCompactButton(
                text = if (chinese) "启动 AGI" else "Launch AGI",
                enabled = state.capability?.launchSupported == true,
                onClick = {
                    runCatching { locator.launch(requireNotNull(state.capability)) }
                        .onFailure { state = state.copy(error = it.message) }
                },
            )
            ProfilerCompactButton(
                text = if (chinese) "导入产物" else "Import Artifact",
                onClick = {
                    chooseArtifact(window)?.let { file ->
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
                                    message = "Imported ${file.name}",
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
                                    "Artifact hash verified."
                                } else {
                                    "Artifact is missing or changed."
                                },
                        )
                },
            ),
            chinese,
            Modifier.weight(1f),
        )
    }
}

private fun chooseExecutable(parent: java.awt.Component): File? =
    JFileChooser().run {
        dialogTitle = "Select Android GPU Inspector executable"
        if (showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }

private fun chooseArtifact(parent: java.awt.Component): File? =
    JFileChooser().run {
        dialogTitle = "Import AGI / Perfetto artifact"
        if (showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }
