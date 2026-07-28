package com.androidperformancestudio.desktop

import org.jetbrains.compose.resources.stringResource

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import com.androidperformancestudio.presentation.CaptureSettingsSection
import com.androidperformancestudio.app_desktop.generated.resources.Res
import com.androidperformancestudio.app_desktop.generated.resources.*
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

@Composable
internal fun simpleperfFileMenuModel(
    recentSessions: List<Path>,
    exportEnabled: Boolean,
    isMacOs: Boolean,
    configurationEnabled: Boolean = true,
): SimpleperfFileMenuModel =
    SimpleperfFileMenuModel(
        fileTitle = stringResource(Res.string.file),
        openLabel = stringResource(Res.string.open),
        settingsLabel = stringResource(Res.string.settings).takeUnless { isMacOs },
        exportMenu =
            SimpleperfExportMenuModel(
                title = stringResource(Res.string.export),
                sessionPackageLabel = stringResource(Res.string.session_package),
                reportLabel = stringResource(Res.string.json_csv),
                geckoProfileLabel = stringResource(Res.string.firefox_profiler_json),
                rawProtobufLabel = stringResource(Res.string.raw_protobuf),
                screenshotLabel = stringResource(Res.string.screenshot),
                simpleperfReportLabel = stringResource(Res.string.simpleperf_report),
                htmlReportLabel = stringResource(Res.string.report_html_py),
                externalOpenLabel = stringResource(Res.string.external_open),
            ),
        configurationMenu =
            SimpleperfConfigurationMenuModel(
                title = stringResource(Res.string.configuration),
                samplingTemplateLabel = stringResource(Res.string.capture_templates),
                captureConfigurationLabel = stringResource(Res.string.capture_configuration),
                advancedParametersLabel = stringResource(Res.string.advanced_parameters),
                enabled = configurationEnabled,
            ),
        openRecentTitle = stringResource(Res.string.open_recent),
        noRecentLabel = stringResource(Res.string.no_recent_sessions),
        clearRecentLabel = stringResource(Res.string.clear_menu),
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
    MenuBar {
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
