@file:Suppress("MagicNumber", "MaxLineLength", "TooManyFunctions")

package com.androidperformancestudio.compose.inspection.host

import com.androidperformancestudio.compose.inspection.ComposeCompilerClass
import com.androidperformancestudio.compose.inspection.ComposeCompilerFunction
import com.androidperformancestudio.compose.inspection.ComposeCompilerParameter
import com.androidperformancestudio.compose.inspection.ComposeCompilerReport
import com.androidperformancestudio.compose.inspection.ComposeCompilerStability
import com.androidperformancestudio.compose.inspection.ComposeInspectionDocument
import com.androidperformancestudio.compose.inspection.ComposeJankObservation
import com.androidperformancestudio.compose.inspection.ComposeStabilityFinding
import com.androidperformancestudio.compose.inspection.ComposableNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

class ComposeCompilerReportParser {
    /**
     * Parses one module's reports when [selection] is a report file, or every module's reports
     * directly inside [selection] when it is a directory. The CSV and text composable reports describe
     * the same functions, so they are merged into one record per function instead of being kept twice.
     */
    fun parse(selection: Path): ComposeCompilerReport {
        val (directory, files) = reportFiles(selection)
        val warnings = mutableListOf<String>()
        val functions = mutableListOf<ComposeCompilerFunction>()
        val classes = mutableListOf<ComposeCompilerClass>()
        val parsedFiles = mutableListOf<Path>()
        files.groupBy(::modulePrefix).toSortedMap().values.forEach { moduleFiles ->
            var csvFunctions: List<ComposeCompilerFunction>? = null
            var textFunctions: List<ComposeCompilerFunction>? = null
            moduleFiles.forEach { file ->
                try {
                    when (reportKind(file.name)) {
                        ReportKind.COMPOSABLES_CSV -> csvFunctions = parseComposableCsv(file)
                        ReportKind.COMPOSABLES_TEXT -> textFunctions = parseComposableText(file)
                        ReportKind.CLASSES_TEXT -> classes += parseClasses(file)
                        null -> Unit
                    }
                    parsedFiles.add(file) // Path is also Iterable<Path>, so += is ambiguous.
                } catch (error: Exception) {
                    // One unreadable sibling should not hide the reports that did parse.
                    warnings += "Unable to parse ${file.fileName}: ${error.message ?: error.javaClass.simpleName}"
                }
            }
            functions += mergeFunctions(csvFunctions.orEmpty(), textFunctions.orEmpty())
        }
        require(parsedFiles.isNotEmpty() && (functions.isNotEmpty() || classes.isNotEmpty())) {
            buildString {
                append("No readable Compose Compiler report data in $selection")
                if (warnings.isNotEmpty()) append(warnings.joinToString(separator = "\n", prefix = "\n"))
            }
        }
        return ComposeCompilerReport(
            sourceDirectory = directory.toAbsolutePath().normalize().toString(),
            files = parsedFiles.map { it.toAbsolutePath().normalize().toString() },
            functions = functions,
            classes = classes.distinct(),
            warnings = warnings,
        )
    }

    private fun reportFiles(selection: Path): Pair<Path, List<Path>> =
        when {
            Files.isDirectory(selection) -> {
                val files = Files.list(selection).use { stream ->
                    stream.filter { it.isRegularFile() && reportKind(it.name) != null }.sorted().toList()
                }
                require(files.isNotEmpty()) {
                    "No Compose Compiler report files ($SUPPORTED_REPORTS) were found directly in $selection"
                }
                selection to files
            }
            selection.isRegularFile() -> {
                require(reportKind(selection.name) != null) {
                    "Unsupported Compose Compiler report file: $selection. Select one of $SUPPORTED_REPORTS."
                }
                val directory = selection.toAbsolutePath().parent
                val prefix = modulePrefix(selection)
                directory to Files.list(directory).use { stream ->
                    stream.filter { it.isRegularFile() && reportKind(it.name) != null && modulePrefix(it) == prefix }
                        .sorted()
                        .toList()
                }
            }
            else -> throw IllegalArgumentException("Compose Compiler report path does not exist: $selection")
        }

    /**
     * The compiler writes both composable reports from the same function list, so the n-th CSV row
     * named `Foo` describes the n-th text entry named `Foo`. CSV flags are explicit 0/1 values and
     * carry the package; the text report carries parameters.
     */
    private fun mergeFunctions(
        csv: List<ComposeCompilerFunction>,
        text: List<ComposeCompilerFunction>,
    ): List<ComposeCompilerFunction> {
        val textByName = text.groupBy(ComposeCompilerFunction::name).mapValues { ArrayDeque(it.value) }
        val merged = csv.map { row ->
            val details = textByName[row.name]?.removeFirstOrNull() ?: return@map row
            row.copy(parameters = details.parameters)
        }
        return merged + textByName.values.flatten()
    }

    private fun parseComposableText(file: Path): List<ComposeCompilerFunction> {
        val result = mutableListOf<ComposeCompilerFunction>()
        var current: MutableFunction? = null
        Files.readAllLines(file).forEach { line ->
            val functionMatch = FUNCTION.find(line)
            if (functionMatch != null) {
                current?.let { result += it.build() }
                val prefix = line.substring(0, functionMatch.range.first)
                current = MutableFunction(
                    name = functionMatch.groupValues[1].substringAfterLast('.'),
                    restartable = prefix.contains("restartable"),
                    skippable = prefix.contains("skippable"),
                    readonly = prefix.contains("readonly"),
                )
            } else if (line.trim() == ")") {
                current?.let { result += it.build() }
                current = null
            } else {
                val parameter = PARAMETER.find(line)
                if (parameter != null && current != null) {
                    current.parameters += ComposeCompilerParameter(
                        name = parameter.groupValues[2],
                        // Defaults are printed as `= @static value` or `= @dynamic value`.
                        type = parameter.groupValues[3].substringBefore(" = ").trim().trimEnd(',', ')').trim(),
                        stability = stability(parameter.groupValues[1]),
                    )
                }
            }
        }
        current?.let { result += it.build() }
        return result
    }

    private fun parseComposableCsv(file: Path): List<ComposeCompilerFunction> {
        val lines = Files.readAllLines(file).filter(String::isNotBlank)
        require(lines.isNotEmpty()) { "The CSV report is empty." }
        val header = splitCsv(lines.first()).map { it.trim().lowercase() }
        require("name" in header) { "The CSV report has no name column." }
        return lines.drop(1).mapNotNull { line ->
            val values = splitCsv(line)
            fun value(vararg names: String): String? = names.firstNotNullOfOrNull { name -> header.indexOf(name).takeIf { it >= 0 }?.let(values::getOrNull) }
            val qualifiedName = value("name")?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val name = qualifiedName.substringAfterLast('.')
            ComposeCompilerFunction(
                // The compiler's package column holds the function's fully qualified name.
                packageName = value("package", "packagename")?.trim()?.takeIf(String::isNotBlank)
                    ?.let { if (it == name) null else it.removeSuffix(".$name") },
                name = name,
                restartable = value("restartable").toBooleanFlag(),
                skippable = value("skippable").toBooleanFlag(),
                readonly = value("readonly").toBooleanFlag(),
                sourceFile = value("file", "sourcefile"),
                sourceLine = value("line", "sourceline")?.toIntOrNull(),
            )
        }
    }

    private fun parseClasses(file: Path): List<ComposeCompilerClass> {
        val result = mutableListOf<ComposeCompilerClass>()
        var currentName: String? = null
        var currentStability = ComposeCompilerStability.UNKNOWN
        val unstableMembers = mutableListOf<String>()
        fun flush() {
            currentName?.let { result += ComposeCompilerClass(it, currentStability, unstableMembers.toList()) }
            currentName = null
            currentStability = ComposeCompilerStability.UNKNOWN
            unstableMembers.clear()
        }
        Files.readAllLines(file).forEach { line ->
            val classMatch = CLASS.find(line)
            if (classMatch != null) {
                flush()
                currentName = classMatch.groupValues[2]
                currentStability = stability(classMatch.groupValues[1])
            } else if (line.trimStart().startsWith("unstable ")) {
                unstableMembers += line.trim().removePrefix("unstable ")
            } else if (line.trim() == "}") {
                flush()
            }
        }
        flush()
        return result
    }

    private fun splitCsv(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        line.forEach { character ->
            when {
                character == '"' -> quoted = !quoted
                character == ',' && !quoted -> {
                    values += current.toString()
                    current.clear()
                }
                else -> current.append(character)
            }
        }
        values += current.toString()
        return values
    }

    private fun stability(value: String): ComposeCompilerStability =
        when (value.trim().lowercase()) {
            "stable" -> ComposeCompilerStability.STABLE
            "unstable" -> ComposeCompilerStability.UNSTABLE
            "runtime" -> ComposeCompilerStability.RUNTIME
            else -> ComposeCompilerStability.UNKNOWN
        }

    private data class MutableFunction(
        val name: String,
        val restartable: Boolean,
        val skippable: Boolean,
        val readonly: Boolean,
        val parameters: MutableList<ComposeCompilerParameter> = mutableListOf(),
    ) {
        fun build() = ComposeCompilerFunction(
            name = name,
            restartable = restartable,
            skippable = skippable,
            readonly = readonly,
            parameters = parameters.toList(),
        )
    }

    private enum class ReportKind(val suffix: String) {
        COMPOSABLES_TEXT("-composables.txt"),
        COMPOSABLES_CSV("-composables.csv"),
        CLASSES_TEXT("-classes.txt"),
    }

    private fun reportKind(fileName: String): ReportKind? =
        ReportKind.entries.firstOrNull { fileName.endsWith(it.suffix) && fileName.length > it.suffix.length }

    private fun modulePrefix(file: Path): String =
        file.name.removeSuffix(requireNotNull(reportKind(file.name)).suffix)

    private companion object {
        val SUPPORTED_REPORTS = ReportKind.entries.joinToString { "*${it.suffix}" }
        val FUNCTION = Regex("\\bfun\\s+([A-Za-z_][A-Za-z0-9_.$<>]*)\\s*\\(")
        // Runtime-determined stability has no keyword; unused parameters carry an extra prefix.
        val PARAMETER = Regex("^\\s+(?:unused\\s+)?(?:(stable|unstable|runtime|unknown)\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
        val CLASS = Regex("^\\s*(stable|unstable|runtime|unknown)\\s+class\\s+([A-Za-z_][A-Za-z0-9_.$<>]*)", RegexOption.IGNORE_CASE)
    }
}

class ComposeStabilityAnalyzer {
    fun analyze(
        report: ComposeCompilerReport,
        document: ComposeInspectionDocument? = null,
        jank: List<ComposeJankObservation> = emptyList(),
    ): List<ComposeStabilityFinding> {
        val runtime = document?.frame?.roots.orEmpty().flatMap { root -> root.nodes.flatMap(::flatten) }
        return report.functions.map { function ->
            val matchingNodes = runtime.filter { node -> node.name == function.name }
            val matchingJank = jank.filter { it.composableName == function.name && it.isJank }
            ComposeStabilityFinding(
                function = function,
                unstableParameters = function.parameters.filter { it.stability == ComposeCompilerStability.UNSTABLE },
                recomposeCount = matchingNodes.sumOf { it.recomposeCount ?: 0 },
                skipCount = matchingNodes.sumOf { it.skipCount ?: 0 },
                jankFrameCount = matchingJank.size,
                limitations = buildList {
                    if (matchingNodes.isEmpty()) add("No runtime Compose node matched this compiler function.")
                    if (matchingJank.isEmpty()) add("No explicitly attributed Jank observation matched this function.")
                },
            )
        }.sortedWith(
            compareByDescending<ComposeStabilityFinding> { it.jankFrameCount }
                .thenByDescending { it.recomposeCount }
                .thenByDescending { it.unstableParameters.size },
        )
    }

    private fun flatten(node: ComposableNode): List<ComposableNode> = listOf(node) + node.children.flatMap(::flatten)
}

private fun String?.toBooleanFlag(): Boolean = this?.trim()?.lowercase() in setOf("true", "yes", "1")
