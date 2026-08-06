@file:Suppress("NestedBlockDepth", "MagicNumber", "ReturnCount")

package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.HeapDump
import java.nio.file.Files
import java.nio.file.Path

/**
 * R8/ProGuard `mapping.txt` parsed into an obfuscated -> original class-name map.
 *
 * Release builds are typically obfuscated, so HPROF class names read as short names such as
 * `a.b.c`. Importing the matching `mapping.txt` lets analysis (leak detection, Activity counting,
 * histograms) run against the original source class names.
 */
class ProguardMapping(
    val obfuscatedToOriginal: Map<String, String>,
) {
    val originalToObfuscated: Map<String, String> by lazy {
        obfuscatedToOriginal.entries.associate { (obfuscated, original) -> original to obfuscated }
    }

    val isEmpty: Boolean
        get() = obfuscatedToOriginal.isEmpty()

    fun originalName(obfuscated: String): String = obfuscatedToOriginal[obfuscated] ?: obfuscated

    fun obfuscatedName(original: String): String? = originalToObfuscated[original]
}

class ProguardMappingParseException(
    message: String,
) : RuntimeException(message)

object ProguardMappingParser {
    fun parse(path: Path): ProguardMapping {
        val text = Files.readString(path)
        if (text.isBlank()) throw ProguardMappingParseException("mapping.txt is empty: $path")
        return parse(text)
    }

    /**
     * Parses the class-mapping section of an R8/ProGuard mapping file.
     *
     * Class mappings are top-level lines of the form `original -> obfuscated:`:
     * ```text
     * com.example.MainActivity -> a.b.c:
     * android.app.Activity -> android.app.Activity:
     * ```
     * Member (field/method) mappings are indented and are ignored here.
     */
    fun parse(text: String): ProguardMapping {
        val obfuscatedToOriginal = linkedMapOf<String, String>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val isClassLine = !rawLine.startsWith(" ") && line.endsWith(":")
            if (isClassLine) {
                val inner = line.dropLast(1)
                val arrowIndex = inner.lastIndexOf(" -> ")
                if (arrowIndex > 0) {
                    val original = inner.substring(0, arrowIndex).trim()
                    val obfuscated = inner.substring(arrowIndex + 4).trim()
                    if (original.isNotEmpty() && obfuscated.isNotEmpty()) {
                        obfuscatedToOriginal[obfuscated] = original
                    }
                }
            }
        }
        return ProguardMapping(obfuscatedToOriginal)
    }
}

/** Returns a copy of the dump with class names resolved back to their pre-obfuscation names. */
fun HeapDump.withDeobfuscation(mapping: ProguardMapping): HeapDump {
    if (mapping.isEmpty) return this

    fun remap(name: String): String = mapping.originalName(name)
    return copy(
        classes = classes.map { it.copy(name = remap(it.name)) },
        instances =
            instances.map { instance ->
                instance.copy(
                    className = remap(instance.className),
                    references =
                        instance.references.map { reference ->
                            reference.copy(targetClassName = remap(reference.targetClassName))
                        },
                )
            },
        objectArrays = objectArrays.map { it.copy(className = remap(it.className)) },
        // Primitive-array class names are type descriptors (byte[], int[], ...) and never obfuscate.
    )
}

/** Best-effort heuristic for whether a class name is an R8/ProGuard short obfuscated name. */
fun isLikelyObfuscatedClassName(className: String): Boolean {
    if (!className.contains(".")) return false
    val knownFrameworkPrefixes =
        listOf(
            "android.",
            "androidx.",
            "java.",
            "javax.",
            "kotlin.",
            "kotlinx.",
            "com.google.",
            "com.android.",
        )
    if (knownFrameworkPrefixes.any(className::startsWith)) return false
    return className.split(".").all { segment ->
        segment.isNotEmpty() && segment.length <= 3 && segment.all { it.isLowerCase() || it.isDigit() }
    }
}
