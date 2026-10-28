@file:Suppress(
    "FunctionName",
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "MaxLineLength",
    "TooManyFunctions",
    "ktlint:standard:function-naming",
)

package com.androidperformancestudio.startup.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.startup.model.CompilationMode
import com.androidperformancestudio.startup.model.StartupProfileSource
import com.androidperformancestudio.startup.model.StartupRun
import com.androidperformancestudio.startup.model.StartupType
import com.androidperformancestudio.startup.presentation.generated.resources.Res
import com.androidperformancestudio.startup.presentation.generated.resources.application
import com.androidperformancestudio.startup.presentation.generated.resources.baseline_profile_artifact
import com.androidperformancestudio.startup.presentation.generated.resources.baseline_profile_status
import com.androidperformancestudio.startup.presentation.generated.resources.cold_start_description
import com.androidperformancestudio.startup.presentation.generated.resources.compilation
import com.androidperformancestudio.startup.presentation.generated.resources.compilation_current
import com.androidperformancestudio.startup.presentation.generated.resources.compilation_reset
import com.androidperformancestudio.startup.presentation.generated.resources.compilation_speed
import com.androidperformancestudio.startup.presentation.generated.resources.compilation_speed_profile
import com.androidperformancestudio.startup.presentation.generated.resources.compilation_verify
import com.androidperformancestudio.startup.presentation.generated.resources.hot_start_description
import com.androidperformancestudio.startup.presentation.generated.resources.iterations
import com.androidperformancestudio.startup.presentation.generated.resources.perfetto_capture
import com.androidperformancestudio.startup.presentation.generated.resources.practical_threshold
import com.androidperformancestudio.startup.presentation.generated.resources.profile_artifact_hint
import com.androidperformancestudio.startup.presentation.generated.resources.profile_baseline_plugin
import com.androidperformancestudio.startup.presentation.generated.resources.profile_build_variant
import com.androidperformancestudio.startup.presentation.generated.resources.profile_macrobenchmark
import com.androidperformancestudio.startup.presentation.generated.resources.profile_no_evidence
import com.androidperformancestudio.startup.presentation.generated.resources.profile_source
import com.androidperformancestudio.startup.presentation.generated.resources.profile_unverified
import com.androidperformancestudio.startup.presentation.generated.resources.profile_unverified_status
import com.androidperformancestudio.startup.presentation.generated.resources.profile_verified_status
import com.androidperformancestudio.startup.presentation.generated.resources.run_timeout
import com.androidperformancestudio.startup.presentation.generated.resources.scenario_configuration
import com.androidperformancestudio.startup.presentation.generated.resources.start_analysis
import com.androidperformancestudio.startup.presentation.generated.resources.stop_analysis
import com.androidperformancestudio.startup.presentation.generated.resources.warm_start_description
import com.androidperformancestudio.startup.presentation.generated.resources.warmup_runs
import com.androidperformancestudio.ui.DeviceDropdownSelector
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.HeaderSpacer
import com.androidperformancestudio.ui.LocalTitleBarTargetHost
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerCompactTextField
import com.androidperformancestudio.ui.TitleBarTargetOrInline
import com.androidperformancestudio.ui.TitleBarTargetSlot
import com.androidperformancestudio.ui.TitleBarTargetWidth
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioPanel
import com.androidperformancestudio.ui.studio.StudioTokens

@Composable
internal fun StartupTypeTabs(
    state: StartupProfilerState,
    actions: StartupProfilerActions,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(StartupType.COLD, StartupType.WARM, StartupType.HOT).forEach { type ->
            val selected = state.config.requestedType == type
            val shape = RoundedCornerShape(StudioTokens.panelRadius)
            Row(
                Modifier
                    .weight(1f)
                    .height(52.dp)
                    .testTag("startup-type-${type.name}")
                    .clip(shape)
                    .background(if (selected) colors.accent.copy(alpha = 0.32f) else colors.panel)
                    .border(1.dp, if (selected) colors.accent else colors.border, shape)
                    .selectable(selected, enabled = !state.isRunning, role = Role.Tab, onClick = { actions.onSelectStartupType(type) })
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (type) {
                        StartupType.COLD -> "❄"
                        StartupType.WARM -> "♨"
                        else -> "↻"
                    },
                    Modifier.padding(end = 12.dp),
                    color =
                        when (type) {
                            StartupType.COLD -> colors.info
                            StartupType.WARM -> colors.warning
                            else -> colors.success
                        },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Column {
                    Text(type.localizedLabel(language), fontWeight = FontWeight.SemiBold, fontSize = ViewerTypography.bodyCompact.fontSize)
                    Text(
                        localizedStringResource(
                            when (type) {
                                StartupType.COLD -> Res.string.cold_start_description
                                StartupType.WARM -> Res.string.warm_start_description
                                else -> Res.string.hot_start_description
                            },
                            language,
                        ),
                        color = colors.secondaryText,
                        fontSize = ViewerTypography.secondary.fontSize,
                    )
                }
            }
        }
    }
}

/**
 * Page-level target selection for the startup experiment.
 *
 * The device scopes the application list. On macOS the shell shows both selectors in the title bar;
 * standalone and other-platform windows keep them inline in the same order.
 */
@Composable
public fun StartupTargetSelectors(
    state: StartupProfilerState,
    actions: StartupProfilerActions,
    language: UiLanguage,
) {
    val targetHost = LocalTitleBarTargetHost.current
    val inTitleBar = targetHost != null
    val selectedTarget = state.targets.firstOrNull { it.componentName == state.selectedComponentName }
    val pendingSharedDevice = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(targetHost, targetHost?.selectedDeviceSerial, state.selectedDeviceSerial, state.devices, state.isRunning) {
        if (targetHost == null || state.isRunning) return@LaunchedEffect
        val sharedSerial = targetHost.selectedDeviceSerial
        if (sharedSerial == null) {
            state.devices.firstOrNull { it.serial == state.selectedDeviceSerial }?.let {
                targetHost.chooseDevice(it.serial, it.name)
            }
        } else if (sharedSerial == state.selectedDeviceSerial) {
            pendingSharedDevice.value = null
        } else if (pendingSharedDevice.value != sharedSerial && state.devices.any { it.serial == sharedSerial && it.online }) {
            pendingSharedDevice.value = sharedSerial
            actions.onSelectDevice(sharedSerial)
        }
    }
    LaunchedEffect(
        targetHost,
        targetHost?.selectedPackageName,
        targetHost?.selectedDeviceSerial,
        state.selectedDeviceSerial,
        state.selectedComponentName,
        state.targets,
        state.isRunning,
    ) {
        if (targetHost == null || state.isRunning || selectedTarget != null) return@LaunchedEffect
        if (!targetHost.deviceReady(state.selectedDeviceSerial)) return@LaunchedEffect
        // A running process can suggest the initial app, but the launch activity is page-owned.
        val sharedPackage = targetHost.selectedPackageName ?: return@LaunchedEffect
        state.targets.firstOrNull { it.packageName == sharedPackage }?.let {
            actions.onSelectTarget(it.componentName)
        }
    }
    TitleBarTargetOrInline(TitleBarTargetSlot.DEVICE) {
        DeviceDropdownSelector(
            language = language,
            items = state.devices,
            selectedItem = state.devices.firstOrNull { it.serial == state.selectedDeviceSerial },
            onItemSelected = {
                targetHost?.chooseDevice(it.serial, it.name)
                pendingSharedDevice.value = it.serial
                actions.onSelectDevice(it.serial)
            },
            itemLabel = { it.name },
            itemEnabled = { it.online },
            modifier = Modifier.width(if (inTitleBar) TitleBarTargetWidth else 160.dp).testTag("startup-device"),
            fillWidth = true,
            enabled = !state.isRunning && !state.isRefreshing,
            displayTextOverride = targetHost?.selectedDeviceLabel,
        )
    }
    if (!inTitleBar) HeaderSpacer()
    // The title bar owns the application-wide process target; the startup component target stays here.
    DropdownSelector(
        items = state.targets,
        searchable = true,
        searchLanguage = language,
        selectedItem = selectedTarget,
        onItemSelected = {
            actions.onSelectTarget(it.componentName)
        },
        itemLabel = { "${it.packageName} / ${it.componentName.substringAfter('/')}" },
        selectedItemLabel = { "${it.packageName} / ${it.componentName.substringAfter('/')}" },
        itemSecondary = { it.componentName },
        placeholder = localizedStringResource(Res.string.application, language),
        selectorDescription = localizedStringResource(Res.string.application, language),
        modifier = Modifier.width(220.dp).testTag("startup-target"),
        fillWidth = true,
        enabled =
            !state.isRunning &&
                state.selectedDeviceSerial != null &&
                (targetHost?.deviceReady(state.selectedDeviceSerial) != false),
    )
}

@Composable
internal fun StartupScenarioControls(
    state: StartupProfilerState,
    actions: StartupProfilerActions,
    language: UiLanguage,
) {
    val config = state.config
    val targetHost = LocalTitleBarTargetHost.current
    val targetReady = targetHost?.deviceReady(state.selectedDeviceSerial) != false
    StudioPanel(
        modifier = Modifier.fillMaxWidth().testTag("startup-scenario"),
        contentPadding = StudioTokens.compactContentPadding,
    ) {
        Text(
            localizedStringResource(Res.string.scenario_configuration, language),
            fontWeight = FontWeight.SemiBold,
        )
        // Every experiment parameter shares one horizontally scrollable row so the advanced
        // configuration is visible by default instead of hiding behind a disclosure button.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                ControlField(localizedStringResource(Res.string.iterations, language)) {
                    DropdownSelector(
                        items = (listOf(1, 3, 5, 10, 20, 30, 50, 100) + config.measuredRuns).distinct().sorted(),
                        selectedItem = config.measuredRuns,
                        onItemSelected = { actions.onUpdateCounts(config.warmupRuns, it) },
                        itemLabel = Int::toString,
                        placeholder = "",
                        enabled = !state.isRunning,
                        modifier = Modifier.testTag("startup-iterations"),
                    )
                }
                ControlField(localizedStringResource(Res.string.compilation, language)) {
                    DropdownSelector(
                        items = CompilationMode.entries,
                        selectedItem = config.compilationMode,
                        onItemSelected = actions.onSelectCompilationMode,
                        itemLabel = { it.localizedLabel(language) },
                        placeholder = "",
                        enabled = !state.isRunning,
                        modifier = Modifier.testTag("startup-compilation"),
                    )
                }
                if (config.compilationMode == CompilationMode.SPEED_PROFILE) {
                    ControlField(localizedStringResource(Res.string.profile_source, language)) {
                        DropdownSelector(
                            items = StartupProfileSource.entries,
                            selectedItem = config.profileSource,
                            onItemSelected = actions.onSelectProfileSource,
                            itemLabel = { it.localizedLabel(language) },
                            placeholder = "",
                            enabled = !state.isRunning,
                            modifier = Modifier.testTag("startup-profile-source"),
                        )
                    }
                }
                ControlField(localizedStringResource(Res.string.warmup_runs, language)) {
                    DropdownSelector(
                        items = ((0..10).toList() + listOf(20, 50, 100) + config.warmupRuns).distinct().sorted(),
                        selectedItem = config.warmupRuns,
                        onItemSelected = { actions.onUpdateCounts(it, config.measuredRuns) },
                        itemLabel = Int::toString,
                        placeholder = "",
                        enabled = !state.isRunning && config.compilationMode == CompilationMode.SPEED_PROFILE,
                    )
                }
                ControlField(localizedStringResource(Res.string.run_timeout, language)) {
                    DropdownSelector(
                        items = (listOf(5, 10, 20, 30, 45, 60, 120, 300) + config.timeoutSeconds).distinct().sorted(),
                        selectedItem = config.timeoutSeconds,
                        onItemSelected = actions.onUpdateTimeout,
                        itemLabel = Int::toString,
                        placeholder = "",
                        enabled = !state.isRunning,
                        modifier = Modifier.testTag("startup-timeout"),
                    )
                }
                ControlField(localizedStringResource(Res.string.practical_threshold, language)) {
                    DropdownSelector(
                        items = (listOf(0.0, 1.0, 3.0, 5.0, 10.0, 100.0) + config.practicalChangeThresholdPercent).distinct().sorted(),
                        selectedItem = config.practicalChangeThresholdPercent,
                        onItemSelected = actions.onUpdatePracticalThreshold,
                        itemLabel = { it.toString() },
                        placeholder = "",
                        enabled = !state.isRunning,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        config.capturePerfettoTrace,
                        actions.onSetPerfettoTraceEnabled,
                        enabled = !state.isRunning,
                        modifier = Modifier.testTag("startup-perfetto"),
                    )
                    Text(localizedStringResource(Res.string.perfetto_capture, language), style = MaterialTheme.typography.bodySmall)
                }
            }
            ProfilerCompactButton(
                text =
                    localizedStringResource(
                        if (state.isRunning) Res.string.stop_analysis else Res.string.start_analysis,
                        language,
                    ),
                enabled =
                    state.isRunning ||
                        (!state.isRefreshing && state.selectedComponentName != null && state.selectedDeviceSerial != null && targetReady),
                modifier = Modifier.padding(start = 14.dp).testTag("startup-start-stop"),
                onClick = actions.onStartStop,
            )
        }
        if (config.compilationMode == CompilationMode.SPEED_PROFILE) {
            ProfilerCompactTextField(
                label = localizedStringResource(Res.string.baseline_profile_artifact, language),
                value = config.baselineProfileArtifact.orEmpty(),
                onValueChange = actions.onUpdateBaselineProfileArtifact,
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth().testTag("startup-profile-artifact"),
            )
            Text(
                localizedStringResource(Res.string.profile_artifact_hint, language),
                color = LocalViewerColors.current.secondaryText,
                fontSize = ViewerTypography.secondary.fontSize,
            )
        }
    }
}

@Composable
private fun ControlField(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = LocalViewerColors.current.secondaryText, fontSize = ViewerTypography.secondary.fontSize)
        content()
    }
}

@Composable
internal fun ExpandableEvidence(
    label: String,
    identity: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember(identity, label) { mutableStateOf(false) }
    ProfilerCompactButton("${if (expanded) "▾" else "▸"} $label", onClick = { expanded = !expanded })
    if (expanded) Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
internal fun StartupProfileSummary(
    run: StartupRun?,
    language: UiLanguage,
) {
    val evidence = run?.compilationEvidence
    val verified = evidence.hasVerifiedBaselineProfile()
    val colors = LocalViewerColors.current
    val tone = if (verified) colors.success else colors.warning
    StudioPanel(
        modifier = Modifier.fillMaxWidth().testTag("startup-profile-status"),
        contentPadding = StudioTokens.compactContentPadding,
    ) {
        Text(localizedStringResource(Res.string.baseline_profile_status, language), fontWeight = FontWeight.SemiBold)
        Text(
            localizedStringResource(
                when {
                    evidence == null -> Res.string.profile_no_evidence
                    verified -> Res.string.profile_verified_status
                    else -> Res.string.profile_unverified_status
                },
                language,
            ),
            Modifier.fillMaxWidth().background(tone.copy(alpha = 0.10f), RoundedCornerShape(4.dp)).padding(10.dp),
            color = tone,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall,
        )
        if (evidence != null) {
            evidence
                .detailRows(language)
                .mapIndexed { index, row ->
                    if (index == 6) row.copy(value = evidence.profileSource.localizedLabel(language)) else row
                }.filterIndexed { index, _ -> index in listOf(2, 5, 6, 8) }
                .forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            row.label,
                            Modifier.weight(0.48f),
                            color = colors.secondaryText,
                            fontSize = ViewerTypography.secondary.fontSize,
                        )
                        Text(row.value, Modifier.weight(0.52f), fontSize = ViewerTypography.secondary.fontSize)
                    }
                }
        }
    }
}

internal fun CompilationMode.localizedLabel(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            CompilationMode.CURRENT -> Res.string.compilation_current
            CompilationMode.RESET -> Res.string.compilation_reset
            CompilationMode.VERIFY -> Res.string.compilation_verify
            CompilationMode.SPEED_PROFILE -> Res.string.compilation_speed_profile
            CompilationMode.SPEED -> Res.string.compilation_speed
        },
        language,
    )

internal fun StartupProfileSource.localizedLabel(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            StartupProfileSource.UNVERIFIED -> Res.string.profile_unverified
            StartupProfileSource.BASELINE_PROFILE_PLUGIN -> Res.string.profile_baseline_plugin
            StartupProfileSource.MACROBENCHMARK -> Res.string.profile_macrobenchmark
            StartupProfileSource.BUILD_VARIANT -> Res.string.profile_build_variant
        },
        language,
    )

internal fun com.androidperformancestudio.startup.model.StartupCompilationEvidence?.hasVerifiedBaselineProfile(): Boolean =
    this != null &&
        requestedMode == CompilationMode.SPEED_PROFILE &&
        verified &&
        profileSourceDeclared &&
        profileSource == StartupProfileSource.BASELINE_PROFILE_PLUGIN &&
        compilerFilterAfter == "speed-profile" &&
        !baselineProfileArtifact.isNullOrBlank() &&
        failureReason.isNullOrBlank()
