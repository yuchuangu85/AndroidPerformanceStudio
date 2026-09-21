package com.androidperformancestudio.desktop

import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.platform.adb.displayName

data class DeviceChoiceModel(
    val serial: String,
    val label: String,
)

fun deviceChoices(devices: List<AdbDevice>): List<DeviceChoiceModel> =
    devices.map { device ->
        DeviceChoiceModel(
            serial = device.serial,
            label = device.displayName(),
        )
    }

fun sanitizeSelectedDeviceSerial(
    selectedSerial: String?,
    devices: List<AdbDevice>,
): String? =
    selectedSerial?.takeIf { serial -> devices.any { it.serial == serial && it.state == AdbDeviceState.ONLINE } }
        ?: devices.filter { it.state == AdbDeviceState.ONLINE }.singleOrNull()?.serial
