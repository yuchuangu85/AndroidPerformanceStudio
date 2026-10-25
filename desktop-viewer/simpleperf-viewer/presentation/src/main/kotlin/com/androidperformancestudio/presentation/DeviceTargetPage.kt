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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.application.CapabilityStatus
import com.androidperformancestudio.application.DeviceOption
import com.androidperformancestudio.application.DeviceSelection
import com.androidperformancestudio.application.DeviceTargetState
import com.androidperformancestudio.application.PackageOption
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.capture.CaptureState
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.ui.DeviceDropdownSelector
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.HeaderSpacer
import com.androidperformancestudio.ui.HeaderToolbar
import com.androidperformancestudio.ui.LocalTitleBarTargetHost
import com.androidperformancestudio.ui.LocalWindowMenuBarActive
import com.androidperformancestudio.ui.TitleBarRefreshOrInline
import com.androidperformancestudio.ui.TitleBarTargetOrInline
import com.androidperformancestudio.ui.TitleBarTargetSlot
import com.androidperformancestudio.ui.TitleBarTargetWidth
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.ViewerDimensions
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.button.MacOSTextButton
import com.androidperformancestudio.ui.localizedStringResource

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod", "ComplexCondition", "MaxLineLength", "ktlint:standard:function-naming")
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
    val style = currentSimpleperfViewerColors(darkTheme)
    val captureActive = captureState.isCaptureActive()
    val selectedDevice = state.devices.firstOrNull { it.serial == state.selectedSerial }
    val titleBarHost = LocalTitleBarTargetHost.current.takeIf { LocalWindowMenuBarActive.current }
    LaunchedEffect(titleBarHost, titleBarHost?.selectedDeviceSerial, state.devices, state.selectedSerial) {
        if (titleBarHost != null) {
            val desired = titleBarHost.selectedDeviceSerial
            if (desired == null && selectedDevice != null) {
                titleBarHost.chooseDevice(selectedDevice.serial, selectedDevice.label)
            } else if (desired != null && desired != state.selectedSerial && state.devices.any { it.serial == desired && it.isOnline }) {
                actions.onSelectDevice(desired)
            }
        }
    }
    LaunchedEffect(
        titleBarHost,
        titleBarHost?.selectedPackageName,
        state.selection?.packages,
        state.selectedPackageName,
        state.selectedSerial,
    ) {
        if (titleBarHost != null && titleBarHost.deviceReady(state.selectedSerial)) {
            val desired = titleBarHost.selectedPackageName
            val selectedPackageName = state.selectedPackageName
            if (desired == null && selectedPackageName != null) {
                titleBarHost.choosePackage(selectedPackageName)
            } else if (
                desired != null &&
                desired != state.selectedPackageName &&
                state.selection
                    ?.packages
                    .orEmpty()
                    .any { it.packageName == desired }
            ) {
                actions.onSelectPackage(desired)
            }
        }
    }
    // The title bar owns one process target app-wide; follow it whenever the page can resolve the pid.
    LaunchedEffect(
        titleBarHost,
        titleBarHost?.selectedProcessPid,
        state.selection?.processes,
        state.selectedProcessId,
        state.selectedSerial,
    ) {
        if (titleBarHost != null && titleBarHost.deviceReady(state.selectedSerial)) {
            val desired = titleBarHost.selectedProcessPid
            if (
                desired != null &&
                desired != state.selectedProcessId &&
                state.selection?.processes.orEmpty().any { it.pid == desired }
            ) {
                actions.onSelectProcess(desired)
            }
        }
    }
    // A selected process carries its own application context, so the page-level app filter
    // cannot block capture once the fixed title bar has picked a process.
    val targetReady =
        titleBarHost?.deviceReady(state.selectedSerial) != false &&
            (state.selectedProcessId != null || titleBarHost?.packageReady(state.selectedPackageName) != false)
    Column(Modifier.fillMaxSize().background(style.workspace)) {
        HeaderToolbar(
            language = currentSimpleperfLanguage(),
            onNavigateHome = onNavigateHome,
            onNavigateSettings = { onSettingsSectionChange(CaptureSettingsSection.SAMPLING_TEMPLATE) },
        ) {
            val language = currentSimpleperfLanguage()
            val targetsInTitleBar = LocalWindowMenuBarActive.current && LocalTitleBarTargetHost.current != null
            val deviceModifier =
                if (targetsInTitleBar) Modifier.width(TitleBarTargetWidth) else Modifier.weight(DEVICE_SELECTOR_WEIGHT)
            // The page-level application selector matches the fixed title-bar process control width.
            val appModifier =
                if (targetsInTitleBar) Modifier.width(TitleBarTargetWidth) else Modifier.weight(APP_SELECTOR_WEIGHT)
            TitleBarTargetOrInline(TitleBarTargetSlot.DEVICE) {
                SimpleperfLocalization(language) {
                    DeviceSelector(
                        selectedDevice,
                        state.devices,
                        { serial ->
                            state.devices.firstOrNull { it.serial == serial }?.let { titleBarHost?.chooseDevice(serial, it.label) }
                            actions.onSelectDevice(serial)
                        },
                        style,
                        deviceModifier,
                        !captureActive && !state.isLoading,
                        titleBarHost?.selectedDeviceLabel,
                    )
                }
            }
            if (!targetsInTitleBar) HeaderSpacer()
            AppSelector(
                state.selectedPackageName,
                state.selection?.packages.orEmpty(),
                { packageName ->
                    titleBarHost?.choosePackage(packageName)
                    actions.onSelectPackage(packageName)
                },
                style,
                appModifier,
                !captureActive && !state.isLoading,
                null,
            )
            HeaderSpacer()
            // The fixed title bar owns the device and application-process targets; this page header
            // only keeps the page-level application selector and the capture actions.
            if (targetsInTitleBar) Spacer(Modifier.weight(1f))
            ToolbarCaptureActions(state, captureState, actions, style, !captureActive && !state.isLoading, targetReady)
            HeaderSpacer()
            Box(Modifier.testTag("cpu-toolbar-import")) {
                MacOSTextButton(
                    localizedStringResource(SimpleperfViewerRes.sp_workspace_import, currentSimpleperfLanguage()),
                    reportActions.onOpenSession,
                    style,
                    enabled = !captureActive,
                )
            }
            HeaderSpacer()
            CapabilityPopupButton(state.selection, style)
        }
        ReportWorkspace(
            state = reportState,
            actions = reportActions,
            style = style,
            modifier = Modifier.weight(1f),
            flameTooltipMode = flameTooltipMode,
            onRunAiAnalysis = onRunAiAnalysis,
            captureState = captureState,
        )
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
    captureState: CaptureState,
    actions: DeviceTargetActions,
    style: ViewerColors,
    enabled: Boolean,
    targetReady: Boolean,
) {
    val language = currentSimpleperfLanguage()
    val captureActive = captureState.isCaptureActive()
    val enableRootAndRetry = state.canEnableRootAndRetry(captureState)
    val refreshLabel = localizedStringResource(SimpleperfViewerRes.sp_target_refresh, language)
    val refreshingLabel = localizedStringResource(SimpleperfViewerRes.sp_target_refreshing, language)
    val textMeasurer = rememberTextMeasurer()
    val refreshTextStyle =
        LocalTextStyle.current.copy(
            fontSize = ViewerTypography.secondary.fontSize,
            lineHeight = ViewerTypography.label.lineHeight,
            fontWeight = FontWeight.Normal,
        )
    val refreshLabelWidth =
        maxOf(
            textMeasurer.measure(refreshLabel, refreshTextStyle, maxLines = 1).size.width,
            textMeasurer.measure(refreshingLabel, refreshTextStyle, maxLines = 1).size.width,
        )
    // Reserve both labels plus the shared text button's 10.dp horizontal padding per side.
    val refreshButtonWidth = with(LocalDensity.current) { refreshLabelWidth.toDp() } + 20.dp
    TitleBarRefreshOrInline(enabled = enabled && !state.isLoading, onRefresh = actions.onRefresh) {
        MacOSTextButton(
            if (state.isLoading) refreshingLabel else refreshLabel,
            actions.onRefresh,
            style,
            enabled = enabled && !state.isLoading,
            modifier = Modifier.width(refreshButtonWidth),
        )
    }
    if (LocalTitleBarTargetHost.current == null || !LocalWindowMenuBarActive.current) HeaderSpacer()
    MacOSTextButton(
        label =
            localizedStringResource(
                when {
                    captureActive -> SimpleperfViewerRes.sp_capture_stop_analyze
                    enableRootAndRetry -> SimpleperfViewerRes.sp_capture_enable_root_retry
                    else -> SimpleperfViewerRes.sp_capture_get_data
                },
                language,
            ),
        onClick =
            when {
                captureActive -> actions.onStopCapture
                enableRootAndRetry -> actions.onEnableRootAndRetry
                else -> actions.onStartCapture
            },
        style = style,
        enabled =
            if (captureActive) {
                captureState is CaptureState.Recording
            } else {
                enabled && targetReady && state.canEnterCapture && state.captureSetup != null
            },
        primary = true,
    )
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
    displayTextOverride: String?,
) {
    val language = currentSimpleperfLanguage()
    DeviceDropdownSelector(
        language = language,
        items = devices,
        selectedItem = selected,
        onItemSelected = { onSelect(it.serial) },
        itemLabel = DeviceOption::label,
        modifier = modifier,
        enabled = enabled,
        displayTextOverride = displayTextOverride,
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
    displayTextOverride: String?,
) {
    val language = currentSimpleperfLanguage()
    DropdownSelector(
        items = packages,
        searchable = true,
        searchLanguage = language,
        selectedItem = packages.firstOrNull { it.packageName == selectedPackage },
        onItemSelected = { onSelect(it.packageName) },
        itemLabel = PackageOption::packageName,
        placeholder = localizedStringResource(SimpleperfViewerRes.sp_target_app, language),
        modifier = modifier,
        enabled = enabled,
        displayTextOverride = displayTextOverride,
        selectorDescription = localizedStringResource(SimpleperfViewerRes.sp_target_app_selector, language),
        colors = style,
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
            fontSize = ViewerTypography.body.fontSize,
            lineHeight = ViewerTypography.bodyCompact.lineHeight,
            fontWeight = FontWeight.SemiBold,
        )
        if (selection == null) {
            Text(
                localizedStringResource(SimpleperfViewerRes.sp_target_capability_selection_hint, language),
                color = style.secondaryText,
                fontSize = ViewerTypography.secondary.fontSize,
                lineHeight = ViewerTypography.label.lineHeight,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        selection.model,
                        color = style.text,
                        fontSize = ViewerTypography.lead.fontSize,
                        lineHeight = ViewerTypography.body.lineHeight,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        selection.serial,
                        color = style.secondaryText,
                        fontSize = ViewerTypography.label.fontSize,
                        lineHeight = ViewerTypography.label.lineHeight,
                    )
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
            fontSize = ViewerTypography.label.fontSize,
            lineHeight = ViewerTypography.secondary.lineHeight,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            color = if (warning) style.warning else style.text,
            fontSize = ViewerTypography.label.fontSize,
            lineHeight = ViewerTypography.secondary.lineHeight,
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
            fontSize = ViewerTypography.label.fontSize,
            lineHeight = ViewerTypography.label.lineHeight,
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
    SelectionContainer {
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
        val hasCaptureError = captureState is CaptureState.Failed
        val color = if (error != null || hasCaptureError) style.error else style.online
        val fileInfo = captureFooterFileInfo(error != null || hasCaptureError, reportState, captureState)
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
            fontSize = ViewerTypography.label.fontSize,
            lineHeight = ViewerTypography.secondary.lineHeight,
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
                fontSize = ViewerTypography.label.fontSize,
                lineHeight = ViewerTypography.secondary.lineHeight,
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
                        fontSize = ViewerTypography.dense.fontSize,
                        lineHeight = ViewerTypography.label.lineHeight,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun captureFooterFileInfo(
    hasError: Boolean,
    reportState: ReportState,
    captureState: CaptureState,
): FooterFileInfo? =
    if (hasError) {
        null
    } else {
        reportState.footerFileInfo() ?: captureState.footerFileInfo()
    }

private data class FooterFileInfo(
    val name: String,
    val path: String,
)

private const val ADB_NOT_FOUND_ERROR_CODE = "ADB_NOT_FOUND"
private const val ROOT_AVAILABLE_AFTER_ADB_ROOT = "AVAILABLE_AFTER_ADB_ROOT"
private val ROOT_RECOVERABLE_CAPTURE_ERRORS = setOf("TARGET_PROCESS_UNAVAILABLE", "TARGET_PERMISSION_DENIED")

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

private fun DeviceTargetState.canEnableRootAndRetry(captureState: CaptureState): Boolean {
    val failure = captureState as? CaptureState.Failed ?: return false
    return selection?.capabilities?.root == ROOT_AVAILABLE_AFTER_ADB_ROOT &&
        failure.error.code in ROOT_RECOVERABLE_CAPTURE_ERRORS
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

