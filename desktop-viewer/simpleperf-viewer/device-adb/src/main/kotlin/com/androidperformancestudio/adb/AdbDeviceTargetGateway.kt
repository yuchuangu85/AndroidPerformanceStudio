package com.androidperformancestudio.adb

import com.androidperformancestudio.application.CapabilityStatus
import com.androidperformancestudio.application.CapabilitySummary
import com.androidperformancestudio.application.DeviceOption
import com.androidperformancestudio.application.DeviceSelection
import com.androidperformancestudio.application.DeviceTargetGateway
import com.androidperformancestudio.application.PackageOption
import com.androidperformancestudio.application.ProcessOption
import com.androidperformancestudio.application.ThreadOption
import com.androidperformancestudio.model.StudioResult
import java.nio.file.Path

class AdbDeviceTargetGateway private constructor(
    private val operations: GatewayOperations,
) : DeviceTargetGateway {
    constructor(adbExecutable: Path) : this(GatewayOperations.create(adbExecutable))

    internal constructor(
        refreshDevices: suspend () -> StudioResult<List<AdbDevice>>,
        readProperties: suspend (String) -> StudioResult<AndroidDeviceProperties>,
        detectCapabilities: suspend (AndroidDeviceProperties) -> StudioResult<DeviceCapabilities>,
        refreshTargets: suspend (String) -> StudioResult<AdbTargetSnapshot>,
        readThreads: suspend (String, Int) -> StudioResult<List<AndroidThread>>,
    ) : this(
        GatewayOperations(
            refreshDevices = refreshDevices,
            readProperties = readProperties,
            detectCapabilities = detectCapabilities,
            refreshTargets = refreshTargets,
            readThreads = readThreads,
        ),
    )

    override suspend fun refreshDevices(): StudioResult<List<DeviceOption>> =
        when (val result = operations.refreshDevices()) {
            is StudioResult.Failure -> result
            is StudioResult.Success -> StudioResult.Success(result.value.map(AdbDevice::toOption))
        }

    override suspend fun loadSelection(serial: String): StudioResult<DeviceSelection> =
        when (val result = operations.readProperties(serial)) {
            is StudioResult.Failure -> result
            is StudioResult.Success -> loadCapabilities(result.value)
        }

    override suspend fun loadThreads(
        serial: String,
        pid: Int,
    ): StudioResult<List<ThreadOption>> =
        when (val result = operations.readThreads(serial, pid)) {
            is StudioResult.Failure -> result
            is StudioResult.Success -> StudioResult.Success(result.value.map(AndroidThread::toOption))
        }

    private suspend fun loadCapabilities(properties: AndroidDeviceProperties): StudioResult<DeviceSelection> =
        when (val result = operations.detectCapabilities(properties)) {
            is StudioResult.Failure -> result
            is StudioResult.Success -> loadTargets(properties, result.value)
        }

    private suspend fun loadTargets(
        properties: AndroidDeviceProperties,
        capabilities: DeviceCapabilities,
    ): StudioResult<DeviceSelection> =
        when (val result = operations.refreshTargets(properties.serial)) {
            is StudioResult.Failure -> result
            is StudioResult.Success ->
                StudioResult.Success(
                    DeviceSelection(
                        serial = properties.serial,
                        model = properties.model,
                        androidVersion = properties.androidVersion,
                        sdkInt = properties.sdkInt,
                        abis = properties.abis,
                        capabilities = capabilities.toSummary(),
                        packages = result.value.packages.map { PackageOption(it.packageName) },
                        processes = result.value.processes.map { ProcessOption(it.pid, it.name, it.user) },
                    ),
                )
        }
}

private data class GatewayOperations(
    val refreshDevices: suspend () -> StudioResult<List<AdbDevice>>,
    val readProperties: suspend (String) -> StudioResult<AndroidDeviceProperties>,
    val detectCapabilities: suspend (AndroidDeviceProperties) -> StudioResult<DeviceCapabilities>,
    val refreshTargets: suspend (String) -> StudioResult<AdbTargetSnapshot>,
    val readThreads: suspend (String, Int) -> StudioResult<List<AndroidThread>>,
) {
    companion object {
        fun create(adbExecutable: Path): GatewayOperations {
            val deviceRefresher = AdbDeviceRefresher(adbExecutable)
            val propertyReader = AdbDevicePropertiesReader(adbExecutable)
            val capabilityDetector = AdbDeviceCapabilityDetector(adbExecutable)
            val targetCatalog = AdbTargetCatalog(adbExecutable)
            return GatewayOperations(
                refreshDevices = { deviceRefresher.refresh() },
                readProperties = { serial -> propertyReader.read(serial) },
                detectCapabilities = { properties -> capabilityDetector.detect(properties) },
                refreshTargets = { serial -> targetCatalog.refresh(serial) },
                readThreads = { serial, pid -> targetCatalog.listThreads(serial, pid) },
            )
        }
    }
}

private fun AdbDevice.toOption(): DeviceOption =
    DeviceOption(
        serial = serial,
        label = model?.replace('_', ' ') ?: serial,
        isOnline = state == AdbDeviceState.ONLINE,
    )

private fun DeviceCapabilities.toSummary(): CapabilitySummary =
    CapabilitySummary(
        status = CapabilityStatus.valueOf(readiness.name),
        root = rootAccess.name,
        profilingScope = profilingScope.name,
        simpleperf =
            simpleperfVersion ?: if (DeviceCapabilityLimitation.SIMPLEPERF_UNAVAILABLE in limitations) {
                "Missing"
            } else {
                "Available"
            },
        eventNames = eventNames,
        limitations = limitations.map { it.name }.sorted(),
    )

private fun AndroidThread.toOption(): ThreadOption = ThreadOption(pid = pid, tid = tid, name = name)
