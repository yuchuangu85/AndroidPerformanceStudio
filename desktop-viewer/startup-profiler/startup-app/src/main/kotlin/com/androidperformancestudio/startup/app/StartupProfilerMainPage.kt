@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "LongMethod",
    "MagicNumber",
    "MaxLineLength",
    "ktlint:standard:function-naming",
)

package com.androidperformancestudio.startup.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.startup.presentation.StartupProfilerActions
import com.androidperformancestudio.startup.presentation.StartupProfilerScreen
import com.androidperformancestudio.startup.presentation.StartupTargetSelectors
import com.androidperformancestudio.startup.startup_app.generated.resources.Res
import com.androidperformancestudio.startup.startup_app.generated.resources.cancel
import com.androidperformancestudio.startup.startup_app.generated.resources.compilation_change_message
import com.androidperformancestudio.startup.startup_app.generated.resources.compilation_change_title
import com.androidperformancestudio.startup.startup_app.generated.resources.continue_action
import com.androidperformancestudio.startup.startup_app.generated.resources.export_csv
import com.androidperformancestudio.startup.startup_app.generated.resources.export_json
import com.androidperformancestudio.startup.startup_app.generated.resources.export_startup_profiler_report
import com.androidperformancestudio.startup.startup_app.generated.resources.import_report
import com.androidperformancestudio.startup.startup_app.generated.resources.import_startup_profiler_report
import com.androidperformancestudio.startup.startup_app.generated.resources.refresh
import com.androidperformancestudio.startup.startup_app.generated.resources.status
import com.androidperformancestudio.ui.HeaderSpacer
import com.androidperformancestudio.ui.HeaderToolbar
import com.androidperformancestudio.ui.LocalTitleBarTargetHost
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerToolbarStatus
import com.androidperformancestudio.ui.TitleBarRefreshOrInline
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerDimensions
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.chooseOpenFile
import com.androidperformancestudio.ui.chooseSaveFile
import com.androidperformancestudio.ui.localizedStringResource
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path

@Composable
public fun FrameWindowScope.StartupProfilerMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
    onBack: () -> Unit = {},
    onOpenTrace: ((Path) -> Unit)? = null,
) {
    val controller = remember(language) { StartupProfilerController(language = language) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var experimentJob by remember { mutableStateOf<Job?>(null) }
    val targetHost = LocalTitleBarTargetHost.current
    val selectedPackage = state.targets.firstOrNull { it.componentName == state.selectedComponentName }?.packageName
    val selectionReady =
        targetHost?.deviceReady(state.selectedDeviceSerial) != false &&
            targetHost?.packageReady(selectedPackage) != false

    LaunchedEffect(controller) { controller.refreshDevices() }

    val actions =
        StartupProfilerActions(
            onSelectRun = controller::selectRun,
            onOpenTrace = onOpenTrace?.let { open -> { file -> openStartupTraceFile(file, open) } },
            onSelectDevice = { serial -> scope.launch { controller.selectDevice(serial) } },
            onSelectTarget = controller::selectTarget,
            onSelectStartupType = controller::selectStartupType,
            onSelectCompilationMode = controller::selectCompilationMode,
            onSelectProfileSource = controller::selectProfileSource,
            onUpdateBaselineProfileArtifact = controller::updateBaselineProfileArtifact,
            onUpdateCounts = controller::updateCounts,
            onUpdateTimeout = controller::updateTimeout,
            onSetPerfettoTraceEnabled = controller::setPerfettoTraceEnabled,
            onUpdatePracticalThreshold = controller::updatePracticalThreshold,
            onRefresh = { scope.launch { controller.refreshDevices() } },
            onStartStop = {
                if (state.isRunning) {
                    experimentJob?.cancel()
                } else if (selectionReady && experimentJob?.isActive != true && state.selectedComponentName != null) {
                    experimentJob = scope.launch { controller.runExperiment() }
                }
            },
            onImport = {
                chooseOpenFile(
                    window,
                    localizedStringResource(Res.string.import_startup_profiler_report, language),
                    "JSON (*.json)",
                    "json",
                    acceptAllFiles = false,
                )?.let { file ->
                    scope.launch { controller.importJson(file.toPath()) }
                }
            },
            onExportCsv = {
                chooseSaveFile(
                    window,
                    localizedStringResource(Res.string.export_startup_profiler_report, language),
                    "startup-analysis.csv",
                )?.let { file ->
                    scope.launch { controller.exportCsv(file.toPath()) }
                }
            },
            onExportJson = {
                chooseSaveFile(
                    window,
                    localizedStringResource(Res.string.export_startup_profiler_report, language),
                    "startup-analysis.json",
                )?.let { file ->
                    scope.launch { controller.exportJson(file.toPath()) }
                }
            },
        )

    StartupProfilerFileMenuBar(
        model =
            startupProfilerFileMenuModel(
                language = language,
                importEnabled = !state.isRunning,
                exportEnabled = state.analysis != null && !state.isRunning,
            ),
        onImport = actions.onImport,
        onExportCsv = actions.onExportCsv,
        onExportJson = actions.onExportJson,
    )

    if (state.compilationConfirmationRequired) {
        AlertDialog(
            onDismissRequest = controller::dismissCompilationConfirmation,
            title = { Text(localizedStringResource(Res.string.compilation_change_title, language)) },
            text = { Text(localizedStringResource(Res.string.compilation_change_message, language)) },
            dismissButton = {
                TextButton(onClick = controller::dismissCompilationConfirmation) {
                    Text(localizedStringResource(Res.string.cancel, language))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.dismissCompilationConfirmation()
                        if (selectionReady && experimentJob?.isActive != true) {
                            experimentJob = scope.launch { controller.runExperiment(compilationChangeConfirmed = true) }
                        }
                    },
                ) { Text(localizedStringResource(Res.string.continue_action, language)) }
            },
        )
    }
    Column(Modifier.fillMaxSize()) {
        HeaderToolbar(
            language = language,
            onNavigateHome = {
                experimentJob?.cancel()
                onBack()
            },
            onNavigateSettings = null,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StartupTargetSelectors(
                    state = state,
                    actions = actions,
                    language = language,
                )
                HeaderSpacer()
                TitleBarRefreshOrInline(
                    enabled = !state.isRunning && !state.isRefreshing,
                    onRefresh = actions.onRefresh,
                ) {
                    ProfilerCompactButton(
                        text = localizedStringResource(Res.string.refresh, language),
                        enabled = !state.isRunning && !state.isRefreshing,
                        onClick = actions.onRefresh,
                    )
                }
                if (targetHost == null) HeaderSpacer()
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.import_report, language),
                    enabled = !state.isRunning,
                    onClick = actions.onImport,
                )
                HeaderSpacer()
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.export_csv, language),
                    enabled = state.analysis != null && !state.isRunning,
                    onClick = actions.onExportCsv,
                )
                HeaderSpacer()
                ProfilerCompactButton(
                    text = localizedStringResource(Res.string.export_json, language),
                    enabled = state.analysis != null && !state.isRunning,
                    onClick = actions.onExportJson,
                )
                HeaderSpacer()
                SelectionContainer {
                    ProfilerToolbarStatus(
                        message = state.operationMessage,
                        error = state.errorMessage,
                    )
                }
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline,
        )
        if (state.isRunning && state.totalRuns > 0) {
            LinearProgressIndicator(
                progress = { state.completedRuns.toFloat() / state.totalRuns.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        StartupProfilerScreen(
            state = state,
            actions = actions,
            language = language,
            modifier = Modifier.weight(1f),
        )
        StartupProfilerStatusBar(
            language = language,
            operationMessage = state.operationMessage,
            errorMessage = state.errorMessage,
        )
    }
}

internal fun openStartupTraceFile(
    file: String,
    onOpenTrace: (Path) -> Unit,
): Boolean {
    val path = runCatching { Path.of(file) }.getOrNull() ?: return false
    return Files.isRegularFile(path) && runCatching { onOpenTrace(path) }.isSuccess
}

@Composable
private fun StartupProfilerStatusBar(
    language: UiLanguage,
    operationMessage: String?,
    errorMessage: String?,
) {
    val colors = LocalViewerColors.current
    val status = errorMessage ?: operationMessage
    SelectionContainer {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(ViewerDimensions.footerHeight)
                    .background(colors.toolbar)
                    .border(
                        ViewerDimensions.hairline,
                        colors.border,
                        RoundedCornerShape(0.dp),
                    ).horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            status?.let {
                Text(
                    text = "${localizedStringResource(Res.string.status, language)}:",
                    color = colors.mutedText,
                    fontSize = ViewerTypography.label.fontSize,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = it,
                    color = if (errorMessage != null) MaterialTheme.colorScheme.error else colors.secondaryText,
                    fontSize = ViewerTypography.secondary.fontSize,
                    lineHeight = ViewerTypography.secondary.lineHeight,
                    maxLines = 1,
                )
            }
        }
    }
}
