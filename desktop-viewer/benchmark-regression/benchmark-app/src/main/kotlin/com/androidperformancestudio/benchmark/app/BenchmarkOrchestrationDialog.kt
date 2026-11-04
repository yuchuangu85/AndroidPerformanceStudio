@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList")

package com.androidperformancestudio.benchmark.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.benchmark.benchmark_app.generated.resources.*
import com.androidperformancestudio.benchmark.benchmark_app.generated.resources.Res
import com.androidperformancestudio.benchmark.cli.MacrobenchmarkCompilationMode
import com.androidperformancestudio.benchmark.cli.MacrobenchmarkScenario
import com.androidperformancestudio.benchmark.cli.MacrobenchmarkStartupMode
import com.androidperformancestudio.ui.CopyableText
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTextField
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.chooseDirectory
import com.androidperformancestudio.ui.chooseSaveFile
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioTokens
import java.awt.Component

private const val OUTPUT_TAIL_CHARACTERS = 8000

/**
 * Hosts the in-app Macrobenchmark orchestration. The dialog performs no IO itself: the workspace runs
 * the plan and feeds [output] back, so the form stays testable and the process never starts from a
 * composable side effect.
 */
@Composable
internal fun BenchmarkOrchestrationDialog(
    form: BenchmarkRunPlanForm,
    onFormChange: (BenchmarkRunPlanForm) -> Unit,
    running: Boolean,
    output: String,
    status: String?,
    problem: BenchmarkPlanProblem?,
    language: UiLanguage,
    parent: Component?,
    onRun: () -> Unit,
    onCancelRun: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalViewerColors.current
    AlertDialog(
        onDismissRequest = { if (!running) onClose() },
        title = { Text(localizedStringResource(Res.string.orchestrate_title, language)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap),
            ) {
                CopyableText(
                    text = localizedStringResource(Res.string.orchestrate_subtitle, language),
                    color = colors.secondaryText,
                    fontSize = ViewerTypography.bodyCompact.fontSize,
                    lineHeight = ViewerTypography.bodyCompact.lineHeight,
                    language = language,
                )
                PathField(
                    label = localizedStringResource(Res.string.plan_project, language),
                    value = form.projectRoot,
                    enabled = !running,
                    onValueChange = { onFormChange(form.copy(projectRoot = it)) },
                    onBrowse = {
                        chooseDirectory(parent, localizedStringResource(Res.string.plan_project, language))
                            ?.let { onFormChange(form.copy(projectRoot = it.absolutePath)) }
                    },
                    language = language,
                )
                ViewerTextField(
                    value = form.gradleTask,
                    onValueChange = { onFormChange(form.copy(gradleTask = it)) },
                    label = { Text(localizedStringResource(Res.string.plan_task, language)) },
                    singleLine = true,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                )
                ViewerTextField(
                    value = form.benchmarkFilter,
                    onValueChange = { onFormChange(form.copy(benchmarkFilter = it)) },
                    label = { Text(localizedStringResource(Res.string.plan_filter, language)) },
                    singleLine = true,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                )
                EnumRow(
                    label = localizedStringResource(Res.string.plan_compilation, language),
                    items = MacrobenchmarkCompilationMode.entries,
                    selected = form.compilationMode,
                    enabled = !running,
                    onSelected = { onFormChange(form.copy(compilationMode = it)) },
                )
                EnumRow(
                    label = localizedStringResource(Res.string.plan_startup, language),
                    items = MacrobenchmarkStartupMode.entries,
                    selected = form.startupMode,
                    enabled = !running,
                    onSelected = { onFormChange(form.copy(startupMode = it)) },
                )
                EnumRow(
                    label = localizedStringResource(Res.string.plan_scenario, language),
                    items = MacrobenchmarkScenario.entries,
                    selected = form.scenario,
                    enabled = !running,
                    onSelected = { onFormChange(form.copy(scenario = it)) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.panelGap)) {
                    NumberField(
                        label = localizedStringResource(Res.string.plan_warmups, language),
                        value = form.warmups,
                        enabled = !running,
                        onValueChange = { onFormChange(form.copy(warmups = it)) },
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        label = localizedStringResource(Res.string.plan_iterations, language),
                        value = form.iterations,
                        enabled = !running,
                        onValueChange = { onFormChange(form.copy(iterations = it)) },
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        label = localizedStringResource(Res.string.plan_timeout, language),
                        value = form.timeoutSeconds,
                        enabled = !running,
                        onValueChange = { onFormChange(form.copy(timeoutSeconds = it)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                PathField(
                    label = localizedStringResource(Res.string.plan_trace_dir, language),
                    value = form.traceDirectory,
                    enabled = !running,
                    onValueChange = { onFormChange(form.copy(traceDirectory = it)) },
                    onBrowse = {
                        chooseDirectory(parent, localizedStringResource(Res.string.plan_trace_dir, language))
                            ?.let { onFormChange(form.copy(traceDirectory = it.absolutePath)) }
                    },
                    language = language,
                )
                PathField(
                    label = localizedStringResource(Res.string.plan_result_json, language),
                    value = form.resultJson,
                    enabled = !running,
                    onValueChange = { onFormChange(form.copy(resultJson = it)) },
                    onBrowse = {
                        chooseSaveFile(
                            parent,
                            localizedStringResource(Res.string.plan_result_json, language),
                            "benchmarkData.json",
                        )?.let { onFormChange(form.copy(resultJson = it.absolutePath)) }
                    },
                    language = language,
                )
                problem?.let {
                    CopyableText(
                        text = it.message(language),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = ViewerTypography.bodyCompact.fontSize,
                        language = language,
                    )
                }
                if (running) {
                    CopyableText(
                        text = localizedStringResource(Res.string.orchestrate_running, language),
                        color = colors.accent,
                        fontSize = ViewerTypography.bodyCompact.fontSize,
                        language = language,
                    )
                }
                status?.let {
                    CopyableText(
                        text = it,
                        color = colors.primaryText,
                        fontSize = ViewerTypography.bodyCompact.fontSize,
                        language = language,
                    )
                }
                if (output.isNotBlank()) {
                    Text(
                        text = localizedStringResource(Res.string.orchestrate_output, language),
                        color = colors.secondaryText,
                        fontSize = ViewerTypography.label.fontSize,
                    )
                    CopyableText(
                        text = output.takeLast(OUTPUT_TAIL_CHARACTERS),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .horizontalScroll(rememberScrollState()),
                        color = colors.primaryText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = ViewerTypography.dense.fontSize,
                        lineHeight = ViewerTypography.dense.lineHeight,
                        language = language,
                    )
                }
            }
        },
        confirmButton = {
            if (running) {
                TextButton(onClick = onCancelRun) {
                    Text(localizedStringResource(Res.string.orchestrate_cancel, language))
                }
            } else {
                TextButton(onClick = onRun) {
                    Text(localizedStringResource(Res.string.orchestrate_start, language))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, enabled = !running) {
                Text(localizedStringResource(Res.string.orchestrate_close, language))
            }
        },
    )
}

@Composable
private fun PathField(
    label: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onBrowse: () -> Unit,
    language: UiLanguage,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.panelGap)) {
        ViewerTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        ProfilerCompactButton(
            text = localizedStringResource(Res.string.plan_browse, language),
            onClick = onBrowse,
            enabled = enabled,
        )
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ViewerTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        modifier = modifier,
    )
}

@Composable
private fun <T> EnumRow(
    label: String,
    items: List<T>,
    selected: T,
    enabled: Boolean,
    onSelected: (T) -> Unit,
) {
    val colors = LocalViewerColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.width(160.dp).padding(end = StudioTokens.panelGap),
            color = colors.secondaryText,
            fontSize = ViewerTypography.bodyCompact.fontSize,
        )
        DropdownSelector(
            items = items,
            selectedItem = selected,
            onItemSelected = onSelected,
            itemLabel = { it.toString() },
            placeholder = "",
            enabled = enabled,
            modifier = Modifier.weight(1f),
            fillWidth = true,
        )
    }
}

@Composable
private fun BenchmarkPlanProblem.message(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            BenchmarkPlanProblem.PROJECT_REQUIRED -> Res.string.orchestrate_project_required
            BenchmarkPlanProblem.PROJECT_NOT_A_DIRECTORY -> Res.string.orchestrate_project_missing
            BenchmarkPlanProblem.WARMUPS_INVALID -> Res.string.orchestrate_warmups_invalid
            BenchmarkPlanProblem.ITERATIONS_INVALID -> Res.string.orchestrate_iterations_invalid
            BenchmarkPlanProblem.TIMEOUT_INVALID -> Res.string.orchestrate_timeout_invalid
        },
        language,
    )
