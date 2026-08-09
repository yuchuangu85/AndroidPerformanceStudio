@file:Suppress("FunctionNaming", "TooManyFunctions", "LongParameterList")

package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.HeaderSpacer
import com.androidperformancestudio.ui.HeaderToolbar
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.ViewerDimensions
import com.androidperformancestudio.ui.button.MacOSTextButton
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.viewerColors

@Composable
@Suppress("FunctionName", "LongMethod", "ktlint:standard:function-naming")
internal fun DeviceTargetPage(
    state: DeviceTargetState,
    captureState: CaptureState,
    reportState: ReportState,
    actions: DeviceTargetActions,
    reportActions: ReportActions,
    darkTheme: Boolean,
    settingsSection: CaptureSettingsSection?,
    onSettingsSectionChange: (CaptureSettingsSection?) -> Unit,
    flameTooltipMode: FlameTooltipMode = FlameTooltipMode.FOLLOW_MOUSE,
    onFlameTooltipModeChange: (FlameTooltipMode) -> Unit = {},
    simpleperfEngine: SimpleperfEngine = SimpleperfEngine.LOCAL,
    onSimpleperfEngineChange: (SimpleperfEngine) -> Unit = {},
    onOpenUserGuide: (() -> Unit)? = null,
    onNavigateHome: (() -> Unit)? = null,
    onRunAiAnalysis: (() -> Unit)? = null,
) {
    val style = viewerColors(darkTheme)
    val captureActive = captureState.isCaptureActive()
    val selectedDevice = state.devices.firstOrNull { it.serial == state.selectedSerial }
    val selectedThreadId = (state.selectedTarget as? CaptureTarget.Thread)?.tid
    Column(Modifier.fillMaxSize().background(style.workspace)) {
        HeaderToolbar(
            language = currentSimpleperfLanguage(),
            onNavigateHome = onNavigateHome,
            onNavigateSettings = { onSettingsSectionChange(CaptureSettingsSection.SAMPLING_TEMPLATE) },
        ) {
            DeviceSelector(
                selectedDevice,
                state.devices,
                actions.onSelectDevice,
                style,
                Modifier.weight(DEVICE_SELECTOR_WEIGHT),
                !captureActive && !state.isLoading,
            )
            HeaderSpacer()
            AppSelector(
                state.selectedPackageName,
                state.selection?.packages.orEmpty(),
                actions.onSelectPackage,
                style,
                Modifier.weight(APP_SELECTOR_WEIGHT),
                !captureActive && !state.isLoading,
            )
            HeaderSpacer()
            ProcessSelector(
                state.selectedProcessId,
                state.processesForSelectedPackage,
                actions.onSelectProcess,
                style,
                Modifier.weight(PROCESS_SELECTOR_WEIGHT),
                !captureActive && !state.isLoading,
            )
            HeaderSpacer()
            ThreadSelector(
                selectedThreadId,
                state.threadsForSelectedProcess,
                actions.onSelectThread,
                style,
                Modifier.weight(THREAD_SELECTOR_WEIGHT),
                !captureActive && !state.isLoading,
            )
            HeaderSpacer()
            ToolbarCaptureActions(state, actions, style, !captureActive && !state.isLoading, !captureActive)
            HeaderSpacer()
            CapabilityPopupButton(state.selection, style)
        }
        if (reportState.loadState == ReportLoadState.Closed) {
            Spacer(Modifier.weight(1f))
        } else {
            ReportWorkspace(
                state = reportState,
                actions = reportActions,
                style = style,
                modifier = Modifier.weight(1f),
                flameTooltipMode = flameTooltipMode,
                onRunAiAnalysis = onRunAiAnalysis,
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
            onOpenUserGuide = onOpenUserGuide,
        )
    }
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun ToolbarCaptureActions(
    state: DeviceTargetState,
    actions: DeviceTargetActions,
    style: ViewerColors,
    enabled: Boolean,
    showGetData: Boolean,
) {
    val language = currentSimpleperfLanguage()
    MacOSTextButton(
        localizedStringResource(
            if (state.isLoading) SimpleperfViewerRes.sp_target_refreshing else SimpleperfViewerRes.sp_target_refresh,
            language,
        ),
        actions.onRefresh,
        style,
        enabled = enabled && !state.isLoading,
    )
    HeaderSpacer()
    if (showGetData) {
        MacOSTextButton(
            label = localizedStringResource(SimpleperfViewerRes.sp_capture_get_data, language),
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
    style: ViewerColors,
    modifier: Modifier,
    enabled: Boolean,
) {
    val language = currentSimpleperfLanguage()
    DropdownSelector(
        items = devices,
        selectedItem = selected,
        onItemSelected = { onSelect(it.serial) },
        itemLabel = DeviceOption::label,
        placeholder = localizedStringResource(SimpleperfViewerRes.sp_target_device, language),
        modifier = modifier,
        enabled = enabled,
        selectorDescription = localizedStringResource(SimpleperfViewerRes.sp_target_device_selector, language),
        colors = style,
        itemSecondary = { device ->
            val status =
                localizedStringResource(
                    if (device.isOnline) {
                        SimpleperfViewerRes.sp_target_online
                    } else {
                        SimpleperfViewerRes.sp_target_unavailable
                    },
                    currentSimpleperfLanguage(),
                )
            "${device.serial} · $status"
        },
        itemEnabled = DeviceOption::isOnline,
        fillWidth = true,
        menuModifier = selectorMenuModifier(),
    )
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun AppSelector(
    selectedPackage: String?,
    packages: List<PackageOption>,
    onSelect: (String) -> Unit,
    style: ViewerColors,
    modifier: Modifier,
    enabled: Boolean,
) {
    val language = currentSimpleperfLanguage()
    DropdownSelector(
        items = packages,
        selectedItem = packages.firstOrNull { it.packageName == selectedPackage },
        onItemSelected = { onSelect(it.packageName) },
        itemLabel = PackageOption::packageName,
        placeholder = localizedStringResource(SimpleperfViewerRes.sp_target_app, language),
        modifier = modifier,
        enabled = enabled,
        selectorDescription = localizedStringResource(SimpleperfViewerRes.sp_target_app_selector, language),
        colors = style,
        fillWidth = true,
        menuModifier = selectorMenuModifier(),
    )
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun ProcessSelector(
    selectedPid: Int?,
    processes: List<ProcessOption>,
    onSelect: (Int) -> Unit,
    style: ViewerColors,
    modifier: Modifier,
    enabled: Boolean,
) {
    val language = currentSimpleperfLanguage()
    DropdownSelector(
        items = processes,
        selectedItem = processes.firstOrNull { it.pid == selectedPid },
        onItemSelected = { onSelect(it.pid) },
        itemLabel = ProcessOption::name,
        placeholder = localizedStringResource(SimpleperfViewerRes.sp_target_process, language),
        modifier = modifier,
        enabled = enabled,
        selectorDescription = localizedStringResource(SimpleperfViewerRes.sp_target_process_selector, language),
        colors = style,
        itemSecondary = { "PID ${it.pid} · ${it.user}" },
        fillWidth = true,
        menuModifier = selectorMenuModifier(),
    )
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun ThreadSelector(
    selectedTid: Int?,
    threads: List<ThreadOption>,
    onSelect: (ThreadOption) -> Unit,
    style: ViewerColors,
    modifier: Modifier,
    enabled: Boolean,
) {
    val language = currentSimpleperfLanguage()
    DropdownSelector(
        items = threads,
        selectedItem = threads.firstOrNull { it.tid == selectedTid },
        onItemSelected = onSelect,
        itemLabel = ThreadOption::name,
        placeholder = localizedStringResource(SimpleperfViewerRes.sp_diagnostics_thread, language),
        modifier = modifier,
        enabled = enabled,
        selectorDescription = localizedStringResource(SimpleperfViewerRes.sp_target_thread_selector, language),
        colors = style,
        itemSecondary = { "TID ${it.tid}" },
        fillWidth = true,
        menuModifier = selectorMenuModifier(),
    )
}

private fun selectorMenuModifier(): Modifier =
    Modifier
        .widthIn(min = 220.dp, max = 340.dp)
        .heightIn(max = 360.dp)

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun CapabilityPopupButton(
    selection: DeviceSelection?,
    style: ViewerColors,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        MacOSTextButton(
            localizedStringResource(
                SimpleperfViewerRes.sp_target_capabilities,
                currentSimpleperfLanguage(),
            ),
            { expanded = true },
            style,
        )
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
@Suppress("FunctionName", "LongMethod", "ktlint:standard:function-naming")
private fun CapabilityPopup(
    selection: DeviceSelection?,
    style: ViewerColors,
) {
    val language = currentSimpleperfLanguage()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            localizedStringResource(SimpleperfViewerRes.sp_target_device_capability, language),
            color = style.text,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (selection == null) {
            Text(
                localizedStringResource(SimpleperfViewerRes.sp_target_capability_selection_hint, language),
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
            CapabilityPopupFact(
                localizedStringResource(SimpleperfViewerRes.sp_target_android, language),
                "${selection.androidVersion} / SDK ${selection.sdkInt}",
                style,
            )
            CapabilityPopupFact(
                localizedStringResource(
                    SimpleperfViewerRes.sp_target_abi_value_format,
                    language,
                    "",
                ).trimLabelSeparator(),
                selection.abis.joinToString(),
                style,
            )
            CapabilityPopupFact(
                localizedStringResource(
                    SimpleperfViewerRes.sp_target_root_value_format,
                    language,
                    "",
                ).trimLabelSeparator(),
                selection.capabilities.root,
                style,
            )
            CapabilityPopupFact(
                localizedStringResource(SimpleperfViewerRes.sp_capture_scope, language),
                selection.capabilities.profilingScope,
                style,
            )
            CapabilityPopupFact(
                localizedStringResource(
                    SimpleperfViewerRes.sp_target_simpleperf_value_format,
                    language,
                    "",
                ).trimLabelSeparator(),
                selection.capabilities.simpleperf,
                style,
            )
            CapabilityPopupFact(
                localizedStringResource(SimpleperfViewerRes.sp_target_events, language),
                selection.capabilities.eventNames
                    .take(MAX_VISIBLE_EVENTS)
                    .joinToString()
                    .ifBlank { localizedStringResource(SimpleperfViewerRes.sp_target_unavailable, language) },
                style,
            )
            if (selection.capabilities.limitations.isNotEmpty()) {
                CapabilityPopupFact(
                    localizedStringResource(SimpleperfViewerRes.sp_target_limits, language),
                    selection.capabilities.limitations.joinToString(),
                    style,
                    warning = true,
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
private fun CapabilityPopupFact(
    label: String,
    value: String,
    style: ViewerColors,
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
    style: ViewerColors,
) {
    val language = currentSimpleperfLanguage()
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
        Text(
            localizedStringResource(
                when (status) {
                    CapabilityStatus.READY -> SimpleperfViewerRes.sp_target_ready
                    CapabilityStatus.LIMITED -> SimpleperfViewerRes.sp_target_limited
                    CapabilityStatus.BLOCKED -> SimpleperfViewerRes.sp_target_blocked
                },
                language,
            ),
            color = color,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Bold,
        )
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
    style: ViewerColors,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(ViewerDimensions.footerHeight)
            .background(style.toolbar)
            .border(
                ViewerDimensions.hairline,
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
    style: ViewerColors,
    modifier: Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val error = state.error
        val color = if (error == null) style.online else style.error
        val fileInfo = reportState.footerFileInfo() ?: captureState.footerFileInfo()
        StatusDot(color)
        Spacer(Modifier.width(7.dp))
        Text(
            error?.let {
                if (it.code == ADB_NOT_FOUND_ERROR_CODE) {
                    "$ADB_NOT_FOUND_ERROR_CODE: ${
                        localizedStringResource(
                            SimpleperfViewerRes.sp_adb_sdk_path_settings_hint,
                            currentSimpleperfLanguage(),
                        )
                    }"
                } else {
                    "${it.code}: ${it.message}"
                }
            } ?: captureState.statusText(currentSimpleperfLanguage()),
            modifier = if (fileInfo == null) Modifier.weight(1f) else Modifier.widthIn(max = 240.dp),
            color = color,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        fileInfo?.let { info ->
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(ViewerDimensions.hairline).height(14.dp).background(style.border))
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

private const val ADB_NOT_FOUND_ERROR_CODE = "ADB_NOT_FOUND"

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
    style: ViewerColors,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (captureState.isCaptureActive()) {
            if (captureState is CaptureState.Recording) {
                MacOSTextButton(
                    localizedStringResource(
                        SimpleperfViewerRes.sp_capture_stop_analyze,
                        currentSimpleperfLanguage(),
                    ),
                    actions.onStopCapture,
                    style,
                    primary = true,
                )
            }
            MacOSTextButton(
                localizedStringResource(
                    SimpleperfViewerRes.sp_capture_cancel,
                    currentSimpleperfLanguage(),
                ),
                actions.onCancelCapture,
                style,
            )
        }
    }
}

private fun String.trimLabelSeparator(): String = trimEnd().trimEnd(':', '：')

@Composable
@Suppress("ktlint:standard:function-naming")
private fun HorizontalHairline(color: Color) {
    Box(Modifier.fillMaxWidth().height(ViewerDimensions.hairline).background(color))
}

private const val MAX_VISIBLE_EVENTS = 8
private const val STATUS_FILL_ALPHA = 0.16f
private const val DEVICE_SELECTOR_WEIGHT = 0.77f
private const val APP_SELECTOR_WEIGHT = 1.1f
private const val PROCESS_SELECTOR_WEIGHT = 1f
private const val THREAD_SELECTOR_WEIGHT = 0.95f
