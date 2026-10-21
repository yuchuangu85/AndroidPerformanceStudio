@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod")

package com.androidperformancestudio.gpu.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.adb.defaultAdbCommandExecutable
import com.androidperformancestudio.gpu.artifact.AgiArtifactIndexer
import com.androidperformancestudio.gpu.artifact.JsonAgiArtifactStore
import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.*
import com.androidperformancestudio.gpu.gpu_integration_app.generated.resources.Res
import com.androidperformancestudio.gpu.model.ArtifactLocationStatus
import com.androidperformancestudio.gpu.model.ArtifactOpenRoute
import com.androidperformancestudio.gpu.model.GpuArtifact
import com.androidperformancestudio.gpu.presentation.ArtifactPrimaryAction
import com.androidperformancestudio.gpu.presentation.GpuAnalysisHubScreen
import com.androidperformancestudio.gpu.presentation.GpuAnalysisToolbar
import com.androidperformancestudio.gpu.presentation.GpuArtifactAvailability
import com.androidperformancestudio.gpu.presentation.GpuIntegrationActions
import com.androidperformancestudio.gpu.toolchain.AgiExecutablePreferenceStore
import com.androidperformancestudio.gpu.toolchain.AgiLocator
import com.androidperformancestudio.ui.HeaderSpacer
import com.androidperformancestudio.ui.HeaderToolbar
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerToolbarStatus
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import com.androidperformancestudio.ui.chooseOpenFile

@Composable
public fun FrameWindowScope.GpuIntegrationMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
    onBack: () -> Unit = {},
    onOpenTrace: (Path) -> Unit = {},
    onOpenTraceAnalyzer: () -> Unit = {},
) {
    val root = remember { Path.of(System.getProperty("user.home"), ".android-performance-studio", "gpu-integration") }
    val locator = remember { AgiLocator() }
    val indexer = remember { AgiArtifactIndexer() }
    val reader = remember { AndroidGpuDeviceContextReader(defaultAdbCommandExecutable()) }
    val controller = remember {
        GpuIntegrationController(
            JsonAgiArtifactStore(root.resolve("artifacts.json")),
            AgiExecutablePreferenceStore(root.resolve("agi-executable.txt")),
            locator, indexer, reader::discoverDevices, reader::inspect,
        )
    }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller) { controller.initialize() }
    LaunchedEffect(reader) { controller.refreshDevices() }

    fun open(artifact: GpuArtifact) {
        scope.launch {
            controller.openArtifact(
                artifact,
                localizedStringResource(Res.string.artifact_missing_or_changed, language),
                localizedStringResource(Res.string.no_compatible_viewer, language),
            ) { availability, capability ->
                val path = requireNotNull(availability.resolvedPath)
                when (availability.primaryAction) {
                    ArtifactPrimaryAction.OPEN_PERFETTO -> onOpenTrace(path)
                    ArtifactPrimaryAction.OPEN_AGI -> withContext(Dispatchers.IO) { locator.launchArtifact(requireNotNull(capability), path) }
                    ArtifactPrimaryAction.LAUNCH_AGI -> withContext(Dispatchers.IO) { locator.launch(requireNotNull(capability)) }
                    ArtifactPrimaryAction.OPEN_DESKTOP -> withContext(Dispatchers.IO) { Desktop.getDesktop().open(path.toFile()) }
                    ArtifactPrimaryAction.NONE -> error(localizedStringResource(Res.string.no_compatible_viewer, language))
                }
            }
        }
    }

    fun reveal(artifact: GpuArtifact) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    require(Desktop.isDesktopSupported()) { "Desktop file browsing is unavailable" }
                    val path = indexer.resolveLocation(artifact).path ?: artifact.path
                    val directory = path.parent?.takeIf(Files::isDirectory) ?: error("Artifact directory is unavailable")
                    Desktop.getDesktop().open(directory.toFile())
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                controller.reportError(failure)
            }
        }
    }

    fun relocate(artifact: GpuArtifact) {
        chooseArtifact(window, language)?.let { file ->
            scope.launch { controller.relocateArtifact(artifact, file.toPath(), localizedStringResource(Res.string.artifact_relocated, language, file.name)) }
        }
    }

    // AGI launch and GPU artifact import live on the hub analysis toolbar (page 09 §14.2 Actions).
    fun launchAgi() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { locator.launch(requireNotNull(state.capability)) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                controller.reportError(failure)
            }
        }
    }

    fun importArtifact() {
        chooseArtifact(window, language)?.let { file ->
            scope.launch { controller.importArtifact(file.toPath(), localizedStringResource(Res.string.imported_file, language, file.name)) }
        }
    }

    val selectedArtifact = state.artifacts.firstOrNull { it.id == state.selectedArtifactId }
    val gpuActions =
        GpuIntegrationActions(
            onOpenArtifact = ::open,
            onVerifyArtifact = { artifact ->
                scope.launch {
                    controller.verifyArtifact(
                        artifact,
                        localizedStringResource(Res.string.artifact_hash_verified, language),
                        localizedStringResource(Res.string.artifact_missing_or_changed, language),
                    )
                }
            },
            onRevealArtifact = ::reveal,
            onRelocateArtifact = ::relocate,
            onRefreshDevices = { scope.launch { controller.refreshDevices() } },
            onSelectDevice = { serial ->
                controller.selectDevice(serial)
                scope.launch { controller.inspectSelectedDevice() }
            },
            onSelectArtifact = controller.selectArtifact,
            onOpenTraceAnalyzer = onOpenTraceAnalyzer,
            onSelectHubTab = controller.selectHubTab,
            onFilterArtifacts = controller.filterArtifacts,
            onLaunchAgi = ::launchAgi,
            onImportArtifact = ::importArtifact,
        )

    Column(Modifier.fillMaxSize()) {
        HeaderToolbar(language = language, onNavigateHome = onBack, onNavigateSettings = null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GpuAnalysisToolbar(
                    state = state,
                    actions = gpuActions,
                    selectedArtifact = selectedArtifact,
                    language = language,
                    modifier = Modifier.weight(1f),
                )
                HeaderSpacer()
                ProfilerToolbarStatus(state.message, state.error)
                HeaderSpacer()
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.refresh_agi, language),
                    enabled = !state.isBusy,
                    onClick = { scope.launch { controller.refreshAgi(message = localizedStringResource(Res.string.agi_capability_refreshed, language)) } },
                )
                HeaderSpacer()
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.configure_agi, language),
                    enabled = !state.isBusy,
                    onClick = {
                        chooseExecutable(window, language)?.let { file ->
                            scope.launch { controller.refreshAgi(file.toPath(), localizedStringResource(Res.string.configured_file, language, file.name)) }
                        }
                    },
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        GpuAnalysisHubScreen(
            state = state,
            actions = gpuActions,
            language = language,
            modifier = Modifier.weight(1f),
        )
    }
}

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
    chooseOpenFile(parent, localizedStringResource(Res.string.select_android_gpu_inspector_executable, language))

private fun chooseArtifact(
    parent: java.awt.Component,
    language: UiLanguage,
): File? =
    chooseOpenFile(parent, localizedStringResource(Res.string.import_agi_perfetto_artifact, language))
