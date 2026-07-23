package com.androidperformancestudio.adb

data class AdbDevice(
    val serial: String,
    val model: String,
    val state: AdbDeviceState,
    val androidSdk: Int = 0,
)

enum class AdbDeviceState {
    ONLINE,
    OFFLINE,
    UNAUTHORIZED,
    NO_PERMISSIONS,
}
