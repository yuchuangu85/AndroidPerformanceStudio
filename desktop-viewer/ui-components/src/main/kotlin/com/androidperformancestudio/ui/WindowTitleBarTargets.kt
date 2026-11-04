package com.androidperformancestudio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The two application-wide target controls shown in the macOS window title strip. */
public enum class TitleBarTargetSlot {
    DEVICE,
    PROCESS,
}

/** Identical control width for both title-bar targets. */
public val TitleBarTargetWidth = 200.dp

/** Combined width of the fixed title-bar device and process controls, including their gap. */
public val TitleBarTargetControlsWidth = TitleBarTargetWidth * 2 + 8.dp

/** Device entry shown by the fixed title-bar device selector. */
public data class TitleBarDeviceOption(
    val serial: String,
    val label: String,
    val online: Boolean = true,
)

/** Process entry shown by the fixed title-bar process selector. */
public data class TitleBarProcessOption(
    val pid: Int,
    val name: String,
    val packageName: String? = null,
    val user: String = "",
)

/** A page-owned app or component entry, with identity independent of its display label. */
public data class TitleBarPageTargetOption(
    val id: String,
    val label: String = id,
    val secondary: String? = null,
)

/** An active page's capture target, rendered in the existing Process slot. */
public data class TitleBarPageTarget(
    val serial: String?,
    val options: List<TitleBarPageTargetOption>,
    val selectedId: String?,
    val enabled: Boolean,
    val onSelectTarget: (String) -> Unit,
)

/**
 * Application-wide device and process target rendered in the macOS window title strip.
 *
 * The shell refreshes [devices] and [processes] on a timer, so both title-bar controls stay in
 * place while the active page changes. Pages read the shared selection and follow it in their own
 * controllers. A page may use the Process slot for an application or component capture target while active.
 */
@Stable
public class TitleBarTargetHost {
    public var pageTarget: TitleBarPageTarget? by mutableStateOf(null)
        internal set

    public var devices: List<TitleBarDeviceOption> by mutableStateOf(emptyList())
        private set
    public var processes: List<TitleBarProcessOption> by mutableStateOf(emptyList())
        private set

    public var selectedDeviceSerial: String? by mutableStateOf(null)
        private set
    public var selectedDeviceLabel: String? by mutableStateOf(null)
        private set
    public var selectedProcessPid: Int? by mutableStateOf(null)
        private set
    public var selectedProcessName: String? by mutableStateOf(null)
        private set
    public var selectedPackageName: String? by mutableStateOf(null)
        private set

    /** Replaces the discovered devices, keeping the selected device while it is still connected. */
    public fun updateDevices(discovered: List<TitleBarDeviceOption>) {
        devices = discovered
        val selected = discovered.firstOrNull { it.serial == selectedDeviceSerial }
        when {
            selected == null -> {
                val automatic = discovered.filter(TitleBarDeviceOption::online).singleOrNull()
                if (automatic != null) selectDevice(automatic.serial) else clearDevice()
            }
            !selected.online -> clearDevice()
            else -> selectedDeviceLabel = selected.label
        }
    }

    /** Replaces the discovered processes of the selected device, dropping a selection that disappeared. */
    public fun updateProcesses(discovered: List<TitleBarProcessOption>) {
        processes = discovered
        val selected = discovered.firstOrNull { it.pid == selectedProcessPid }
        if (selected == null) {
            selectedProcessPid = null
            selectedProcessName = null
        } else {
            selectedPackageName = selected.packageName
        }
    }

    /** Selects a connected device; a device change invalidates the previously selected process. */
    public fun selectDevice(serial: String) {
        val device = devices.firstOrNull { it.serial == serial }
        if (selectedDeviceSerial != serial) {
            selectedProcessPid = null
            selectedProcessName = null
            selectedPackageName = null
            processes = emptyList()
        }
        selectedDeviceSerial = serial
        selectedDeviceLabel = device?.label ?: serial
    }

    /** Selects one of the discovered processes. */
    public fun selectProcess(pid: Int) {
        val process = processes.firstOrNull { it.pid == pid } ?: return
        selectedProcessPid = pid
        selectedProcessName = process.name
        selectedPackageName = process.packageName
    }

    /** Pages mirror the device they adopt so the fixed control keeps showing the real target. */
    public fun chooseDevice(
        serial: String,
        label: String,
    ) {
        if (selectedDeviceSerial != serial) {
            selectedProcessPid = null
            selectedProcessName = null
            selectedPackageName = null
        }
        selectedDeviceSerial = serial
        selectedDeviceLabel = label
    }

    public fun clearDevice() {
        selectedDeviceSerial = null
        selectedDeviceLabel = null
        selectedProcessPid = null
        selectedProcessName = null
        selectedPackageName = null
        processes = emptyList()
    }

    /** Pages mirror the process they adopt; the title-bar selection stays the shared target. */
    public fun chooseProcess(
        pid: Int,
        name: String,
        packageName: String?,
    ) {
        selectedProcessPid = pid
        selectedProcessName = name
        selectedPackageName = packageName
    }

    /** Mirrors an application context; app capture targets explicitly clear any stale PID. */
    public fun choosePackage(
        packageName: String?,
        clearProcess: Boolean = false,
    ) {
        if (clearProcess) {
            selectedProcessPid = null
            selectedProcessName = null
        }
        selectedPackageName = packageName
    }

    public fun deviceReady(serial: String?): Boolean = selectedDeviceSerial == null || selectedDeviceSerial == serial

    public fun packageReady(packageName: String?): Boolean = selectedPackageName.let { it == null || it == packageName }
}

@Composable
public fun rememberTitleBarTargetHost(): TitleBarTargetHost = remember { TitleBarTargetHost() }

/** Null outside the macOS application shell, including standalone feature windows. */
public val LocalTitleBarTargetHost = staticCompositionLocalOf<TitleBarTargetHost?> { null }

/** Uses the Process slot for [target] only while its page is active, including retained pages. */
@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
public fun ActiveTitleBarTarget(target: TitleBarPageTarget) {
    if (!LocalWindowMenuBarActive.current) return
    val host = LocalTitleBarTargetHost.current ?: return
    val current = rememberUpdatedState(target)
    SideEffect { host.pageTarget = current.value }
    DisposableEffect(host) {
        onDispose {
            if (host.pageTarget === current.value) host.pageTarget = null
        }
    }
}

/**
 * Keeps target selectors inline only when no title-bar host is available.
 */
@Composable
@Suppress("FunctionName", "UnusedParameter")
public fun TitleBarTargetOrInline(
    slot: TitleBarTargetSlot,
    content: @Composable () -> Unit,
) {
    if (LocalWindowMenuBarActive.current && LocalTitleBarTargetHost.current != null) return
    content()
}

/** The shell refreshes the targets automatically; the hosted title bar has no manual refresh action. */
@Composable
@Suppress("FunctionName", "UnusedParameter")
public fun TitleBarRefreshOrInline(
    enabled: Boolean,
    onRefresh: () -> Unit,
    inline: @Composable () -> Unit,
) {
    if (LocalWindowMenuBarActive.current && LocalTitleBarTargetHost.current != null) return
    inline()
}

/** Renders the fixed application-wide device and process selectors of the title bar. */
@Composable
@Suppress("FunctionName")
public fun TitleBarTargetControls(
    host: TitleBarTargetHost,
    language: UiLanguage = UiLanguage.ENGLISH,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .width(TitleBarTargetWidth)
                    .heightIn(min = ViewerDimensions.compactControlHeight)
                    .clipToBounds()
                    .testTag("titlebar-device-slot"),
            contentAlignment = Alignment.CenterEnd,
        ) {
            DeviceDropdownSelector(
                language = language,
                items = host.devices,
                selectedItem = host.devices.firstOrNull { it.serial == host.selectedDeviceSerial },
                onItemSelected = { device -> host.selectDevice(device.serial) },
                itemLabel = TitleBarDeviceOption::label,
                itemSecondary = { device -> if (device.online) null else offlineLabel(language) },
                itemEnabled = TitleBarDeviceOption::online,
                modifier = Modifier.width(TitleBarTargetWidth),
                fillWidth = true,
            )
        }
        Box(
            modifier =
                Modifier
                    .width(TitleBarTargetWidth)
                    .heightIn(min = ViewerDimensions.compactControlHeight)
                    .clipToBounds()
                    .testTag("titlebar-process-slot"),
            contentAlignment = Alignment.CenterEnd,
        ) {
            val pageTarget = host.pageTarget
            if (pageTarget != null) {
                PageTargetSelector(host, pageTarget, language)
            } else {
                ProcessTargetSelector(host, language)
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
private fun PageTargetSelector(
    host: TitleBarTargetHost,
    target: TitleBarPageTarget,
    language: UiLanguage,
) {
    val deviceReady = host.selectedDeviceSerial != null && target.serial == host.selectedDeviceSerial
    DropdownSelector(
        items = target.options,
        searchable = true,
        searchLanguage = language,
        selectedItem = target.options.firstOrNull { it.id == target.selectedId },
        onItemSelected = { option ->
            val active = host.pageTarget === target && target.enabled
            val deviceStillReady = host.selectedDeviceSerial != null && target.serial == host.selectedDeviceSerial
            if (active && deviceStillReady && option in target.options) {
                target.onSelectTarget(option.id)
            }
        },
        itemLabel = TitleBarPageTargetOption::label,
        itemSecondary = { it.secondary },
        itemSearchText = { "${it.label} ${it.id} ${it.secondary.orEmpty()}" },
        placeholder = if (language == UiLanguage.SIMPLIFIED_CHINESE) "进程" else "Process",
        selectorDescription = if (language == UiLanguage.SIMPLIFIED_CHINESE) "进程选择器" else "Process selector",
        modifier = Modifier.width(TitleBarTargetWidth),
        fillWidth = true,
        enabled = target.enabled && target.options.isNotEmpty() && deviceReady,
        menuModifier = Modifier.widthIn(min = 240.dp, max = 380.dp).heightIn(max = 360.dp),
    )
}

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
private fun ProcessTargetSelector(
    host: TitleBarTargetHost,
    language: UiLanguage,
) {
    DropdownSelector(
        items = host.processes,
        selectedItem = host.processes.firstOrNull { it.pid == host.selectedProcessPid },
        onItemSelected = { process -> host.selectProcess(process.pid) },
        itemLabel = { process -> "${process.name} (${process.pid})" },
        selectedItemLabel = TitleBarProcessOption::name,
        placeholder = if (language == UiLanguage.SIMPLIFIED_CHINESE) "进程" else "Process",
        itemSecondary = { process -> "PID ${process.pid} · ${process.user}" },
        selectorDescription = if (language == UiLanguage.SIMPLIFIED_CHINESE) "进程选择器" else "Process selector",
        searchable = true,
        searchLanguage = language,
        itemSearchText = { process -> "${process.name} ${process.pid} ${process.user}" },
        modifier = Modifier.width(TitleBarTargetWidth),
        fillWidth = true,
        enabled = host.selectedDeviceSerial != null,
        menuModifier = Modifier.widthIn(min = 240.dp, max = 380.dp).heightIn(max = 360.dp),
    )
}

private fun offlineLabel(ui: UiLanguage): String = if (ui == UiLanguage.SIMPLIFIED_CHINESE) "离线" else "Offline"
