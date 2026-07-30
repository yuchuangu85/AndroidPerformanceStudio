package com.androidperformancestudio.desktop

import com.androidperformancestudio.adb.AdbDevice

data class DeviceChoiceModel(
    val serial: String,
    val label: String,
)

fun deviceChoices(devices: List<AdbDevice>): List<DeviceChoiceModel> =
    devices.map { device ->
        DeviceChoiceModel(
            serial = device.serial,
            label = listOfNotNull(
                device.model?.takeIf(String::isNotBlank),
                device.serial,
            ).joinToString(" · "),
        )
    }

fun sanitizeSelectedDeviceSerial(
    selectedSerial: String?,
    devices: List<AdbDevice>,
): String? = selectedSerial?.takeIf { serial -> devices.any { it.serial == serial } }
