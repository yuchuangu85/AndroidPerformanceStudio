@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod")

package com.androidperformancestudio.benchmark.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.benchmark.analysis.RegressionAnalyzer
import com.androidperformancestudio.benchmark.export.BenchmarkReportExporter
import com.androidperformancestudio.benchmark.model.RegressionPolicy
import com.androidperformancestudio.benchmark.parser.BenchmarkJsonParser
import com.androidperformancestudio.benchmark.presentation.BenchmarkRegressionScreen
import com.androidperformancestudio.benchmark.presentation.BenchmarkRegressionState
import com.androidperformancestudio.benchmark.storage.SqliteBenchmarkStore
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
public fun FrameWindowScope.BenchmarkRegressionWorkspace(
    chinese: Boolean = false,
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
            state = if (baseline) state.copy(baseline = run, message = "Imported baseline ${file.name}", error = null) else state.copy(current = run, message = "Imported current result ${file.name}", error = null)
            val current = state.current
            val reference = state.baseline
            if (current != null && reference != null) state = state.copy(report = analyzer.compare(reference, current, RegressionPolicy(relativeThresholdPercent = state.thresholdPercent)))
        }.onFailure { state = state.copy(error = it.message ?: "Import failed") }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text(if (chinese) "返回主页" else "Back to Home") }
            OutlinedButton(onClick = { chooseJson(window)?.let { import(it, false) } }) { Text(if (chinese) "导入当前结果" else "Import Current") }
            OutlinedButton(onClick = { chooseJson(window)?.let { import(it, true) } }) { Text(if (chinese) "导入基线" else "Import Baseline") }
            OutlinedButton(enabled = state.report != null, onClick = { chooseSave(window, "benchmark-regression.json")?.let { exporter.writeJson(requireNotNull(state.report), it.toPath()) } }) { Text(if (chinese) "导出报告" else "Export Report") }
            OutlinedButton(enabled = state.current?.cases?.any { it.traceArtifacts.isNotEmpty() } == true, onClick = { state.current?.cases?.flatMap { it.traceArtifacts }?.firstOrNull()?.let(onOpenTrace) }) { Text(if (chinese) "在 Perfetto 打开 Trace" else "Open Trace in Perfetto") }
        }
        BenchmarkRegressionScreen(state, chinese, Modifier.weight(1f))
    }
}

private fun chooseJson(parent: java.awt.Component): File? = JFileChooser().run {
    dialogTitle = "Import AndroidX Benchmark JSON"
    fileFilter = FileNameExtensionFilter("Benchmark JSON", "json")
    if (showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
}

private fun chooseSave(parent: java.awt.Component, name: String): File? = JFileChooser().run {
    selectedFile = File(name)
    if (showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) selectedFile else null
}
