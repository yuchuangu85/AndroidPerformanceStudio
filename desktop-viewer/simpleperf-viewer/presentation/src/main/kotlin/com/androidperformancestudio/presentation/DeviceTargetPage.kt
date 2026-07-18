@file:Suppress("FunctionNaming", "TooManyFunctions", "LongParameterList")

package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.application.CapabilityStatus
import com.androidperformancestudio.application.CaptureTarget
import com.androidperformancestudio.application.DeviceOption
import com.androidperformancestudio.application.DeviceSelection
import com.androidperformancestudio.application.DeviceTargetState
import com.androidperformancestudio.application.PackageOption
import com.androidperformancestudio.application.ProcessOption
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.ThreadOption
import com.androidperformancestudio.capture.CaptureState

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun DeviceTargetPage(
    state: DeviceTargetState,
    captureState: CaptureState,
    reportState: ReportState,
    actions: DeviceTargetActions,
    reportActions: ReportActions,
    darkTheme: Boolean,
    settingsSection: CaptureSettingsSection?,
    onSettingsSectionChange: (CaptureSettingsSection?) -> Unit,
    flameTooltipMode: FlameTooltipMode = FlameTooltipMode.FIXED,
    onFlameTooltipModeChange: (FlameTooltipMode) -> Unit = {},
    simpleperfEngine: SimpleperfEngine = SimpleperfEngine.LOCAL,
    onSimpleperfEngineChange: (SimpleperfEngine) -> Unit = {},
) {
    val style = macOsDeviceTargetStyle(darkTheme)
    val captureActive = captureState.isCaptureActive()
    Column(Modifier.fillMaxSize().background(style.workspace)) {
        WorkspaceToolbar(
            state = state,
            actions = actions,
            style = style,
            enabled = !captureActive && !state.isLoading,
            showGetData = !captureActive,
            onOpenSettings = { onSettingsSectionChange(CaptureSettingsSection.SAMPLING_TEMPLATE) },
        )
        if (reportState.loadState == ReportLoadState.Closed) {
            Spacer(Modifier.weight(1f))
        } else {
            ReportWorkspace(
                state = reportState,
                actions = reportActions,
                style = style,
                modifier = Modifier.weight(1f),
                flameTooltipMode = flameTooltipMode,
            )
        }
        WorkspaceFooter(state, captureState, reportState, actions, style)
    }
    settingsSection?.let { section ->
        CaptureSettingsDialog(
            section = section,
            setup = state.captureSetup,
            availableEvents =
                state.selection
                    ?.capabilities
                    ?.eventNames
                    .orEmpty(),
            style = style,
            enabled = !captureActive,
            onSectionChange = { onSettingsSectionChange(it) },
            onSelectTemplate = actions.onSelectTemplate,
            onUpdate = actions.onUpdateSamplingParameters,
            onDismiss = { onSettingsSectionChange(null) },
            flameTooltipMode = flameTooltipMode,
            onFlameTooltipModeChange = onFlameTooltipModeChange,
            simpleperfEngine = simpleperfEngine,
            onSimpleperfEngineChange = onSimpleperfEngineChange,
        )
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun WorkspaceToolbar(
    state: DeviceTargetState,
    actions: DeviceTargetActions,
    style: MacOsDeviceTargetStyle,
    enabled: Boolean,
    showGetData: Boolean,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(MacOsDeviceTargetDimensions.toolbarHeight)
                .background(style.toolbar)
                .border(
                    MacOsDeviceTargetDimensions.hairline,
                    style.border,
                    RoundedCornerShape(0.dp),
                ).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ToolbarContent(state, actions, style, enabled, showGetData, onOpenSettings)
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun RowScope.ToolbarContent(
    state: DeviceTargetState,
    actions: DeviceTargetActions,
    style: MacOsDeviceTargetStyle,
    enabled: Boolean,
    showGetData: Boolean,
    onOpenSettings: () -> Unit,
) {
    val selectedDevice = state.devices.firstOrNull { it.serial == state.selectedSerial }
    val selectedThreadId = (state.selectedTarget as? CaptureTarget.Thread)?.tid
    DeviceSelector(
        selectedDevice,
        state.devices,
        actions.onSelectDevice,
        style,
        Modifier.weight(DEVICE_SELECTOR_WEIGHT),
        enabled,
    )
    AppSelector(
        state.selectedPackageName,
        state.selection?.packages.orEmpty(),
        actions.onSelectPackage,
        style,
        Modifier.weight(APP_SELECTOR_WEIGHT),
        enabled,
    )
    ProcessSelector(
        state.selectedProcessId,
        state.processesForSelectedPackage,
        actions.onSelectProcess,
        style,
        Modifier.weight(PROCESS_SELECTOR_WEIGHT),
        enabled,
    )
    ThreadSelector(
        selectedThreadId,
        state.threadsForSelectedProcess,
        actions.onSelectThread,
        style,
        Modifier.weight(THREAD_SELECTOR_WEIGHT),
        enabled,
    )
    Spacer(Modifier.width(2.dp))
    ToolbarCaptureActions(state, actions, style, enabled, showGetData)
    CapabilityPopupButton(state.selection, style)
    MacOsButton("Settings", onOpenSettings, style, enabled = enabled)
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun ToolbarCaptureActions(
    state: DeviceTargetState,
    actions: DeviceTargetActions,
    style: MacOsDeviceTargetStyle,
    enabled: Boolean,
    showGetData: Boolean,
) {
    MacOsButton(
        if (state.isLoading) "Refreshing…" else "Refresh",
        actions.onRefresh,
        style,
        enabled = enabled && !state.isLoading,
    )
    if (showGetData) {
        MacOsButton(
            label = "Get data",
            onClick = actions.onStartCapture,
            style = style,
            enabled = enabled && state.canEnterCapture && state.captureSetup != null,
            primary = true,
        )
    }
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun DeviceSelector(
    selected: DeviceOption?,
    devices: List<DeviceOption>,
    onSelect: (String) -> Unit,
    style: MacOsDeviceTargetStyle,
    modifier: Modifier,
    enabled: Boolean,
) {
    ToolbarSelector(
        label = "Device",
        selected = selected,
        items = devices,
        itemLabel = DeviceOption::label,
        itemSecondary = { device ->
            "${device.serial} · ${localizedSimpleperfText(if (device.isOnline) "Online" else "Unavailable")}"
        },
        itemEnabled = DeviceOption::isOnline,
        onSelect = { onSelect(it.serial) },
        style = style,
        modifier = modifier,
        enabled = enabled,
    )
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun AppSelector(
    selectedPackage: String?,
    packages: List<PackageOption>,
    onSelect: (String) -> Unit,
    style: MacOsDeviceTargetStyle,
    modifier: Modifier,
    enabled: Boolean,
) {
    ToolbarSelector(
        label = "App",
        selected = packages.firstOrNull { it.packageName == selectedPackage },
        items = packages,
        itemLabel = PackageOption::packageName,
        onSelect = { onSelect(it.packageName) },
        style = style,
        modifier = modifier,
        enabled = enabled,
    )
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun ProcessSelector(
    selectedPid: Int?,
    processes: List<ProcessOption>,
    onSelect: (Int) -> Unit,
    style: MacOsDeviceTargetStyle,
    modifier: Modifier,
    enabled: Boolean,
) {
    ToolbarSelector(
        label = "Process",
        selected = processes.firstOrNull { it.pid == selectedPid },
        items = processes,
        itemLabel = ProcessOption::name,
        itemSecondary = { "PID ${it.pid} · ${it.user}" },
        onSelect = { onSelect(it.pid) },
        style = style,
        modifier = modifier,
        enabled = enabled,
    )
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun ThreadSelector(
    selectedTid: Int?,
    threads: List<ThreadOption>,
    onSelect: (ThreadOption) -> Unit,
    style: MacOsDeviceTargetStyle,
    modifier: Modifier,
    enabled: Boolean,
) {
    ToolbarSelector(
        label = "Thread",
        selected = threads.firstOrNull { it.tid == selectedTid },
        items = threads,
        itemLabel = ThreadOption::name,
        itemSecondary = { "TID ${it.tid}" },
        onSelect = onSelect,
        style = style,
        modifier = modifier,
        enabled = enabled,
    )
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun <T> ToolbarSelector(
    label: String,
    selected: T?,
    items: List<T>,
    itemLabel: (T) -> String,
    onSelect: (T) -> Unit,
    style: MacOsDeviceTargetStyle,
    modifier: Modifier,
    enabled: Boolean,
    itemSecondary: (@Composable (T) -> String)? = null,
    itemEnabled: (T) -> Boolean = { true },
) {
    var expanded by remember { mutableStateOf(false) }
    val selectorDescription = localizedSimpleperfText("$label selector")
    val displayText = selected?.let(itemLabel) ?: localizedSimpleperfText(label)
    Box(modifier) {
        SelectorControl(
            displayText = displayText,
            selectorDescription = selectorDescription,
            hasSelection = selected != null,
            enabled = enabled && items.isNotEmpty(),
            onClick = { expanded = true },
            style = style,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier =
                Modifier
                    .widthIn(min = 220.dp, max = 340.dp)
                    .heightIn(max = 360.dp)
                    .background(style.panel),
        ) {
            items.forEach { item ->
                SelectorMenuItem(
                    label = itemLabel(item),
                    secondary = itemSecondary?.invoke(item),
                    selected = item == selected,
                    enabled = enabled && itemEnabled(item),
                    onClick = {
                        expanded = false
                        onSelect(item)
                    },
                    style = style,
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun SelectorControl(
    displayText: String,
    selectorDescription: String,
    hasSelection: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    style: MacOsDeviceTargetStyle,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(MacOsDeviceTargetDimensions.selectorHeight)
                .clip(RoundedCornerShape(MacOsDeviceTargetDimensions.controlRadius))
                .background(style.field)
                .border(
                    MacOsDeviceTargetDimensions.hairline,
                    style.strongBorder,
                    RoundedCornerShape(MacOsDeviceTargetDimensions.controlRadius),
                ).semantics {
                    contentDescription = selectorDescription
                    stateDescription = displayText
                }.clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            displayText,
            modifier = Modifier.weight(1f),
            color = if (hasSelection) style.text else style.secondaryText,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(DROPDOWN_GLYPH, color = style.secondaryText, fontSize = 10.sp, lineHeight = 12.sp)
    }
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun SelectorMenuItem(
    label: String,
    secondary: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    style: MacOsDeviceTargetStyle,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(
                    label,
                    color = style.text,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                secondary?.let {
                    Text(
                        it,
                        color = style.secondaryText,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        onClick = onClick,
        enabled = enabled,
        modifier =
            Modifier
                .height(if (secondary == null) 32.dp else 42.dp)
                .semantics { this.selected = selected },
    )
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun CapabilityPopupButton(
    selection: DeviceSelection?,
    style: MacOsDeviceTargetStyle,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        MacOsButton("Capabilities", { expanded = true }, style)
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(430.dp).background(style.panel),
        ) {
            CapabilityPopup(selection, style)
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun CapabilityPopup(
    selection: DeviceSelection?,
    style: MacOsDeviceTargetStyle,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Device capability",
            color = style.text,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (selection == null) {
            Text(
                "Select an online device to inspect its capabilities.",
                color = style.secondaryText,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        selection.model,
                        color = style.text,
                        fontSize = 14.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(selection.serial, color = style.secondaryText, fontSize = 10.sp, lineHeight = 12.sp)
                }
                CapabilityStatusBadge(selection.capabilities.status, style)
            }
            HorizontalHairline(style.border)
            CapabilityPopupFact("Android", "${selection.androidVersion} / SDK ${selection.sdkInt}", style)
            CapabilityPopupFact("ABI", selection.abis.joinToString(), style)
            CapabilityPopupFact("Root", selection.capabilities.root, style)
            CapabilityPopupFact("Scope", selection.capabilities.profilingScope, style)
            CapabilityPopupFact("Simpleperf", selection.capabilities.simpleperf, style)
            CapabilityPopupFact(
                "Events",
                selection.capabilities.eventNames
                    .take(MAX_VISIBLE_EVENTS)
                    .joinToString()
                    .ifBlank { "Unavailable" },
                style,
            )
            if (selection.capabilities.limitations.isNotEmpty()) {
                CapabilityPopupFact("Limits", selection.capabilities.limitations.joinToString(), style, warning = true)
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun CapabilityPopupFact(
    label: String,
    value: String,
    style: MacOsDeviceTargetStyle,
    warning: Boolean = false,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            modifier = Modifier.width(72.dp),
            color = style.secondaryText,
            fontSize = 10.sp,
            lineHeight = 13.sp,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            color = if (warning) style.warning else style.text,
            fontSize = 10.sp,
            lineHeight = 13.sp,
        )
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun CapabilityStatusBadge(
    status: CapabilityStatus,
    style: MacOsDeviceTargetStyle,
) {
    val color =
        when (status) {
            CapabilityStatus.READY -> style.online
            CapabilityStatus.LIMITED -> style.warning
            CapabilityStatus.BLOCKED -> style.error
        }
    Row(
        Modifier
            .background(color.copy(alpha = STATUS_FILL_ALPHA), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color)
        Spacer(Modifier.width(5.dp))
        Text(status.name, color = color, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun StatusDot(color: Color) {
    Box(Modifier.size(6.dp).background(color, CircleShape))
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun WorkspaceFooter(
    state: DeviceTargetState,
    captureState: CaptureState,
    reportState: ReportState,
    actions: DeviceTargetActions,
    style: MacOsDeviceTargetStyle,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(MacOsDeviceTargetDimensions.footerHeight)
            .background(style.toolbar)
            .border(
                MacOsDeviceTargetDimensions.hairline,
                style.border,
                RoundedCornerShape(0.dp),
            ).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CaptureStatus(state, captureState, reportState, style, Modifier.weight(1f))
        CaptureActions(captureState, actions, style)
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun CaptureStatus(
    state: DeviceTargetState,
    captureState: CaptureState,
    reportState: ReportState,
    style: MacOsDeviceTargetStyle,
    modifier: Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val error = state.error
        val color = if (error == null) style.online else style.error
        val fileInfo = reportState.footerFileInfo() ?: captureState.footerFileInfo()
        StatusDot(color)
        Spacer(Modifier.width(7.dp))
        Text(
            error?.let { "${it.code}: ${it.message}" } ?: captureState.statusText(),
            modifier = if (fileInfo == null) Modifier.weight(1f) else Modifier.widthIn(max = 240.dp),
            color = color,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        fileInfo?.let { info ->
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(MacOsDeviceTargetDimensions.hairline).height(14.dp).background(style.border))
            Spacer(Modifier.width(10.dp))
            Text(
                info.name,
                color = style.text,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                SelectionContainer {
                    Text(
                        info.path,
                        color = style.secondaryText,
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private data class FooterFileInfo(
    val name: String,
    val path: String,
)

private fun ReportState.footerFileInfo(): FooterFileInfo? =
    when (val current = loadState) {
        ReportLoadState.Closed -> null
        is ReportLoadState.Loading ->
            FooterFileInfo(
                current.sessionDirectory.fileName.toString(),
                current.sessionDirectory.toString(),
            )
        is ReportLoadState.Failed ->
            FooterFileInfo(
                current.sessionDirectory.fileName.toString(),
                current.sessionDirectory.toString(),
            )
        is ReportLoadState.Ready ->
            FooterFileInfo(
                current.report.session.name,
                current.report.session.directory
                    .toString(),
            )
    }

private fun CaptureState.footerFileInfo(): FooterFileInfo? =
    sessionPath()?.let { path ->
        FooterFileInfo(
            path.replace('\\', '/').substringAfterLast('/'),
            path,
        )
    }

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun CaptureActions(
    captureState: CaptureState,
    actions: DeviceTargetActions,
    style: MacOsDeviceTargetStyle,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (captureState.isCaptureActive()) {
            if (captureState is CaptureState.Recording) {
                MacOsButton("Stop and analyze", actions.onStopCapture, style, primary = true)
            }
            MacOsButton("Cancel", actions.onCancelCapture, style)
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun MacOsButton(
    label: String,
    onClick: () -> Unit,
    style: MacOsDeviceTargetStyle,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val container = if (primary) style.accent else style.panel
    val content = if (primary) style.accentText else style.text
    Box(
        modifier =
            Modifier
                .height(MacOsDeviceTargetDimensions.buttonHeight)
                .clip(RoundedCornerShape(MacOsDeviceTargetDimensions.controlRadius))
                .background(container.copy(alpha = if (enabled) 1f else DISABLED_CONTAINER_ALPHA))
                .border(
                    MacOsDeviceTargetDimensions.hairline,
                    if (primary) style.accent else style.strongBorder,
                    RoundedCornerShape(MacOsDeviceTargetDimensions.controlRadius),
                ).clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = content.copy(alpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun HorizontalHairline(color: Color) {
    Box(Modifier.fillMaxWidth().height(MacOsDeviceTargetDimensions.hairline).background(color))
}

private const val MAX_VISIBLE_EVENTS = 8
private const val STATUS_FILL_ALPHA = 0.16f
private const val DISABLED_CONTAINER_ALPHA = 0.55f
private const val DISABLED_CONTENT_ALPHA = 0.48f
private const val DROPDOWN_GLYPH = "⌄"
private const val DEVICE_SELECTOR_WEIGHT = 0.77f
private const val APP_SELECTOR_WEIGHT = 1.1f
private const val PROCESS_SELECTOR_WEIGHT = 1f
private const val THREAD_SELECTOR_WEIGHT = 0.95f
