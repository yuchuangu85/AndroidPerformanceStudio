package com.androidperformancestudio.desktop

import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.androidperformancestudio.compose.inspection.ComposeCompilerReport
import com.androidperformancestudio.presentation.generated.resources.Res
import com.androidperformancestudio.presentation.generated.resources.*
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.chooseDirectory
import com.androidperformancestudio.ui.chooseOpenFile
import com.androidperformancestudio.ui.localizedStringResource
import java.awt.Component
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class ComposeReportImportSource {
    FILE,
    DIRECTORY,
}

private fun ComposeReportImportSource.label(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            ComposeReportImportSource.FILE -> Res.string.compose_reports_select_file
            ComposeReportImportSource.DIRECTORY -> Res.string.compose_reports_select_directory
        },
        language,
    )

/**
 * Status bar entry for compiler reports, styled like the capture target selector.
 *
 * The status bar is a SelectionContainer. Menu text lives in a popup layout hierarchy, so it must
 * not register with that container: a press would make selection compare coordinates across the
 * two hierarchies and throw "layouts are not part of the same hierarchy".
 */
@Composable
internal fun ComposeReportImportSelector(
    importInProgress: Boolean,
    language: UiLanguage,
    onImport: (ComposeReportImportSource) -> Unit,
) = DisableSelection {
    DropdownSelector(
        items = ComposeReportImportSource.entries,
        selectedItem = null,
        onItemSelected = onImport,
        itemLabel = { it.label(language) },
        placeholder = localizedStringResource(
            if (importInProgress) Res.string.compose_reports_importing else Res.string.import_compose_compiler_reports,
            language,
        ),
        enabled = !importInProgress,
        selectorDescription = localizedStringResource(Res.string.import_compose_compiler_reports, language),
        searchLanguage = language,
    )
}

internal fun chooseComposeCompilerReportSource(
    parent: Component?,
    source: ComposeReportImportSource,
    language: UiLanguage,
): Path? = when (source) {
    ComposeReportImportSource.FILE -> chooseOpenFile(
        parent,
        source.label(language),
        localizedStringResource(Res.string.compose_reports_file_filter, language),
        "txt",
        "csv",
        acceptAllFiles = false,
    )?.toPath()
    ComposeReportImportSource.DIRECTORY -> chooseDirectory(parent, source.label(language))?.toPath()
}

internal sealed interface ComposeCompilerReportImportState {
    data object Idle : ComposeCompilerReportImportState
    data object Working : ComposeCompilerReportImportState
    data class Success(val report: ComposeCompilerReport) : ComposeCompilerReportImportState
    data class Failure(val message: String) : ComposeCompilerReportImportState
}

internal class ComposeCompilerReportImport(
    private val parseReport: suspend (Path) -> ComposeCompilerReport,
    private val loadReport: (ComposeCompilerReport) -> Unit,
    private val workContext: CoroutineContext = Dispatchers.IO,
) {
    var uiState by mutableStateOf<ComposeCompilerReportImportState>(ComposeCompilerReportImportState.Idle)
        private set

    suspend fun importReport(chooseSource: () -> Path?): Boolean {
        if (uiState == ComposeCompilerReportImportState.Working) return false
        uiState = ComposeCompilerReportImportState.Working
        return try {
            currentCoroutineContext().ensureActive()
            val source = chooseSource() ?: return false
            val report = withContext(workContext) { parseReport(source) }
            currentCoroutineContext().ensureActive()
            loadReport(report)
            uiState = ComposeCompilerReportImportState.Success(report)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            uiState = ComposeCompilerReportImportState.Failure(error.message ?: error.javaClass.simpleName)
            false
        } finally {
            if (uiState == ComposeCompilerReportImportState.Working) {
                uiState = ComposeCompilerReportImportState.Idle
            }
        }
    }

    fun dismissResult() {
        if (uiState != ComposeCompilerReportImportState.Working) {
            uiState = ComposeCompilerReportImportState.Idle
        }
    }
}

internal fun composeCompilerReportImportMessage(report: ComposeCompilerReport, language: UiLanguage): String =
    buildString {
        append(localizedStringResource(
            Res.string.compose_reports_import_succeeded,
            language,
            report.files.size,
            report.functions.size,
            report.classes.size,
            report.sourceDirectory,
        ))
        append("\n\n")
        append(localizedStringResource(Res.string.compose_reports_imported_files, language, report.files.joinToString("\n")))
        if (report.warnings.isNotEmpty()) {
            append("\n\n")
            append(localizedStringResource(Res.string.compose_reports_import_warnings, language, report.warnings.joinToString("\n")))
        }
    }
