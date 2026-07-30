@file:Suppress("FunctionName", "MatchingDeclarationName", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import com.androidperformancestudio.memory.memory_app.generated.resources.Res
import com.androidperformancestudio.memory.memory_app.generated.resources.export
import com.androidperformancestudio.memory.memory_app.generated.resources.export_csv
import com.androidperformancestudio.memory.memory_app.generated.resources.export_raw_hprof
import com.androidperformancestudio.memory.memory_app.generated.resources.export_standard_hprof
import com.androidperformancestudio.memory.memory_app.generated.resources.file
import com.androidperformancestudio.memory.memory_app.generated.resources.import_hprof_menu
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

internal data class MemoryProfilerFileMenuModel(
    val fileTitle: String,
    val importLabel: String,
    val exportTitle: String,
    val exportRawHprofLabel: String,
    val exportStandardHprofLabel: String,
    val exportCsvLabel: String,
    val importEnabled: Boolean,
    val rawHprofExportEnabled: Boolean,
    val standardHprofExportEnabled: Boolean,
    val csvExportEnabled: Boolean,
)

internal fun memoryProfilerFileMenuModel(
    language: UiLanguage,
    importEnabled: Boolean,
    rawHprofExportEnabled: Boolean,
    standardHprofExportEnabled: Boolean,
    csvExportEnabled: Boolean,
): MemoryProfilerFileMenuModel =
    MemoryProfilerFileMenuModel(
        fileTitle = localizedStringResource(Res.string.file, language),
        importLabel = localizedStringResource(Res.string.import_hprof_menu, language),
        exportTitle = localizedStringResource(Res.string.export, language),
        exportRawHprofLabel = localizedStringResource(Res.string.export_raw_hprof, language),
        exportStandardHprofLabel = localizedStringResource(Res.string.export_standard_hprof, language),
        exportCsvLabel = localizedStringResource(Res.string.export_csv, language),
        importEnabled = importEnabled,
        rawHprofExportEnabled = rawHprofExportEnabled,
        standardHprofExportEnabled = standardHprofExportEnabled,
        csvExportEnabled = csvExportEnabled,
    )

@Composable
internal fun FrameWindowScope.MemoryProfilerFileMenuBar(
    model: MemoryProfilerFileMenuModel,
    onImportHprof: () -> Unit,
    onExportRawHprof: () -> Unit,
    onExportStandardHprof: () -> Unit,
    onExportCsv: () -> Unit,
) {
    MenuBar {
        Menu(model.fileTitle) {
            Item(
                text = model.importLabel,
                enabled = model.importEnabled,
                onClick = onImportHprof,
            )
            Menu(model.exportTitle) {
                Item(
                    text = model.exportRawHprofLabel,
                    enabled = model.rawHprofExportEnabled,
                    onClick = onExportRawHprof,
                )
                Item(
                    text = model.exportStandardHprofLabel,
                    enabled = model.standardHprofExportEnabled,
                    onClick = onExportStandardHprof,
                )
                Item(
                    text = model.exportCsvLabel,
                    enabled = model.csvExportEnabled,
                    onClick = onExportCsv,
                )
            }
        }
    }
}
