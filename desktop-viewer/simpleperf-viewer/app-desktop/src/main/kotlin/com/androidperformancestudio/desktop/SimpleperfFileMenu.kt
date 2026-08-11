package com.androidperformancestudio.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.ui.ActiveWindowMenuBar
import com.androidperformancestudio.app_desktop.generated.resources.Res
import com.androidperformancestudio.app_desktop.generated.resources.sp_export_external_open
import com.androidperformancestudio.app_desktop.generated.resources.sp_export_firefox_profiler_json
import com.androidperformancestudio.app_desktop.generated.resources.sp_export_json_csv
import com.androidperformancestudio.app_desktop.generated.resources.sp_export_raw_protobuf
import com.androidperformancestudio.app_desktop.generated.resources.sp_export_report_html_py
import com.androidperformancestudio.app_desktop.generated.resources.sp_export_screenshot
import com.androidperformancestudio.app_desktop.generated.resources.sp_export_session_package
import com.androidperformancestudio.app_desktop.generated.resources.sp_export_simpleperf_report
import com.androidperformancestudio.app_desktop.generated.resources.sp_menu_configuration
import com.androidperformancestudio.app_desktop.generated.resources.sp_menu_export
import com.androidperformancestudio.app_desktop.generated.resources.sp_menu_file
import com.androidperformancestudio.app_desktop.generated.resources.sp_menu_open
import com.androidperformancestudio.app_desktop.generated.resources.sp_menu_settings
import com.androidperformancestudio.app_desktop.generated.resources.sp_recent_clear_menu
import com.androidperformancestudio.app_desktop.generated.resources.sp_recent_empty
import com.androidperformancestudio.app_desktop.generated.resources.sp_recent_open
import com.androidperformancestudio.app_desktop.generated.resources.sp_settings_advanced_parameters
import com.androidperformancestudio.app_desktop.generated.resources.sp_settings_capture_configuration
import com.androidperformancestudio.app_desktop.generated.resources.sp_settings_capture_templates
import com.androidperformancestudio.presentation.CaptureSettingsSection
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Path

internal data class SimpleperfMenuShortcut(
    val key: Key,
    val ctrl: Boolean,
    val meta: Boolean,
)

internal data class SimpleperfRecentMenuItem(
    val label: String,
    val path: Path,
)

internal data class SimpleperfExportMenuModel(
    val title: String,
    val sessionPackageLabel: String,
    val reportLabel: String,
    val geckoProfileLabel: String,
    val rawProtobufLabel: String,
    val screenshotLabel: String,
    val simpleperfReportLabel: String,
    val htmlReportLabel: String,
    val externalOpenLabel: String,
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
)

internal data class SimpleperfConfigurationMenuModel(
    val title: String,
    val samplingTemplateLabel: String,
    val captureConfigurationLabel: String,
    val advancedParametersLabel: String,
    val enabled: Boolean,
)

internal data class SimpleperfFileMenuModel(
    val fileTitle: String,
    val openLabel: String,
    val settingsLabel: String?,
    val exportMenu: SimpleperfExportMenuModel,
    val configurationMenu: SimpleperfConfigurationMenuModel,
    val openRecentTitle: String,
    val noRecentLabel: String,
    val clearRecentLabel: String,
    val recentItems: List<SimpleperfRecentMenuItem>,
    val exportEnabled: Boolean,
    val openShortcut: SimpleperfMenuShortcut,
    val exportShortcut: SimpleperfMenuShortcut,
    val settingsShortcut: SimpleperfMenuShortcut?,
)

internal fun simpleperfFileMenuModel(
    language: UiLanguage,
    recentSessions: List<Path>,
    exportEnabled: Boolean,
    isMacOs: Boolean,
    configurationEnabled: Boolean = true,
): SimpleperfFileMenuModel =
    SimpleperfFileMenuModel(
        fileTitle = localizedStringResource(Res.string.sp_menu_file, language),
        openLabel = localizedStringResource(Res.string.sp_menu_open, language),
        settingsLabel = localizedStringResource(Res.string.sp_menu_settings, language).takeUnless { isMacOs },
        exportMenu =
            SimpleperfExportMenuModel(
                title = localizedStringResource(Res.string.sp_menu_export, language),
                sessionPackageLabel = localizedStringResource(Res.string.sp_export_session_package, language),
                reportLabel = localizedStringResource(Res.string.sp_export_json_csv, language),
                geckoProfileLabel = localizedStringResource(Res.string.sp_export_firefox_profiler_json, language),
                rawProtobufLabel = localizedStringResource(Res.string.sp_export_raw_protobuf, language),
                screenshotLabel = localizedStringResource(Res.string.sp_export_screenshot, language),
                simpleperfReportLabel = localizedStringResource(Res.string.sp_export_simpleperf_report, language),
                htmlReportLabel = localizedStringResource(Res.string.sp_export_report_html_py, language),
                externalOpenLabel = localizedStringResource(Res.string.sp_export_external_open, language),
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
        openRecentTitle = localizedStringResource(Res.string.sp_recent_open, language),
        noRecentLabel = localizedStringResource(Res.string.sp_recent_empty, language),
        clearRecentLabel = localizedStringResource(Res.string.sp_recent_clear_menu, language),
        recentItems = recentSessions.toRecentMenuItems(),
        exportEnabled = exportEnabled,
        openShortcut = primaryShortcut(Key.O, isMacOs),
        exportShortcut = primaryShortcut(Key.E, isMacOs),
        settingsShortcut = primaryShortcut(Key.Comma, isMacOs).takeUnless { isMacOs },
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
    exportActions: SimpleperfExportMenuActions,
    onOpenRecent: (Path) -> Unit,
    onClearRecent: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCaptureSettings: (CaptureSettingsSection) -> Unit,
) {
    ActiveWindowMenuBar {
        Menu(model.fileTitle) {
            Item(
                text = model.openLabel,
                shortcut = model.openShortcut.toKeyShortcut(),
                onClick = onOpen,
            )
            Menu(model.exportMenu.title) {
                Item(
                    text = model.exportMenu.sessionPackageLabel,
                    enabled = model.exportEnabled,
                    onClick = exportActions.onSessionPackage,
                )
                Item(
                    text = model.exportMenu.reportLabel,
                    enabled = model.exportEnabled,
                    shortcut = model.exportShortcut.toKeyShortcut(),
                    onClick = exportActions.onReport,
                )
                Item(
                    text = model.exportMenu.rawProtobufLabel,
                    enabled = model.exportEnabled,
                    onClick = exportActions.onRawProtobuf,
                )
                Item(
                    text = model.exportMenu.geckoProfileLabel,
                    enabled = model.exportEnabled,
                    onClick = exportActions.onGeckoProfile,
                )
                Item(
                    text = model.exportMenu.screenshotLabel,
                    enabled = model.exportEnabled,
                    onClick = exportActions.onScreenshot,
                )
                Item(
                    text = model.exportMenu.simpleperfReportLabel,
                    enabled = model.exportEnabled,
                    onClick = exportActions.onSimpleperfReport,
                )
                Item(
                    text = model.exportMenu.htmlReportLabel,
                    enabled = model.exportEnabled,
                    onClick = exportActions.onHtmlReport,
                )
                Item(
                    text = model.exportMenu.externalOpenLabel,
                    enabled = model.exportEnabled,
                    onClick = exportActions.onExternalOpen,
                )
            }
            Menu(model.openRecentTitle) {
                if (model.recentItems.isEmpty()) {
                    Item(text = model.noRecentLabel, enabled = false, onClick = {})
                } else {
                    model.recentItems.forEach { item ->
                        Item(text = item.label, onClick = { onOpenRecent(item.path) })
                    }
                    Separator()
                    Item(text = model.clearRecentLabel, onClick = onClearRecent)
                }
            }
            val settingsLabel = model.settingsLabel
            val settingsShortcut = model.settingsShortcut
            if (settingsLabel != null && settingsShortcut != null) {
                Separator()
                Item(
                    text = settingsLabel,
                    shortcut = settingsShortcut.toKeyShortcut(),
                    onClick = onOpenSettings,
                )
            }
        }
        Menu(model.configurationMenu.title, enabled = model.configurationMenu.enabled) {
            Item(
                text = model.configurationMenu.samplingTemplateLabel,
                onClick = { onOpenCaptureSettings(CaptureSettingsSection.SAMPLING_TEMPLATE) },
            )
            Item(
                text = model.configurationMenu.captureConfigurationLabel,
                onClick = { onOpenCaptureSettings(CaptureSettingsSection.CAPTURE_CONFIGURATION) },
            )
            Item(
                text = model.configurationMenu.advancedParametersLabel,
                onClick = { onOpenCaptureSettings(CaptureSettingsSection.ADVANCED_PARAMETERS) },
            )
        }
    }
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

internal fun primaryShortcut(
    key: Key,
    isMacOs: Boolean,
): SimpleperfMenuShortcut =
    SimpleperfMenuShortcut(
        key = key,
        ctrl = !isMacOs,
        meta = isMacOs,
    )

private fun SimpleperfMenuShortcut.toKeyShortcut(): KeyShortcut =
    KeyShortcut(
        key = key,
        ctrl = ctrl,
        meta = meta,
    )
