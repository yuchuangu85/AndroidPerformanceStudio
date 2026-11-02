package com.androidperformancestudio.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.androidperformancestudio.platform.adb.AndroidDeviceInfo
import com.androidperformancestudio.ui.TitleBarDeviceOption
import com.androidperformancestudio.ui.TitleBarTargetHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal const val INSPECTOR_DEVICE_POLL_MILLIS = 2_000L

/** Hosted pages use the shell's live discovery; standalone windows own their polling. */
@Composable
internal fun rememberInspectorDeviceOptions(
    host: TitleBarTargetHost?,
    refreshRequest: Int,
    discover: suspend () -> List<AndroidDeviceInfo>,
): List<TitleBarDeviceOption> {
    var standaloneDevices by remember { mutableStateOf<List<TitleBarDeviceOption>>(emptyList()) }
    val currentDiscover by rememberUpdatedState(discover)
    LaunchedEffect(host, refreshRequest) {
        if (host != null) return@LaunchedEffect
        while (currentCoroutineContext().isActive) {
            standaloneDevices = withContext(Dispatchers.IO) {
                runInspectorCatching { currentDiscover() }.getOrDefault(emptyList()).map { device ->
                    TitleBarDeviceOption(device.serial, device.displayName, device.online)
                }
            }
            delay(INSPECTOR_DEVICE_POLL_MILLIS)
        }
    }
    return host?.devices ?: standaloneDevices
}

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

internal fun selectedInspectorDeviceSerial(
    selectedSerial: String?,
    devices: List<TitleBarDeviceOption>,
): String? =
    selectedSerial?.takeIf { serial -> devices.any { it.serial == serial && it.online } }
        ?: devices.filter(TitleBarDeviceOption::online).singleOrNull()?.serial
