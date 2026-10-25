package com.androidperformancestudio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
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

/** The two target controls that can be moved into the macOS window title strip. */
public enum class TitleBarTargetSlot {
    DEVICE,
    PROCESS,
}

/** Identical control width on every page, including pages without a process target. */
public val TitleBarTargetWidth = 200.dp

internal data class TitleBarTargetRegistration(
    val owner: Any,
    val content: State<@Composable () -> Unit>,
)

internal data class TitleBarRefreshRegistration(
    val owner: Any,
    val enabled: State<Boolean>,
    val onRefresh: State<() -> Unit>,
)

/** Active page-owned controls; retained inactive pages cannot replace the visible page's targets. */
@Stable
public class TitleBarTargetHost internal constructor() {
    private val registrations = mutableStateMapOf<TitleBarTargetSlot, TitleBarTargetRegistration>()
    internal var refreshRegistration: TitleBarRefreshRegistration? by mutableStateOf(null)
        private set

    public var selectedDeviceSerial: String? by mutableStateOf(null)
        private set
    public var selectedDeviceLabel: String? by mutableStateOf(null)
        private set
    public var selectedPackageName: String? by mutableStateOf(null)
        private set

    public val hasSelection: Boolean
        get() = selectedDeviceSerial != null || selectedPackageName != null

    public val hasTargets: Boolean
        get() = registrations.isNotEmpty()

    public fun chooseDevice(serial: String, label: String) {
        if (selectedDeviceSerial != serial) selectedPackageName = null
        selectedDeviceSerial = serial
        selectedDeviceLabel = label
    }

    public fun clearDevice() {
        selectedDeviceSerial = null
        selectedDeviceLabel = null
        selectedPackageName = null
    }

    public fun choosePackage(packageName: String) {
        selectedPackageName = packageName
    }

    public fun deviceReady(serial: String?): Boolean = selectedDeviceSerial == null || selectedDeviceSerial == serial

    public fun packageReady(packageName: String?): Boolean =
        selectedPackageName == null || selectedPackageName == packageName

    internal fun register(
        slot: TitleBarTargetSlot,
        owner: Any,
        content: State<@Composable () -> Unit>,
    ) {
        registrations[slot] = TitleBarTargetRegistration(owner, content)
    }

    internal fun unregister(
        slot: TitleBarTargetSlot,
        owner: Any,
    ) {
        if (registrations[slot]?.owner === owner) registrations.remove(slot)
    }

    internal fun registration(slot: TitleBarTargetSlot): TitleBarTargetRegistration? = registrations[slot]

    internal fun registerRefresh(registration: TitleBarRefreshRegistration) {
        refreshRegistration = registration
    }

    internal fun unregisterRefresh(owner: Any) {
        if (refreshRegistration?.owner === owner) refreshRegistration = null
    }
}

@Composable
public fun rememberTitleBarTargetHost(): TitleBarTargetHost = remember { TitleBarTargetHost() }

/** Null outside the macOS application shell, including standalone feature windows. */
public val LocalTitleBarTargetHost = staticCompositionLocalOf<TitleBarTargetHost?> { null }

/**
 * Publishes one existing selector to the title bar, or renders it unchanged at the call site.
 * The caller owns device/process state and callbacks in both modes.
 */
@Composable
@Suppress("FunctionName")
public fun TitleBarTargetOrInline(
    slot: TitleBarTargetSlot,
    content: @Composable () -> Unit,
) {
    val active = LocalWindowMenuBarActive.current
    val host = if (active) LocalTitleBarTargetHost.current else null
    if (host == null) {
        content()
        return
    }

    val owner = remember { Any() }
    val currentContent = rememberUpdatedState(content)
    DisposableEffect(host, slot, owner) {
        host.register(slot, owner, currentContent)
        onDispose { host.unregister(slot, owner) }
    }
}

/** Moves a page's device/target refresh action to the title bar while keeping its original inline UI elsewhere. */
@Composable
@Suppress("FunctionName")
public fun TitleBarRefreshOrInline(
    enabled: Boolean,
    onRefresh: () -> Unit,
    inline: @Composable () -> Unit,
) {
    val host = if (LocalWindowMenuBarActive.current) LocalTitleBarTargetHost.current else null
    if (host == null) {
        inline()
        return
    }

    val owner = remember { Any() }
    val currentEnabled = rememberUpdatedState(enabled)
    val currentRefresh = rememberUpdatedState(onRefresh)
    DisposableEffect(host, owner) {
        host.registerRefresh(TitleBarRefreshRegistration(owner, currentEnabled, currentRefresh))
        onDispose { host.unregisterRefresh(owner) }
    }
}

/** Keeps both slots in place while rendering selectors supplied by the visible page. */
@Composable
@Suppress("FunctionName")
public fun TitleBarTargetControls(
    host: TitleBarTargetHost,
    language: UiLanguage = UiLanguage.ENGLISH,
) {
    val device = host.registration(TitleBarTargetSlot.DEVICE)
    val process = host.registration(TitleBarTargetSlot.PROCESS)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.width(TitleBarTargetWidth)
                    .heightIn(min = ViewerDimensions.compactControlHeight)
                    .clipToBounds()
                    .testTag("titlebar-device-slot"),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (device != null) {
                key(device.owner) { device.content.value() }
            } else {
                DeviceDropdownSelector(
                    language = language,
                    items = emptyList<String>(),
                    selectedItem = host.selectedDeviceLabel,
                    onItemSelected = {},
                    itemLabel = { it },
                    modifier = Modifier.width(TitleBarTargetWidth),
                    fillWidth = true,
                    enabled = false,
                )
            }
        }
        Box(
            modifier =
                Modifier.width(TitleBarTargetWidth)
                    .heightIn(min = ViewerDimensions.compactControlHeight)
                    .clipToBounds()
                    .testTag("titlebar-process-slot"),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (process != null) {
                key(process.owner) { process.content.value() }
            } else {
                DropdownSelector(
                    items = emptyList<String>(),
                    selectedItem = host.selectedPackageName,
                    onItemSelected = {},
                    itemLabel = { it },
                    placeholder = if (language == UiLanguage.SIMPLIFIED_CHINESE) "进程" else "Process",
                    modifier = Modifier.width(TitleBarTargetWidth),
                    fillWidth = true,
                    enabled = false,
                )
            }
        }
        TitleBarRefreshControl(host.refreshRegistration, language)
    }
}

@Composable
@Suppress("FunctionName")
private fun TitleBarRefreshControl(
    refresh: TitleBarRefreshRegistration?,
    language: UiLanguage,
) {
    Box(
        modifier = Modifier.width(68.dp).testTag("titlebar-refresh-slot"),
        contentAlignment = Alignment.CenterEnd,
    ) {
        ProfilerCompactButton(
            text = if (language == UiLanguage.SIMPLIFIED_CHINESE) "刷新" else "Refresh",
            onClick = { refresh?.onRefresh?.value?.invoke() },
            enabled = refresh?.enabled?.value == true,
            modifier = Modifier.width(68.dp),
        )
    }
}
