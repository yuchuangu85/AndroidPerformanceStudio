package com.androidperformancestudio.desktop

import com.androidperformancestudio.platform.adb.AndroidDeviceInfo

data class DeviceChoiceModel(
    val serial: String,
    val label: String,
)

fun deviceChoices(devices: List<AndroidDeviceInfo>): List<DeviceChoiceModel> =
    devices.map { device ->
        DeviceChoiceModel(
            serial = device.serial,
            label = device.displayName,
        )
    }

fun sanitizeSelectedDeviceSerial(
    selectedSerial: String?,
    devices: List<AndroidDeviceInfo>,
): String? =
    selectedSerial?.takeIf { serial -> devices.any { it.serial == serial && it.online } }
        ?: devices.filter(AndroidDeviceInfo::online).singleOrNull()?.serial
