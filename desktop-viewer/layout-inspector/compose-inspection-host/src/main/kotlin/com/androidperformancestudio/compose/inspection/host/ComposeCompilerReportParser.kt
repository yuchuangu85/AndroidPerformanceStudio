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
    fun parse(directory: Path): ComposeCompilerReport {
        require(Files.isDirectory(directory)) { "Compose Compiler report directory does not exist: $directory" }
        val files = Files.list(directory).use { stream ->
            stream.filter(Path::isRegularFile)
                .filter { it.name.endsWith("-composables.txt") || it.name.endsWith("-composables.csv") || it.name.endsWith("-classes.txt") }
                .sorted()
                .toList()
        }
        val warnings = mutableListOf<String>()
        val functions = mutableListOf<ComposeCompilerFunction>()
        val classes = mutableListOf<ComposeCompilerClass>()
        files.forEach { file ->
            runCatching {
                when {
                    file.name.endsWith("-composables.csv") -> functions += parseComposableCsv(file)
                    file.name.endsWith("-composables.txt") -> functions += parseComposableText(file)
                    file.name.endsWith("-classes.txt") -> classes += parseClasses(file)
                }
            }.onFailure { warnings += "Unable to parse ${file.fileName}: ${it.message}" }
        }
        if (files.isEmpty()) warnings += "No Compose Compiler report files were found."
        return ComposeCompilerReport(
            sourceDirectory = directory.toAbsolutePath().normalize().toString(),
            files = files.map { it.toAbsolutePath().normalize().toString() },
            functions = functions.distinctBy { listOf(it.packageName, it.name, it.sourceFile, it.sourceLine) },
            classes = classes.distinctBy(ComposeCompilerClass::name),
            warnings = warnings,
        )
    }

    private fun parseComposableText(file: Path): List<ComposeCompilerFunction> {
        val result = mutableListOf<ComposeCompilerFunction>()
        var current: MutableFunction? = null
        Files.readAllLines(file).forEach { line ->
            val functionMatch = FUNCTION.find(line)
            if (functionMatch != null) {
                current?.let { result += it.build() }
                val prefix = line.substringBefore("fun")
                current = MutableFunction(
                    name = functionMatch.groupValues[1].substringAfterLast('.'),
                    restartable = prefix.contains("restartable"),
                    skippable = prefix.contains("skippable"),
                    readonly = prefix.contains("readonly"),
                )
            } else {
                val parameter = PARAMETER.find(line)
                if (parameter != null && current != null) {
                    current.parameters += ComposeCompilerParameter(
                        name = parameter.groupValues[2],
                        type = parameter.groupValues[3].trim().trimEnd(',', ')'),
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
        if (lines.isEmpty()) return emptyList()
        val header = splitCsv(lines.first()).map { it.trim().lowercase() }
        return lines.drop(1).mapNotNull { line ->
            val values = splitCsv(line)
            fun value(vararg names: String): String? = names.firstNotNullOfOrNull { name -> header.indexOf(name).takeIf { it >= 0 }?.let(values::getOrNull) }
            val name = value("name", "composable", "function")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            ComposeCompilerFunction(
                packageName = value("package", "packagename"),
                name = name.substringAfterLast('.'),
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

    private companion object {
        val FUNCTION = Regex("\\bfun\\s+([A-Za-z_][A-Za-z0-9_.$<>]*)\\s*\\(")
        val PARAMETER = Regex("^\\s*(stable|unstable|runtime|unknown)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
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
