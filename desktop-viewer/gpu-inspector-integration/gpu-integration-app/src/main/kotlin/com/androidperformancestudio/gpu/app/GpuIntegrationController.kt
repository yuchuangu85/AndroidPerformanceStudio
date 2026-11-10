package com.androidperformancestudio.gpu.app

import com.androidperformancestudio.gpu.artifact.AgiArtifactIndexer
import com.androidperformancestudio.gpu.artifact.JsonAgiArtifactStore
import com.androidperformancestudio.gpu.model.AgiCapability
import com.androidperformancestudio.gpu.model.ArtifactLocationStatus
import com.androidperformancestudio.gpu.model.GpuArtifact
import com.androidperformancestudio.gpu.model.GpuDeviceContext
import com.androidperformancestudio.gpu.presentation.ArtifactPrimaryAction
import com.androidperformancestudio.gpu.presentation.GpuArtifactAvailability
import com.androidperformancestudio.gpu.presentation.GpuHubTab
import com.androidperformancestudio.gpu.presentation.GpuIntegrationState
import com.androidperformancestudio.gpu.toolchain.AgiExecutablePreferenceStore
import com.androidperformancestudio.gpu.toolchain.AgiLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** Local index/toolchain IO stays off the UI thread; imported evidence is never inferred from UI selection. */
@Suppress("TooManyFunctions") // Workspace actions share one state; open revalidation belongs at this controller boundary.
internal class GpuIntegrationController(
    private val store: JsonAgiArtifactStore,
    private val preferences: AgiExecutablePreferenceStore,
    private val locator: AgiLocator = AgiLocator(),
    private val indexer: AgiArtifactIndexer = AgiArtifactIndexer(),
    private val discoverDevices: suspend () -> List<GpuDeviceContext> = { emptyList() },
    private val inspectDevice: suspend (GpuDeviceContext) -> GpuDeviceContext = { it },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow(GpuIntegrationState(isBusy = true))
    val state = mutableState.asStateFlow()
    private var indexLoadFailed = false
    private var deviceGeneration = 0L
    private val calculateAvailability: (List<GpuArtifact>, AgiCapability?) -> Map<String, GpuArtifactAvailability> = { artifacts, capability ->
        artifacts.associate { it.id to artifactAvailability(it, capability, indexer) }
    }
    val reportError: (Exception) -> Unit = { failure ->
        mutableState.update { it.copy(message = null, error = failure.message, completedDurationSeconds = null) }
    }

    suspend fun initialize() {
        val started = System.nanoTime()
        mutableState.update { it.copy(completedDurationSeconds = null) }
        try {
            val loaded = withContext(io) { store.load() }
            val capability = withContext(io) { locator.locate(preferences.load()) }
            val availability = withContext(io) { calculateAvailability(loaded, capability) }
            mutableState.update {
                it.copy(
                    capability = capability,
                    artifacts = loaded,
                    completedDurationSeconds = (System.nanoTime() - started) / 1_000_000_000.0,
                    selectedArtifactId = loaded.firstOrNull()?.id,
                    availabilityByArtifactId = availability,
                    isBusy = false,
                )
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            indexLoadFailed = true
            mutableState.update { it.copy(isBusy = false, error = failure.message) }
        }
    }

    suspend fun refreshAgi(configuredPath: Path? = null, message: String? = null) {
        operation(message) {
            val capability = withContext(io) {
                if (configuredPath != null) {
                    require(Files.isRegularFile(configuredPath) && Files.isExecutable(configuredPath)) {
                        "Selected AGI executable is not usable: $configuredPath"
                    }
                    preferences.save(configuredPath)
                }
                locator.locate(configuredPath ?: preferences.load())
            }
            val availability = withContext(io) { calculateAvailability(state.value.artifacts, capability) }
            mutableState.update { it.copy(capability = capability, availabilityByArtifactId = availability) }
        }
    }

    suspend fun importArtifact(path: Path, message: String? = null) {
        operation(message, timed = true) {
            require(!indexLoadFailed) { "GPU artifact index could not be loaded; existing index will not be overwritten" }
            val current = state.value
            val artifact = withContext(io) { indexer.import(path, agiVersion = current.capability?.version) }
            val updated = withContext(io) {
                indexer.mergeLocation(current.artifacts, artifact).also(store::save)
            }
            publishArtifacts(updated, updated.first().id)
        }
    }

    suspend fun relocateArtifact(artifact: GpuArtifact, path: Path, message: String? = null) {
        operation(message, timed = true) {
            require(!indexLoadFailed) { "GPU artifact index could not be loaded; existing index will not be overwritten" }
            val updated = withContext(io) {
                val relocated = indexer.relocate(artifact, path)
                state.value.artifacts.map { if (it.id == artifact.id) relocated else it }.also(store::save)
            }
            publishArtifacts(updated, artifact.id)
        }
    }

    suspend fun verifyArtifact(artifact: GpuArtifact, verifiedMessage: String, changedMessage: String) {
        operation {
            val verified = withContext(io) { indexer.verify(artifact) }
            val availability = withContext(io) { calculateAvailability(state.value.artifacts, state.value.capability) }
            mutableState.update {
                it.copy(
                    availabilityByArtifactId = availability, message = if (verified) verifiedMessage else null,
                    error = if (verified) null else changedMessage,
                )
            }
        }
    }

    suspend fun openArtifact(
        artifact: GpuArtifact,
        unavailableMessage: String,
        noViewerMessage: String,
        open: suspend (GpuArtifactAvailability, AgiCapability?) -> Unit,
    ) {
        operation {
            val current = state.value
            val indexedArtifact = requireNotNull(current.artifacts.firstOrNull { it.id == artifact.id }) { unavailableMessage }
            // Cached UI availability is only a hint: verify the indexed content again at the opening boundary.
            val availability = withContext(io) { artifactAvailability(indexedArtifact, current.capability, indexer) }
            mutableState.update {
                it.copy(availabilityByArtifactId = it.availabilityByArtifactId + (artifact.id to availability))
            }
            require(availability.locationStatus == ArtifactLocationStatus.AVAILABLE && availability.resolvedPath != null) {
                unavailableMessage
            }
            require(availability.primaryAction != ArtifactPrimaryAction.NONE) { noViewerMessage }
            open(availability, current.capability)
        }
    }

    val selectArtifact: (String) -> Unit = { id ->
        if (state.value.artifacts.any { it.id == id }) mutableState.update { it.copy(selectedArtifactId = id) }
    }

    /** Hub tab and rail search are presentation state; they never re-index or re-verify artifacts. */
    val selectHubTab: (GpuHubTab) -> Unit = { tab -> mutableState.update { it.copy(hubTab = tab) } }

    val filterArtifacts: (String) -> Unit = { query -> mutableState.update { it.copy(artifactFilter = query) } }

    fun selectDevice(serial: String) {
        if (state.value.devices.none { it.serial == serial }) return
        deviceGeneration++
        mutableState.update { it.copy(selectedDeviceSerial = serial, isInspectingDevice = false, deviceError = null) }
    }

    suspend fun refreshDevices() {
        val generation = ++deviceGeneration
        mutableState.update { it.copy(isRefreshingDevices = true, isInspectingDevice = false, deviceError = null) }
        try {
            val devices = discoverDevices()
            if (generation != deviceGeneration) return
            val selected = state.value.selectedDeviceSerial?.takeIf { serial -> devices.any { it.serial == serial } }
            mutableState.update { it.copy(devices = devices, selectedDeviceSerial = selected) }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            if (generation == deviceGeneration) {
                mutableState.update { it.copy(devices = emptyList(), selectedDeviceSerial = null, deviceError = failure.message) }
            }
        } finally {
            if (generation == deviceGeneration) mutableState.update { it.copy(isRefreshingDevices = false) }
        }
        inspectSelectedDevice()
    }

    suspend fun inspectSelectedDevice() {
        val device = state.value.devices.firstOrNull { it.serial == state.value.selectedDeviceSerial } ?: return
        val generation = ++deviceGeneration
        mutableState.update { it.copy(isInspectingDevice = true, deviceError = null) }
        try {
            val inspected = inspectDevice(device)
            if (generation == deviceGeneration && state.value.selectedDeviceSerial == device.serial) {
                mutableState.update { current ->
                    current.copy(devices = current.devices.map { if (it.serial == device.serial) inspected else it })
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            if (generation == deviceGeneration) mutableState.update { it.copy(deviceError = failure.message) }
        } finally {
            if (generation == deviceGeneration) mutableState.update { it.copy(isInspectingDevice = false) }
        }
    }

    private suspend fun publishArtifacts(artifacts: List<GpuArtifact>, selectedId: String) {
        val availability = withContext(io) { calculateAvailability(artifacts, state.value.capability) }
        mutableState.update { it.copy(artifacts = artifacts, selectedArtifactId = selectedId, availabilityByArtifactId = availability) }
    }

    private suspend fun operation(message: String? = null, timed: Boolean = false, block: suspend () -> Unit) {
        if (state.value.isBusy) return
        mutableState.update { it.copy(isBusy = true, error = null, message = null, completedDurationSeconds = null) }
        val started = System.nanoTime()
        try {
            block()
            if (timed && state.value.error == null) {
                mutableState.update { it.copy(completedDurationSeconds = (System.nanoTime() - started) / 1_000_000_000.0) }
            }
            if (message != null) mutableState.update { it.copy(message = message) }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            reportError(failure)
        } finally {
            mutableState.update { it.copy(isBusy = false) }
        }
    }
}
