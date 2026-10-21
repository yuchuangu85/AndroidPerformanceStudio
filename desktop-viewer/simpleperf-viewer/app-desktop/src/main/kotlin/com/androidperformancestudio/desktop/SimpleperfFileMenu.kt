package com.androidperformancestudio.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.app_desktop.generated.resources.Res
import com.androidperformancestudio.app_desktop.generated.resources.sp_export_external_open
import com.androidperformancestudio.app_desktop.generated.resources.sp_export_firefox_profiler_json
import com.androidperformancestudio.app_desktop.generated.resources.sp_export_folded_stacks
import com.androidperformancestudio.app_desktop.generated.resources.sp_export_json_csv
import com.androidperformancestudio.app_desktop.generated.resources.sp_export_pprof
import com.androidperformancestudio.app_desktop.generated.resources.sp_export_raw_protobuf
import com.androidperformancestudio.app_desktop.generated.resources.sp_export_report_html_py
import com.androidperformancestudio.app_desktop.generated.resources.sp_export_screenshot
import com.androidperformancestudio.app_desktop.generated.resources.sp_export_session_package
import com.androidperformancestudio.app_desktop.generated.resources.sp_export_simpleperf_report
import com.androidperformancestudio.app_desktop.generated.resources.sp_menu_configuration
import com.androidperformancestudio.app_desktop.generated.resources.sp_settings_advanced_parameters
import com.androidperformancestudio.app_desktop.generated.resources.sp_settings_capture_configuration
import com.androidperformancestudio.app_desktop.generated.resources.sp_settings_capture_templates
import com.androidperformancestudio.presentation.CaptureSettingsSection
import com.androidperformancestudio.ui.ActivePageMenu
import com.androidperformancestudio.ui.MenuCommand
import com.androidperformancestudio.ui.MenuShortcuts
import com.androidperformancestudio.ui.PageMenu
import com.androidperformancestudio.ui.RecentMenu
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Path

internal data class SimpleperfRecentMenuItem(
    val label: String,
    val path: Path,
)

internal data class SimpleperfExportMenuModel(
    val sessionPackageLabel: String,
    val reportLabel: String,
    val geckoProfileLabel: String,
    val rawProtobufLabel: String,
    val screenshotLabel: String,
    val simpleperfReportLabel: String,
    val htmlReportLabel: String,
    val externalOpenLabel: String,
    val foldedStacksLabel: String,
    val pprofLabel: String,
)

internal data class SimpleperfExportMenuActions(
    val onSessionPackage: () -> Unit,
    val onReport: () -> Unit,
    val onGeckoProfile: () -> Unit,
    val onRawProtobuf: () -> Unit,
    val onScreenshot: () -> Unit,
    val onSimpleperfReport: () -> Unit,
    val onHtmlReport: () -> Unit,
    val onExternalOpen: () -> Unit,
    val onFoldedStacks: () -> Unit,
    val onPprof: () -> Unit,
)

internal data class SimpleperfConfigurationMenuModel(
    val title: String,
    val samplingTemplateLabel: String,
    val captureConfigurationLabel: String,
    val advancedParametersLabel: String,
    val enabled: Boolean,
)

internal data class SimpleperfFileMenuModel(
    val exportMenu: SimpleperfExportMenuModel,
    val configurationMenu: SimpleperfConfigurationMenuModel,
    val recentItems: List<SimpleperfRecentMenuItem>,
    val exportEnabled: Boolean,
)

internal fun simpleperfFileMenuModel(
    language: UiLanguage,
    recentSessions: List<Path>,
    exportEnabled: Boolean,
    configurationEnabled: Boolean = true,
): SimpleperfFileMenuModel =
    SimpleperfFileMenuModel(
        exportMenu =
            SimpleperfExportMenuModel(
                sessionPackageLabel = localizedStringResource(Res.string.sp_export_session_package, language),
                reportLabel = localizedStringResource(Res.string.sp_export_json_csv, language),
                geckoProfileLabel = localizedStringResource(Res.string.sp_export_firefox_profiler_json, language),
                rawProtobufLabel = localizedStringResource(Res.string.sp_export_raw_protobuf, language),
                screenshotLabel = localizedStringResource(Res.string.sp_export_screenshot, language),
                simpleperfReportLabel = localizedStringResource(Res.string.sp_export_simpleperf_report, language),
                htmlReportLabel = localizedStringResource(Res.string.sp_export_report_html_py, language),
                externalOpenLabel = localizedStringResource(Res.string.sp_export_external_open, language),
                foldedStacksLabel = localizedStringResource(Res.string.sp_export_folded_stacks, language),
                pprofLabel = localizedStringResource(Res.string.sp_export_pprof, language),
            ),
        configurationMenu =
            SimpleperfConfigurationMenuModel(
                title = localizedStringResource(Res.string.sp_menu_configuration, language),
                samplingTemplateLabel = localizedStringResource(Res.string.sp_settings_capture_templates, language),
                captureConfigurationLabel =
                    localizedStringResource(Res.string.sp_settings_capture_configuration, language),
                advancedParametersLabel = localizedStringResource(Res.string.sp_settings_advanced_parameters, language),
                enabled = configurationEnabled,
            ),
        recentItems = recentSessions.toRecentMenuItems(),
        exportEnabled = exportEnabled,
    )

@Composable
@Suppress(
    "FunctionName",
    "LongMethod",
    "LongParameterList",
    "ktlint:standard:function-naming",
)
internal fun FrameWindowScope.SimpleperfFileMenuBar(
    model: SimpleperfFileMenuModel,
    onOpen: () -> Unit,
    onOpenFolder: () -> Unit,
    exportActions: SimpleperfExportMenuActions,
    onOpenRecent: (Path) -> Unit,
    onClearRecent: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCaptureSettings: (CaptureSettingsSection) -> Unit,
) {
    val export = model.exportMenu
    val exportEnabled = model.exportEnabled
    ActivePageMenu(
        PageMenu(
            onOpen = onOpen,
            onOpenFolder = onOpenFolder,
            recent =
                RecentMenu(
                    items = model.recentItems.map { item -> MenuCommand(item.label) { onOpenRecent(item.path) } },
                    onClear = onClearRecent,
                ),
            exportGroups =
                listOf(
                    listOf(
                        MenuCommand(
                            export.reportLabel,
                            enabled = exportEnabled,
                            shortcut = MenuShortcuts.export(),
                            onClick = exportActions.onReport,
                        ),
                        MenuCommand(export.sessionPackageLabel, enabled = exportEnabled, onClick = exportActions.onSessionPackage),
                        MenuCommand(export.screenshotLabel, enabled = exportEnabled, onClick = exportActions.onScreenshot),
                    ),
                    listOf(
                        MenuCommand(export.rawProtobufLabel, enabled = exportEnabled, onClick = exportActions.onRawProtobuf),
                        MenuCommand(export.geckoProfileLabel, enabled = exportEnabled, onClick = exportActions.onGeckoProfile),
                        MenuCommand(export.foldedStacksLabel, enabled = exportEnabled, onClick = exportActions.onFoldedStacks),
                        MenuCommand(export.pprofLabel, enabled = exportEnabled, onClick = exportActions.onPprof),
                    ),
                    listOf(
                        MenuCommand(export.simpleperfReportLabel, enabled = exportEnabled, onClick = exportActions.onSimpleperfReport),
                        MenuCommand(export.htmlReportLabel, enabled = exportEnabled, onClick = exportActions.onHtmlReport),
                        MenuCommand(export.externalOpenLabel, enabled = exportEnabled, onClick = exportActions.onExternalOpen),
                    ),
                ),
            onOpenSettings = onOpenSettings,
            tools = {
                val configuration = model.configurationMenu
                Menu(configuration.title, enabled = configuration.enabled) {
                    Item(configuration.samplingTemplateLabel) {
                        onOpenCaptureSettings(CaptureSettingsSection.SAMPLING_TEMPLATE)
                    }
                    Item(configuration.captureConfigurationLabel) {
                        onOpenCaptureSettings(CaptureSettingsSection.CAPTURE_CONFIGURATION)
                    }
                    Item(configuration.advancedParametersLabel) {
                        onOpenCaptureSettings(CaptureSettingsSection.ADVANCED_PARAMETERS)
                    }
                }
            },
        ),
    )
}

internal fun List<Path>.toRecentMenuItems(): List<SimpleperfRecentMenuItem> {
    val normalized = map { it.toAbsolutePath().normalize() }.distinct()
    val duplicateNames =
        normalized
            .groupingBy { it.fileName?.toString().orEmpty() }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    return normalized.map { path ->
        val fileName = path.fileName?.toString().orEmpty()
        SimpleperfRecentMenuItem(
            label = if (fileName.isBlank() || fileName in duplicateNames) path.toString() else fileName,
            path = path,
        )
    }
}
