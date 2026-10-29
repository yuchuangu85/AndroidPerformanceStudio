package com.androidperformancestudio.battery.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.androidperformancestudio.battery.battery_app.generated.resources.Res
import com.androidperformancestudio.battery.battery_app.generated.resources.app_uid
import com.androidperformancestudio.battery.battery_app.generated.resources.package_uid
import com.androidperformancestudio.battery.battery_app.generated.resources.package_uid_shared
import com.androidperformancestudio.battery.model.BatteryTarget
import com.androidperformancestudio.battery.presentation.BatteryProfilerState
import com.androidperformancestudio.ui.ActiveTitleBarTarget
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.LocalTitleBarTargetHost
import com.androidperformancestudio.ui.LocalWindowMenuBarActive
import com.androidperformancestudio.ui.TitleBarPageTarget
import com.androidperformancestudio.ui.TitleBarPageTargetOption
import com.androidperformancestudio.ui.TitleBarTargetHost
import com.androidperformancestudio.ui.TitleBarTargetOrInline
import com.androidperformancestudio.ui.TitleBarTargetSlot
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

/** Publishes the App/UID target in Process, retaining an inline selector outside the hosted window. */
@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
internal fun BatteryAppUidSelector(
    state: BatteryProfilerState,
    onSelectTarget: (String) -> Unit,
    language: UiLanguage,
) {
    val host = LocalTitleBarTargetHost.current.takeIf { LocalWindowMenuBarActive.current }
    val enabled = !state.isRunning && !state.isRefreshing && !state.isLoadingSession
    LaunchedEffect(
        host,
        host?.selectedDeviceSerial,
        host?.selectedPackageName,
        host?.selectedProcessPid,
        state.selectedDeviceSerial,
        state.selectedPackageName,
        state.targets,
        enabled,
    ) {
        if (enabled) syncBatteryAppTarget(host, state, onSelectTarget)
    }
    ActiveTitleBarTarget(
        TitleBarPageTarget(
            serial = state.selectedDeviceSerial,
            options = state.targets.map { TitleBarPageTargetOption(it.packageName, it.selectorLabel(language)) },
            selectedId = state.selectedPackageName,
            enabled = enabled,
            onSelectTarget = { packageName ->
                host?.choosePackage(packageName, clearProcess = true)
                onSelectTarget(packageName)
            },
        ),
    )
    TitleBarTargetOrInline(TitleBarTargetSlot.PROCESS) {
        DropdownSelector(
            items = state.targets,
            searchable = true,
            searchLanguage = language,
            selectedItem = state.targets.firstOrNull { it.packageName == state.selectedPackageName },
            onItemSelected = { onSelectTarget(it.packageName) },
            itemLabel = { it.selectorLabel(language) },
            placeholder = localizedStringResource(Res.string.app_uid, language),
            selectorDescription = localizedStringResource(Res.string.app_uid, language),
            enabled = enabled && state.selectedDeviceSerial != null,
        )
    }
}

private fun syncBatteryAppTarget(
    host: TitleBarTargetHost?,
    state: BatteryProfilerState,
    onSelectTarget: (String) -> Unit,
) {
    if (host == null || state.selectedDeviceSerial == null) return
    if (host.selectedDeviceSerial != state.selectedDeviceSerial) return
    val sharedPackage = host.selectedPackageName
    val target =
        state.targets.firstOrNull { it.packageName == sharedPackage }
            ?: state.targets.firstOrNull { it.packageName == state.selectedPackageName }
    target?.let {
        host.choosePackage(it.packageName, clearProcess = true)
        if (it.packageName != state.selectedPackageName) onSelectTarget(it.packageName)
    }
}

private fun BatteryTarget.selectorLabel(language: UiLanguage): String =
    localizedStringResource(
        if (sharedUid) Res.string.package_uid_shared else Res.string.package_uid,
        language,
        packageName,
        uid,
    )
