@file:Suppress("FunctionNaming", "TooManyFunctions")

package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.application.CapabilityStatus
import com.androidperformancestudio.application.CaptureTarget
import com.androidperformancestudio.application.DeviceOption
import com.androidperformancestudio.application.DeviceSelection
import com.androidperformancestudio.application.DeviceTargetState

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun DeviceTargetPage(
    state: DeviceTargetState,
    actions: DeviceTargetActions,
    darkTheme: Boolean,
) {
    val style = macOsDeviceTargetStyle(darkTheme)
    Column(Modifier.fillMaxSize().background(style.workspace)) {
        WorkspaceToolbar(state.isLoading, actions.onRefresh, style)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sidebarWidth =
                if (maxWidth < COMPACT_WORKSPACE_WIDTH) {
                    MacOsDeviceTargetDimensions.compactSidebarWidth
                } else {
                    MacOsDeviceTargetDimensions.sidebarWidth
                }
            Row(Modifier.fillMaxSize()) {
                DeviceSidebar(
                    devices = state.devices,
                    selectedSerial = state.selectedSerial,
                    onSelectDevice = actions.onSelectDevice,
                    style = style,
                    modifier = Modifier.width(sidebarWidth).fillMaxHeight(),
                )
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    CapabilityInspector(state, style)
                    TargetBrowser(state, actions, style, Modifier.weight(1f))
                    WorkspaceFooter(state, actions.onContinue, style)
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun WorkspaceToolbar(
    isLoading: Boolean,
    onRefresh: () -> Unit,
    style: MacOsDeviceTargetStyle,
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
                ).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                "Device & Target",
                color = style.text,
                fontSize = 15.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Select an Android device and a profile target.",
                color = style.secondaryText,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )
        }
        MacOsButton(if (isLoading) "Refreshing…" else "Refresh", onRefresh, style, enabled = !isLoading)
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun DeviceSidebar(
    devices: List<DeviceOption>,
    selectedSerial: String?,
    onSelectDevice: (String) -> Unit,
    style: MacOsDeviceTargetStyle,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .background(style.sidebar)
                .border(
                    MacOsDeviceTargetDimensions.hairline,
                    style.border,
                    RoundedCornerShape(0.dp),
                ).padding(top = 14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Devices",
                modifier = Modifier.weight(1f),
                color = style.secondaryText,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
            Text(devices.size.toString(), color = style.secondaryText, fontSize = 11.sp, lineHeight = 13.sp)
        }
        Spacer(Modifier.height(4.dp))
        if (devices.isEmpty()) {
            EmptyDeviceList(style)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(devices, key = DeviceOption::serial) { device ->
                    DeviceSidebarRow(
                        device = device,
                        selected = device.serial == selectedSerial,
                        style = style,
                        onClick = { onSelectDevice(device.serial) },
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun EmptyDeviceList(style: MacOsDeviceTargetStyle) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DeviceGlyph(style.secondaryText)
        Text("No USB devices found.", color = style.secondaryText, fontSize = 12.sp, lineHeight = 15.sp)
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun DeviceSidebarRow(
    device: DeviceOption,
    selected: Boolean,
    style: MacOsDeviceTargetStyle,
    onClick: () -> Unit,
) {
    val availability = localizedSimpleperfText(if (device.isOnline) "Online" else "Unavailable")
    val background = if (selected) style.accent else androidx.compose.ui.graphics.Color.Transparent
    val primary = if (selected) style.accentText else style.text
    val secondary = if (selected) style.accentText.copy(alpha = SELECTED_SECONDARY_ALPHA) else style.secondaryText
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(MacOsDeviceTargetDimensions.deviceRowHeight)
                .clip(RoundedCornerShape(MacOsDeviceTargetDimensions.controlRadius))
                .background(background)
                .semantics {
                    this.selected = selected
                    stateDescription = availability
                }.clickable(enabled = device.isOnline, onClick = onClick)
                .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeviceGlyph(primary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                device.label,
                color = primary,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                device.serial,
                color = secondary,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (device.isOnline) style.online else style.secondaryText)
                Spacer(Modifier.width(4.dp))
                Text(
                    if (device.isOnline) "Online" else "Unavailable",
                    color = secondary,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                )
            }
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun DeviceGlyph(color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .size(width = 16.dp, height = 24.dp)
            .border(1.2.dp, color, RoundedCornerShape(3.dp))
            .padding(bottom = 3.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(Modifier.width(5.dp).height(1.dp).background(color, RoundedCornerShape(1.dp)))
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun StatusDot(color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(6.dp).background(color, CircleShape))
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun CapabilityInspector(
    state: DeviceTargetState,
    style: MacOsDeviceTargetStyle,
) {
    val selection = state.selection
    Column(
        Modifier
            .fillMaxWidth()
            .height(MacOsDeviceTargetDimensions.capabilityHeight)
            .background(style.workspace)
            .border(
                MacOsDeviceTargetDimensions.hairline,
                style.border,
                RoundedCornerShape(0.dp),
            ).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            "Device capability",
            color = style.secondaryText,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
        )
        if (selection == null) {
            Row(
                Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Select an online device to inspect its capabilities.",
                    color = style.secondaryText,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                )
            }
        } else {
            SelectedCapabilitySummary(selection, style)
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun SelectedCapabilitySummary(
    selection: DeviceSelection,
    style: MacOsDeviceTargetStyle,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            selection.model,
            modifier = Modifier.weight(1f),
            color = style.text,
            fontSize = 15.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        CapabilityStatusBadge(selection.capabilities.status, style)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        CapabilityFact("Android", "${selection.androidVersion} / SDK ${selection.sdkInt}", style)
        CapabilityFact("ABI", selection.abis.joinToString(), style)
        CapabilityFact("Root", selection.capabilities.root, style)
        CapabilityFact("Scope", selection.capabilities.profilingScope, style)
        CapabilityFact("Simpleperf", selection.capabilities.simpleperf, style)
    }
    val eventSummary =
        selection.capabilities.eventNames
            .take(MAX_VISIBLE_EVENTS)
            .joinToString()
            .ifBlank { "Unavailable" }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Events: $eventSummary",
            modifier = Modifier.weight(1f),
            color = style.secondaryText,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selection.capabilities.limitations.isNotEmpty()) {
            Text(
                "Limits: ${selection.capabilities.limitations.joinToString()}",
                color = style.warning,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun CapabilityFact(
    label: String,
    value: String,
    style: MacOsDeviceTargetStyle,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label:", color = style.secondaryText, fontSize = 10.sp, lineHeight = 12.sp)
        Text(value, color = style.text, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium)
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
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun TargetBrowser(
    state: DeviceTargetState,
    actions: DeviceTargetActions,
    style: MacOsDeviceTargetStyle,
    modifier: Modifier,
) {
    Column(modifier.background(style.panel)) {
        TargetBrowserHeader(state.searchQuery, actions.onSearch, style)
        HorizontalHairline(style.border)
        Row(Modifier.fillMaxSize()) {
            TargetColumn("Apps", state.visiblePackages.size, style, Modifier.weight(1f)) {
                items(state.visiblePackages, key = { it.packageName }) { item ->
                    TargetRow(
                        title = item.packageName,
                        selected = state.selectedTarget == CaptureTarget.App(item.packageName),
                        style = style,
                        onClick = { actions.onSelectPackage(item.packageName) },
                    )
                }
            }
            VerticalHairline(style.border)
            TargetColumn("Processes", state.visibleProcesses.size, style, Modifier.weight(1f)) {
                items(state.visibleProcesses, key = { it.pid }) { item ->
                    TargetRow(
                        title = item.name,
                        subtitle = "PID ${item.pid} · ${item.user}",
                        selected = (state.selectedTarget as? CaptureTarget.Process)?.pid == item.pid,
                        style = style,
                        onClick = { actions.onSelectProcess(item.pid) },
                    )
                }
            }
            VerticalHairline(style.border)
            TargetColumn("Threads", state.threads.size, style, Modifier.weight(1f)) {
                items(state.threads, key = { it.tid }) { item ->
                    TargetRow(
                        title = item.name,
                        subtitle = "TID ${item.tid}",
                        selected = (state.selectedTarget as? CaptureTarget.Thread)?.tid == item.tid,
                        style = style,
                        onClick = { actions.onSelectThread(item) },
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun TargetBrowserHeader(
    searchQuery: String,
    onSearch: (String) -> Unit,
    style: MacOsDeviceTargetStyle,
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Profile target",
                color = style.text,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Choose an app, process, or thread to profile.",
                color = style.secondaryText,
                fontSize = 10.sp,
                lineHeight = 12.sp,
            )
        }
        TargetSearchField(searchQuery, onSearch, style)
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun TargetSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    style: MacOsDeviceTargetStyle,
) {
    val description = localizedSimpleperfText("Search package, process, user or PID")
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            Modifier
                .width(280.dp)
                .height(MacOsDeviceTargetDimensions.searchHeight)
                .semantics { contentDescription = description },
        singleLine = true,
        textStyle = TextStyle(color = style.text, fontSize = 11.sp),
        cursorBrush = SolidColor(style.accent),
        decorationBox = { innerTextField ->
            Row(
                Modifier
                    .fillMaxSize()
                    .background(style.field, RoundedCornerShape(MacOsDeviceTargetDimensions.controlRadius))
                    .border(
                        MacOsDeviceTargetDimensions.hairline,
                        style.strongBorder,
                        RoundedCornerShape(MacOsDeviceTargetDimensions.controlRadius),
                    ).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(SEARCH_GLYPH, color = style.secondaryText, fontSize = 12.sp, lineHeight = 14.sp)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            "Search package, process, user or PID",
                            color = style.secondaryText,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
            }
        },
    )
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun TargetColumn(
    title: String,
    count: Int,
    style: MacOsDeviceTargetStyle,
    modifier: Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(style.workspace)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                color = style.text,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(count.toString(), color = style.secondaryText, fontSize = 10.sp, lineHeight = 12.sp)
        }
        HorizontalHairline(style.border)
        LazyColumn(Modifier.fillMaxSize().padding(vertical = 4.dp), content = content)
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun TargetRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    style: MacOsDeviceTargetStyle,
    onClick: () -> Unit,
) {
    val selectedDescription = localizedSimpleperfText("Selected")
    val background = if (selected) style.accent else androidx.compose.ui.graphics.Color.Transparent
    val primary = if (selected) style.accentText else style.text
    val secondary = if (selected) style.accentText.copy(alpha = SELECTED_SECONDARY_ALPHA) else style.secondaryText
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(MacOsDeviceTargetDimensions.targetRowHeight)
                .padding(horizontal = 5.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(background)
                .semantics {
                    this.selected = selected
                    if (selected) stateDescription = selectedDescription
                }.clickable(onClick = onClick)
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = primary,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it,
                    color = secondary,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun WorkspaceFooter(
    state: DeviceTargetState,
    onContinue: () -> Unit,
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
        state.error?.let {
            StatusDot(style.error)
            Spacer(Modifier.width(6.dp))
            Text(
                "${it.code}: ${it.message}",
                modifier = Modifier.weight(1f),
                color = style.error,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } ?: Spacer(Modifier.weight(1f))
        MacOsButton(
            label = "Continue to Capture",
            onClick = onContinue,
            style = style,
            enabled = state.canEnterCapture,
            primary = true,
        )
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun MacOsButton(
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
                .padding(horizontal = 12.dp),
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
private fun HorizontalHairline(color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.fillMaxWidth().height(MacOsDeviceTargetDimensions.hairline).background(color))
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun VerticalHairline(color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.fillMaxHeight().width(MacOsDeviceTargetDimensions.hairline).background(color))
}

private const val MAX_VISIBLE_EVENTS = 8
private const val SELECTED_SECONDARY_ALPHA = 0.78f
private const val STATUS_FILL_ALPHA = 0.16f
private const val DISABLED_CONTAINER_ALPHA = 0.55f
private const val DISABLED_CONTENT_ALPHA = 0.48f
private const val SEARCH_GLYPH = "⌕"
private val COMPACT_WORKSPACE_WIDTH = 920.dp
