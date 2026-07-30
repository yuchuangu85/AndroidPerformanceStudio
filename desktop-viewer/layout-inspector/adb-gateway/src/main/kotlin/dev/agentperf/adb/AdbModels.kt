package com.androidperformancestudio.adb

enum class DeviceState {
    DEVICE,
    OFFLINE,
    UNAUTHORIZED,
    UNKNOWN,
}

data class AdbDevice(
    val serial: String,
    val state: DeviceState,
    val product: String? = null,
    val model: String? = null,
    val transportId: Int? = null,
)
