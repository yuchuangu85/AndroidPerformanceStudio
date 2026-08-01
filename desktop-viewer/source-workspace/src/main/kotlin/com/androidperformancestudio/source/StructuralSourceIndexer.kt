@file:Suppress("MaxLineLength")

package com.androidperformancestudio.source

public class StructuralSourceIndexer {
    public fun index(
        file: SourceFile,
        content: String,
    ): List<SourceSymbol> =
        when (file.language) {
            SourceLanguage.KOTLIN -> indexKotlin(file, content)
            SourceLanguage.JAVA -> indexJava(file, content)
            SourceLanguage.XML -> indexXml(file, content)
            SourceLanguage.C, SourceLanguage.CPP -> indexNative(file, content)
            SourceLanguage.OTHER -> emptyList()
        }

    private fun indexKotlin(
        file: SourceFile,
        content: String,
    ): List<SourceSymbol> {
        val packageName = packagePattern.find(content)?.groupValues?.get(1).orEmpty()
        val symbols = mutableListOf<SourceSymbol>()
        typePattern.findAll(content).forEach { match ->
            symbols += match.toSymbol(file, content, SourceSymbolKind.TYPE, qualify(packageName, match.groupValues[2]))
        }
        kotlinFunctionPattern.findAll(content).forEach { match ->
            symbols += match.toSymbol(file, content, SourceSymbolKind.FUNCTION, qualify(packageName, match.groupValues[1]), match.groupValues[2])
        }
        return symbols
    }

    private fun indexJava(
        file: SourceFile,
        content: String,
    ): List<SourceSymbol> {
        val packageName = packagePattern.find(content)?.groupValues?.get(1).orEmpty()
        val symbols = mutableListOf<SourceSymbol>()
        javaTypePattern.findAll(content).forEach { match ->
            symbols += match.toSymbol(file, content, SourceSymbolKind.TYPE, qualify(packageName, match.groupValues[2]))
        }
        javaMethodPattern.findAll(content).forEach { match ->
            val methodName = match.groupValues[1]
            if (methodName !in javaKeywords) {
                symbols += match.toSymbol(file, content, SourceSymbolKind.METHOD, qualify(packageName, methodName), match.groupValues[2])
            }
        }
        return symbols
    }

    private fun indexXml(
        file: SourceFile,
        content: String,
    ): List<SourceSymbol> =
        resourcePattern.findAll(content).map { match ->
            match.toSymbol(
                file = file,
                content = content,
                kind = SourceSymbolKind.RESOURCE,
                qualifiedName = "${match.groupValues[1]}/${match.groupValues[2]}",
            )
        }.toList()

    private fun indexNative(
        file: SourceFile,
        content: String,
    ): List<SourceSymbol> =
        nativeFunctionPattern.findAll(content).map { match ->
            match.toSymbol(
                file = file,
                content = content,
                kind = SourceSymbolKind.NATIVE_SYMBOL,
                qualifiedName = match.groupValues[1],
                signature = match.groupValues[2],
            )
        }.toList()

    private fun MatchResult.toSymbol(
        file: SourceFile,
        content: String,
        kind: SourceSymbolKind,
        qualifiedName: String,
        signature: String? = null,
    ): SourceSymbol {
        val line = content.substring(0, range.first).count { it == '\n' } + 1
        return SourceSymbol(
            snapshotId = file.snapshotId,
            relativePath = file.relativePath,
            kind = kind,
            qualifiedName = qualifiedName,
            signature = signature?.trim(),
            startLine = line,
            endLine = line,
        )
    }

    private fun qualify(
        packageName: String,
        name: String,
    ): String = if (packageName.isBlank()) name else "$packageName.$name"

    private companion object {
        val packagePattern: Regex = Regex("(?m)^\\s*package\\s+([A-Za-z_][\\w.]*)")
        val typePattern: Regex = Regex("\\b(class|interface|object|enum\\s+class|sealed\\s+class)\\s+([A-Za-z_]\\w*)")
        val kotlinFunctionPattern: Regex = Regex("\\bfun\\s+(?:<[^>]+>\\s*)?(?:[\\w?.<>]+\\.)?([A-Za-z_]\\w*)\\s*\\(([^)]*)\\)")
        val javaTypePattern: Regex = Regex("\\b(class|interface|enum|record)\\s+([A-Za-z_]\\w*)")
        val javaMethodPattern: Regex = Regex("(?:public|protected|private|static|final|native|synchronized|abstract|\\s)+[\\w<>, ?\\[\\].]+\\s+([A-Za-z_]\\w*)\\s*\\(([^)]*)\\)")
        val nativeFunctionPattern: Regex = Regex("(?m)^[\\w:<>,*&\\s]+\\s+([A-Za-z_~][\\w:]*)\\s*\\(([^;{}]*)\\)\\s*(?:const\\s*)?\\{")
        val resourcePattern: Regex = Regex("@\\+?([a-zA-Z_][\\w]*)/([a-zA-Z_][\\w]*)")
        val javaKeywords: Set<String> = setOf("if", "for", "while", "switch", "catch", "return", "new")
    }
}

public fun sourceLanguage(relativePath: String): SourceLanguage =
    when (relativePath.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> SourceLanguage.KOTLIN
        "java" -> SourceLanguage.JAVA
        "xml" -> SourceLanguage.XML
        "c", "h" -> SourceLanguage.C
        "cc", "cpp", "cxx", "hh", "hpp" -> SourceLanguage.CPP
        else -> SourceLanguage.OTHER
    }
