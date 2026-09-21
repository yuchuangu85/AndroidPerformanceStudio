@file:Suppress("MaxLineLength", "ktlint:standard:max-line-length")

package com.androidperformancestudio.export

import java.nio.file.Files
import java.nio.file.Path

data class ReportTemplate(
    val name: String,
    val content: String,
) {
    init {
        require(name.isNotBlank()) { "template name must not be blank" }
    }
}

class ReportTemplateRenderer {
    fun render(
        template: ReportTemplate,
        values: Map<String, String>,
    ): String {
        val missing =
            PLACEHOLDER
                .findAll(template.content)
                .map { it.groupValues[1] }
                .filterNot(values::containsKey)
                .toSet()
        require(missing.isEmpty()) { "Missing report template values: ${missing.sorted().joinToString()}" }
        return PLACEHOLDER.replace(template.content) { match -> values.getValue(match.groupValues[1]) }
    }

    fun renderTo(
        template: ReportTemplate,
        values: Map<String, String>,
        destination: Path,
    ) {
        destination.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.writeString(destination, render(template, values))
    }

    private companion object {
        val PLACEHOLDER = Regex("\\{\\{([A-Za-z0-9_.-]+)}}")
    }
}
