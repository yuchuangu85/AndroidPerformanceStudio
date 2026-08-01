@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod")

package com.androidperformancestudio.benchmark.app

import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.benchmark.benchmark_app.generated.resources.Res
import com.androidperformancestudio.benchmark.benchmark_app.generated.resources.*

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.benchmark.analysis.RegressionAnalyzer
import com.androidperformancestudio.benchmark.export.BenchmarkReportExporter
import com.androidperformancestudio.benchmark.model.RegressionPolicy
import com.androidperformancestudio.benchmark.parser.BenchmarkJsonParser
import com.androidperformancestudio.benchmark.presentation.BenchmarkRegressionScreen
import com.androidperformancestudio.benchmark.presentation.BenchmarkRegressionState
import com.androidperformancestudio.benchmark.storage.SqliteBenchmarkStore
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.button.HomeButton
import com.androidperformancestudio.ui.ProfilerMacOsToolbar
import com.androidperformancestudio.ui.ProfilerToolbarStatus
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
public fun FrameWindowScope.BenchmarkRegressionMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
    onBack: () -> Unit = {},
    onOpenTrace: (Path) -> Unit = {},
) {
    val parser = remember { BenchmarkJsonParser() }
    val analyzer = remember { RegressionAnalyzer() }
    val exporter = remember { BenchmarkReportExporter() }
    val storePath = remember { Path.of(System.getProperty("user.home"), ".android-performance-studio", "benchmark", "benchmark.db") }
    var state by remember { mutableStateOf(BenchmarkRegressionState(thresholdPercent = 5.0)) }

    fun import(file: File, baseline: Boolean) {
        runCatching { parser.parse(file.toPath()) }.onSuccess { run ->
            runCatching { SqliteBenchmarkStore.open(storePath).use { it.save(run) } }
            state = if (baseline) state.copy(baseline = run, message = localizedStringResource(Res.string.imported_baseline, language, file.name), error = null) else state.copy(current = run, message = localizedStringResource(Res.string.imported_current, language, file.name), error = null)
            val current = state.current
            val reference = state.baseline
            if (current != null && reference != null) state = state.copy(report = analyzer.compare(reference, current, RegressionPolicy(relativeThresholdPercent = state.thresholdPercent)))
        }.onFailure { state = state.copy(error = it.message ?: localizedStringResource(Res.string.import_failed, language)) }
    }

    Column(Modifier.fillMaxSize()) {
        ProfilerMacOsToolbar {
            HomeButton(
                contentDescription = localizedStringResource(Res.string.back_to_home, language),
                onClick = onBack,
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.import_current, language),
                onClick = { chooseJson(window, language)?.let { import(it, false) } },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.import_baseline, language),
                onClick = { chooseJson(window, language)?.let { import(it, true) } },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.export_report, language),
                enabled = state.report != null,
                onClick = {
                    chooseSave(window, "benchmark-regression.json")
                        ?.let { exporter.writeJson(requireNotNull(state.report), it.toPath()) }
                },
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.open_trace_in_perfetto, language),
                enabled = state.current?.cases?.any { it.traceArtifacts.isNotEmpty() } == true,
                onClick = {
                    state.current
                        ?.cases
                        ?.flatMap { it.traceArtifacts }
                        ?.firstOrNull()
                        ?.let(onOpenTrace)
                },
            )
            Spacer(Modifier.weight(1f))
            ProfilerToolbarStatus(state.message, state.error)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        BenchmarkRegressionScreen(state, language, Modifier.weight(1f))
    }
}

private fun chooseJson(parent: java.awt.Component, language: UiLanguage): File? = JFileChooser().run {
    dialogTitle = localizedStringResource(Res.string.import_androidx_benchmark_json, language)
    fileFilter = FileNameExtensionFilter(localizedStringResource(Res.string.benchmark_json, language), "json")
    if (showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
}

private fun chooseSave(parent: java.awt.Component, name: String): File? = JFileChooser().run {
    selectedFile = File(name)
    if (showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
}
