@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.androidperformancestudio.startup.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.ui.ActiveWindowMenuBar
import com.androidperformancestudio.startup.startup_app.generated.resources.Res
import com.androidperformancestudio.startup.startup_app.generated.resources.export
import com.androidperformancestudio.startup.startup_app.generated.resources.export_csv
import com.androidperformancestudio.startup.startup_app.generated.resources.export_json
import com.androidperformancestudio.startup.startup_app.generated.resources.file
import com.androidperformancestudio.startup.startup_app.generated.resources.import_report
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

internal data class StartupProfilerExportMenuModel(
    val title: String,
    val csvLabel: String,
    val jsonLabel: String,
    val enabled: Boolean,
)

internal data class StartupProfilerFileMenuModel(
    val fileTitle: String,
    val importLabel: String,
    val importEnabled: Boolean,
    val exportMenu: StartupProfilerExportMenuModel,
)

internal fun startupProfilerFileMenuModel(
    language: UiLanguage,
    importEnabled: Boolean,
    exportEnabled: Boolean,
): StartupProfilerFileMenuModel =
    StartupProfilerFileMenuModel(
        fileTitle = localizedStringResource(Res.string.file, language),
        importLabel = localizedStringResource(Res.string.import_report, language),
        importEnabled = importEnabled,
        exportMenu =
            StartupProfilerExportMenuModel(
                title = localizedStringResource(Res.string.export, language),
                csvLabel = localizedStringResource(Res.string.export_csv, language),
                jsonLabel = localizedStringResource(Res.string.export_json, language),
                enabled = exportEnabled,
            ),
    )

@Composable
internal fun FrameWindowScope.StartupProfilerFileMenuBar(
    model: StartupProfilerFileMenuModel,
    onImport: () -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
) {
    ActiveWindowMenuBar {
        Menu(model.fileTitle) {
            Item(
                text = model.importLabel,
                enabled = model.importEnabled,
                onClick = onImport,
            )
            Menu(model.exportMenu.title) {
                Item(
                    text = model.exportMenu.csvLabel,
                    enabled = model.exportMenu.enabled,
                    onClick = onExportCsv,
                )
                Item(
                    text = model.exportMenu.jsonLabel,
                    enabled = model.exportMenu.enabled,
                    onClick = onExportJson,
                )
            }
        }
    }
}
