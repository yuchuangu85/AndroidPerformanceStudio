@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod")

package com.androidperformancestudio.gpu.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.gpu.artifact.AgiArtifactIndexer
import com.androidperformancestudio.gpu.artifact.JsonAgiArtifactStore
import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.Res
import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.agi_capability_refreshed
import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.artifact_hash_verified
import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.artifact_indexing
import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.artifact_missing_or_changed
import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.artifact_relocated
import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.back_to_home
import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.configure_agi
import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.configured_file
import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.import_agi_perfetto_artifact
import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.import_artifact
import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.imported_file
import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.launch_agi
import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.no_compatible_viewer
import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.refresh_agi
import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.select_android_gpu_inspector_executable
import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.unable_to_open_artifact
import com.androidperformancestudio.gpu.model.ArtifactLocationStatus
import com.androidperformancestudio.gpu.model.ArtifactOpenRoute
import com.androidperformancestudio.gpu.model.GpuArtifact
import com.androidperformancestudio.gpu.presentation.ArtifactPrimaryAction
import com.androidperformancestudio.gpu.presentation.GpuArtifactAvailability
import com.androidperformancestudio.gpu.presentation.GpuIntegrationActions
import com.androidperformancestudio.gpu.presentation.GpuIntegrationScreen
import com.androidperformancestudio.gpu.presentation.GpuIntegrationState
import com.androidperformancestudio.gpu.toolchain.AgiLocator
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerMacOsToolbar
import com.androidperformancestudio.ui.ProfilerToolbarStatus
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.button.HomeButton
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser

@Composable
public fun FrameWindowScope.GpuIntegrationMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
    darkTheme: Boolean = isSystemInDarkTheme(),
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
    val scope = rememberCoroutineScope()
    var state by
        remember {
            val capability = locator.locate()
            val loaded = runCatching(store::load)
            mutableStateOf(
                withAvailability(
                    GpuIntegrationState(
                        capability = capability,
                        artifacts = loaded.getOrDefault(emptyList()),
                        error = loaded.exceptionOrNull()?.message,
                    ),
                    indexer,
                ),
            )
        }

    fun open(artifact: GpuArtifact) {
        runCatching {
            val availability = artifactAvailability(artifact, state.capability, indexer)
            val path = requireNotNull(availability.resolvedPath) { "Artifact is missing or changed" }
            when (availability.primaryAction) {
                ArtifactPrimaryAction.OPEN_PERFETTO -> onOpenTrace(path)
                ArtifactPrimaryAction.OPEN_AGI -> locator.launchArtifact(requireNotNull(state.capability), path)
                ArtifactPrimaryAction.LAUNCH_AGI -> locator.launch(requireNotNull(state.capability))
                ArtifactPrimaryAction.OPEN_DESKTOP -> Desktop.getDesktop().open(path.toFile())
                ArtifactPrimaryAction.NONE -> error(localizedStringResource(Res.string.no_compatible_viewer, language))
            }
        }.onFailure {
            state =
                withAvailability(
                    state.copy(
                        error = it.message ?: localizedStringResource(Res.string.unable_to_open_artifact, language),
                    ),
                    indexer,
                )
        }
    }

    fun reveal(artifact: GpuArtifact) {
        runCatching {
            require(Desktop.isDesktopSupported()) { "Desktop file browsing is unavailable" }
            val path = indexer.resolveLocation(artifact).path ?: artifact.path
            val directory = path.parent?.takeIf(Files::isDirectory) ?: error("Artifact directory is unavailable")
            Desktop.getDesktop().open(directory.toFile())
        }.onFailure { state = state.copy(error = it.message) }
    }

    fun relocate(artifact: GpuArtifact) {
        chooseArtifact(window, language)?.let { file ->
            val artifacts = state.artifacts
            state = state.copy(isBusy = true, message = localizedStringResource(Res.string.artifact_indexing, language))
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val relocated = indexer.relocate(artifact, file.toPath())
                        val updated = artifacts.map { if (it.id == artifact.id) relocated else it }
                        store.save(updated)
                        updated
                    }
                }.onSuccess { updated ->
                    state =
                        withAvailability(
                            state.copy(
                                artifacts = updated,
                                isBusy = false,
                                message = localizedStringResource(Res.string.artifact_relocated, language, file.name),
                                error = null,
                            ),
                            indexer,
                        )
                }.onFailure {
                    state = state.copy(isBusy = false, error = it.message)
                }
            }
        }
    }

    ViewerTheme(darkTheme = darkTheme) {
        Column(Modifier.fillMaxSize()) {
            ProfilerMacOsToolbar {
                HomeButton(
                    contentDescription = localizedStringResource(Res.string.back_to_home, language),
                    onClick = onBack,
                )
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.refresh_agi, language),
                    enabled = !state.isBusy,
                    onClick = {
                        state =
                            withAvailability(
                                state.copy(
                                    capability = locator.locate(),
                                    message = localizedStringResource(Res.string.agi_capability_refreshed, language),
                                    error = null,
                                ),
                                indexer,
                            )
                    },
                )
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.configure_agi, language),
                    enabled = !state.isBusy,
                    onClick = {
                        chooseExecutable(window, language)?.let { file ->
                            state =
                                withAvailability(
                                    state.copy(
                                        capability = locator.locate(file.toPath()),
                                        message = localizedStringResource(Res.string.configured_file, language, file.name),
                                        error = null,
                                    ),
                                    indexer,
                                )
                        }
                    },
                )
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.launch_agi, language),
                    enabled = !state.isBusy && state.capability?.launchSupported == true,
                    onClick = {
                        runCatching { locator.launch(requireNotNull(state.capability)) }
                            .onFailure { state = state.copy(error = it.message) }
                    },
                )
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.import_artifact, language),
                    enabled = !state.isBusy,
                    onClick = {
                        chooseArtifact(window, language)?.let { file ->
                            val agiVersion = state.capability?.version
                            val artifacts = state.artifacts
                            state =
                                state.copy(
                                    isBusy = true,
                                    message = localizedStringResource(Res.string.artifact_indexing, language),
                                    error = null,
                                )
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        val artifact = indexer.import(file.toPath(), agiVersion = agiVersion)
                                        val updated = indexer.mergeLocation(artifacts, artifact)
                                        store.save(updated)
                                        updated
                                    }
                                }.onSuccess { updated ->
                                    state =
                                        withAvailability(
                                            state.copy(
                                                artifacts = updated,
                                                isBusy = false,
                                                message = localizedStringResource(Res.string.imported_file, language, file.name),
                                                error = null,
                                            ),
                                            indexer,
                                        )
                                }.onFailure {
                                    state = state.copy(isBusy = false, error = it.message)
                                }
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
                        state = state.copy(isBusy = true)
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { indexer.verify(artifact) } }
                                .onSuccess { verified ->
                                    state =
                                        withAvailability(
                                            state.copy(
                                                isBusy = false,
                                                message =
                                                    localizedStringResource(
                                                        if (verified) {
                                                            Res.string.artifact_hash_verified
                                                        } else {
                                                            Res.string.artifact_missing_or_changed
                                                        },
                                                        language,
                                                    ),
                                            ),
                                            indexer,
                                        )
                                }.onFailure {
                                    state = state.copy(isBusy = false, error = it.message)
                                }
                        }
                    },
                    onRevealArtifact = ::reveal,
                    onRelocateArtifact = ::relocate,
                ),
                language,
                Modifier.weight(1f),
            )
        }
    }
}

private fun withAvailability(
    state: GpuIntegrationState,
    indexer: AgiArtifactIndexer,
): GpuIntegrationState =
    state.copy(
        availabilityByArtifactId =
            state.artifacts.associate { artifact ->
                artifact.id to artifactAvailability(artifact, state.capability, indexer)
            },
    )

internal fun artifactAvailability(
    artifact: GpuArtifact,
    capability: com.androidperformancestudio.gpu.model.AgiCapability?,
    indexer: AgiArtifactIndexer,
): GpuArtifactAvailability {
    val location = indexer.resolveLocation(artifact)
    val action =
        if (location.status != ArtifactLocationStatus.AVAILABLE) {
            ArtifactPrimaryAction.NONE
        } else {
            when (artifact.openRoute) {
                ArtifactOpenRoute.PERFETTO -> ArtifactPrimaryAction.OPEN_PERFETTO
                ArtifactOpenRoute.DESKTOP -> ArtifactPrimaryAction.OPEN_DESKTOP
                ArtifactOpenRoute.AGI ->
                    when {
                        capability?.artifactOpenSupported == true -> ArtifactPrimaryAction.OPEN_AGI
                        capability?.launchSupported == true -> ArtifactPrimaryAction.LAUNCH_AGI
                        else -> ArtifactPrimaryAction.NONE
                    }
                ArtifactOpenRoute.NONE -> ArtifactPrimaryAction.NONE
            }
        }
    return GpuArtifactAvailability(location.status, location.path, action)
}

private fun chooseExecutable(
    parent: java.awt.Component,
    language: UiLanguage,
): File? =
    JFileChooser().run {
        dialogTitle = localizedStringResource(Res.string.select_android_gpu_inspector_executable, language)
        if (showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }

private fun chooseArtifact(
    parent: java.awt.Component,
    language: UiLanguage,
): File? =
    JFileChooser().run {
        dialogTitle = localizedStringResource(Res.string.import_agi_perfetto_artifact, language)
        if (showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
    }
