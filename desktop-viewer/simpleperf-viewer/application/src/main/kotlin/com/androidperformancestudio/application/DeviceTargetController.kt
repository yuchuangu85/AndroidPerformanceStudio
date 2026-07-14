package com.androidperformancestudio.application

import com.androidperformancestudio.capture.CaptureRequest
import com.androidperformancestudio.capture.CaptureSession
import com.androidperformancestudio.capture.CaptureState
import com.androidperformancestudio.capture.DeviceSimpleperfAvailability
import com.androidperformancestudio.capture.SamplingParameters
import com.androidperformancestudio.capture.SamplingTemplate
import com.androidperformancestudio.capture.SimpleperfTarget
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path
import java.util.UUID

enum class WorkspacePage {
    DEVICE_TARGET,
    CAPTURE,
}

enum class CapabilityStatus {
    READY,
    LIMITED,
    BLOCKED,
}

data class DeviceOption(
    val serial: String,
    val label: String,
    val isOnline: Boolean,
)

data class CapabilitySummary(
    val status: CapabilityStatus,
    val root: String,
    val profilingScope: String,
    val simpleperf: String,
    val eventNames: List<String>,
    val limitations: List<String>,
)

data class PackageOption(
    val packageName: String,
)

data class ProcessOption(
    val pid: Int,
    val name: String,
    val user: String,
)

data class ThreadOption(
    val pid: Int,
    val tid: Int,
    val name: String,
)

data class DeviceSelection(
    val serial: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val abis: List<String>,
    val capabilities: CapabilitySummary,
    val packages: List<PackageOption>,
    val processes: List<ProcessOption>,
)

sealed interface CaptureTarget {
    data class App(
        val packageName: String,
    ) : CaptureTarget

    data class Process(
        val pid: Int,
        val name: String,
    ) : CaptureTarget

    data class Thread(
        val pid: Int,
        val tid: Int,
        val name: String,
    ) : CaptureTarget
}

data class DeviceTargetState(
    val page: WorkspacePage = WorkspacePage.DEVICE_TARGET,
    val devices: List<DeviceOption> = emptyList(),
    val selectedSerial: String? = null,
    val selection: DeviceSelection? = null,
    val searchQuery: String = "",
    val selectedTarget: CaptureTarget? = null,
    val captureSetup: CaptureSetup? = null,
    val threads: List<ThreadOption> = emptyList(),
    val isLoading: Boolean = false,
    val error: StudioError? = null,
) {
    val canEnterCapture: Boolean
        get() = selectedTarget != null && selection?.capabilities?.status != CapabilityStatus.BLOCKED

    val visiblePackages: List<PackageOption>
        get() =
            selection
                ?.packages
                .orEmpty()
                .filter { searchQuery.isBlank() || it.packageName.contains(searchQuery.trim(), ignoreCase = true) }

    val visibleProcesses: List<ProcessOption>
        get() =
            selection
                ?.processes
                .orEmpty()
                .filter {
                    searchQuery.isBlank() ||
                        it.name.contains(searchQuery.trim(), ignoreCase = true) ||
                        it.user.contains(searchQuery.trim(), ignoreCase = true) ||
                        it.pid.toString().contains(searchQuery.trim())
                }
}

data class CaptureSetup(
    val template: SamplingTemplate,
    val parameters: SamplingParameters,
)

interface DeviceTargetGateway {
    suspend fun refreshDevices(): StudioResult<List<DeviceOption>>

    suspend fun loadSelection(serial: String): StudioResult<DeviceSelection>

    suspend fun loadThreads(
        serial: String,
        pid: Int,
    ): StudioResult<List<ThreadOption>>
}

@Suppress("TooManyFunctions")
class DeviceTargetController(
    private val gateway: DeviceTargetGateway,
    private val captureSession: CaptureSession? = null,
    private val sessionRoot: Path =
        Path.of(System.getProperty("user.home"), ".android-performance-studio", "sessions"),
    private val sessionIdProvider: () -> String = { "capture-${UUID.randomUUID()}" },
) {
    private val mutableState = MutableStateFlow(DeviceTargetState())
    private val idleCaptureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<DeviceTargetState> = mutableState.asStateFlow()
    val captureState: StateFlow<CaptureState> = captureSession?.state ?: idleCaptureState.asStateFlow()
    val cancelCapture: () -> Unit = { captureSession?.cancel() }

    suspend fun stopCapture() {
        captureSession?.stop()
    }

    suspend fun refreshDevices() {
        mutableState.value = mutableState.value.copy(isLoading = true, error = null)
        mutableState.value =
            when (val result = gateway.refreshDevices()) {
                is StudioResult.Success -> mutableState.value.copy(devices = result.value, isLoading = false)
                is StudioResult.Failure -> mutableState.value.copy(isLoading = false, error = result.error)
            }
    }

    suspend fun selectDevice(serial: String) {
        mutableState.value = mutableState.value.copy(isLoading = true, error = null)
        mutableState.value =
            when (val result = gateway.loadSelection(serial)) {
                is StudioResult.Success ->
                    mutableState.value.copy(
                        selectedSerial = serial,
                        selection = result.value,
                        selectedTarget = null,
                        captureSetup = null,
                        threads = emptyList(),
                        isLoading = false,
                    )
                is StudioResult.Failure -> mutableState.value.copy(isLoading = false, error = result.error)
            }
    }

    fun updateSearch(query: String) {
        mutableState.value = mutableState.value.copy(searchQuery = query)
    }

    fun selectPackage(packageName: String) {
        mutableState.value =
            mutableState.value.copy(
                selectedTarget = CaptureTarget.App(packageName),
                captureSetup = null,
                threads = emptyList(),
            )
    }

    suspend fun selectProcess(pid: Int) {
        val current = mutableState.value
        val serial = current.selectedSerial ?: return
        val process = current.selection?.processes?.firstOrNull { it.pid == pid } ?: return
        mutableState.value =
            current.copy(
                selectedTarget = CaptureTarget.Process(process.pid, process.name),
                captureSetup = null,
                threads = emptyList(),
                isLoading = true,
                error = null,
            )
        mutableState.value =
            when (val result = gateway.loadThreads(serial, pid)) {
                is StudioResult.Success -> mutableState.value.copy(threads = result.value, isLoading = false)
                is StudioResult.Failure -> mutableState.value.copy(isLoading = false, error = result.error)
            }
    }

    fun selectThread(thread: ThreadOption) {
        mutableState.value =
            mutableState.value.copy(
                selectedTarget = CaptureTarget.Thread(thread.pid, thread.tid, thread.name),
                captureSetup = null,
            )
    }

    fun enterCapture(): Boolean {
        val current = mutableState.value
        val setup = current.createCaptureSetup(SamplingTemplate.APP_CPU_BASIC)
        val canEnter = current.canEnterCapture && setup != null
        if (canEnter) {
            mutableState.value = current.copy(page = WorkspacePage.CAPTURE, captureSetup = setup)
        }
        return canEnter
    }

    fun selectSamplingTemplate(template: SamplingTemplate) {
        val setup = mutableState.value.createCaptureSetup(template) ?: return
        mutableState.value = mutableState.value.copy(captureSetup = setup)
    }

    fun updateSamplingParameters(parameters: SamplingParameters) {
        val current = mutableState.value
        val setup = current.captureSetup ?: return
        mutableState.value =
            current.copy(
                captureSetup =
                    setup.copy(
                        parameters = parameters,
                    ),
            )
    }

    fun backToTargets() {
        mutableState.value = mutableState.value.copy(page = WorkspacePage.DEVICE_TARGET)
    }

    suspend fun startCapture(): CaptureState? {
        val request = mutableState.value.createCaptureRequest(sessionIdProvider(), sessionRoot)
        return captureSession?.let { session -> request?.let { session.capture(it) } }
    }
}

private fun DeviceTargetState.createCaptureRequest(
    sessionId: String,
    sessionRoot: Path,
): CaptureRequest? =
    selection?.let { selection ->
        captureSetup?.parameters?.let { parameters ->
            CaptureRequest(
                sessionId = sessionId,
                sessionRoot = sessionRoot,
                serial = selection.serial,
                availability =
                    DeviceSimpleperfAvailability(
                        deviceVersion =
                            selection.capabilities.simpleperf.takeIf {
                                it.isNotBlank() && !it.equals("missing", ignoreCase = true)
                            },
                        abis = selection.abis,
                    ),
                parameters = parameters,
            )
        }
    }

private fun DeviceTargetState.createCaptureSetup(template: SamplingTemplate): CaptureSetup? {
    if (selectedSerial == null) {
        return null
    }
    return selectedTarget?.toSimpleperfTarget()?.let { target ->
        CaptureSetup(
            template = template,
            parameters = template.create(target),
        )
    }
}

private fun CaptureTarget.toSimpleperfTarget(): SimpleperfTarget =
    when (this) {
        is CaptureTarget.App -> SimpleperfTarget.App(packageName)
        is CaptureTarget.Process -> SimpleperfTarget.Process(pid)
        is CaptureTarget.Thread -> SimpleperfTarget.Thread(tid)
    }
