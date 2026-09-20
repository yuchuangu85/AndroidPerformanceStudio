@file:Suppress("TooGenericExceptionCaught", "MaxLineLength", "ReturnCount", "LongMethod")

package com.androidperformancestudio.memory.capture

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.asSequence

/**
 * Adds the APS LeakCanary bridge and LeakCanary debug dependencies to an Android source project.
 *
 * The operation is intentionally source-project based: an already installed APK cannot be safely
 * modified without rebuilding and re-signing it. A backup of the selected Gradle file and copied
 * agent artifacts are stored under `.aps/leakcanary-agent` so the operation is reversible.
 */
public class AndroidProjectLeakCanaryInjector(
    private val leakCanaryDependency: String = "com.squareup.leakcanary:leakcanary-android:2.14",
    private val serializationDependency: String = "org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0",
) {
    public fun inject(
        projectRoot: Path,
        agentAar: Path,
        protocolJar: Path,
    ): LeakCanaryInjectionResult {
        val root = projectRoot.toAbsolutePath().normalize()
        require(Files.isDirectory(root)) { "Android project root is not a directory: $root" }
        require(agentAar.isRegularFile()) { "LeakCanary bridge AAR is not readable: $agentAar" }
        require(protocolJar.isRegularFile()) { "LeakCanary protocol JAR is not readable: $protocolJar" }

        val gradleFile =
            findApplicationGradleFile(root)
                ?: error("No Android application/library Gradle module with a dependencies block was found under $root")
        val artifactDirectory = root.resolve(".aps/leakcanary-agent").createDirectories()
        val copiedAar = artifactDirectory.resolve(agentAar.name)
        val copiedProtocol = artifactDirectory.resolve(protocolJar.name)
        Files.copy(agentAar, copiedAar, StandardCopyOption.REPLACE_EXISTING)
        Files.copy(protocolJar, copiedProtocol, StandardCopyOption.REPLACE_EXISTING)

        val relativeAar =
            gradleFile.parent
                .relativize(copiedAar)
                .toString()
                .replace('\\', '/')
        val relativeProtocol =
            gradleFile.parent
                .relativize(copiedProtocol)
                .toString()
                .replace('\\', '/')
        val original = Files.readString(gradleFile)
        val marker = "// APS LeakCanary Agent"
        if (original.contains(marker)) {
            return LeakCanaryInjectionResult(
                gradleFile = gradleFile,
                backupFile = null,
                agentAar = copiedAar,
                protocolJar = copiedProtocol,
                changed = false,
                message = "LeakCanary Agent is already injected into ${gradleFile.fileName}.",
            )
        }

        val backup = artifactDirectory.resolve("${gradleFile.fileName}.before-injection.bak")
        Files.copy(gradleFile, backup, StandardCopyOption.REPLACE_EXISTING)
        val kotlinDsl = gradleFile.extension == "kts"
        val dependencyBlock =
            if (kotlinDsl) {
                """$marker\n    debugImplementation(files(\n        \"$relativeAar\",\n        \"$relativeProtocol\",\n    ))\n    debugImplementation(\"$leakCanaryDependency\")\n    debugImplementation(\"$serializationDependency\")\n"""
            } else {
                """$marker\n    debugImplementation files(\"$relativeAar\", \"$relativeProtocol\")\n    debugImplementation \"$leakCanaryDependency\"\n    debugImplementation \"$serializationDependency\"\n"""
            }
        val updated = insertIntoDependencies(original, dependencyBlock)
        val temporary = gradleFile.resolveSibling(".${gradleFile.fileName}.aps.tmp")
        Files.writeString(temporary, updated, StandardCharsets.UTF_8)
        runCatching {
            Files.move(temporary, gradleFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(temporary, gradleFile, StandardCopyOption.REPLACE_EXISTING)
        }
        return LeakCanaryInjectionResult(
            gradleFile = gradleFile,
            backupFile = backup,
            agentAar = copiedAar,
            protocolJar = copiedProtocol,
            changed = true,
            message = "LeakCanary bridge and debug dependency injected into ${gradleFile.fileName}.",
        )
    }

    private fun findApplicationGradleFile(root: Path): Path? =
        Files.walk(root).use { paths ->
            paths
                .asSequence()
                .filter { it.isRegularFile() }
                .filter { it.fileName.toString() in setOf("build.gradle", "build.gradle.kts") }
                .filterNot { it.toString().contains("/.gradle/") || it.toString().contains("/build/") }
                .map { it to Files.readString(it) }
                .filter { (_, content) ->
                    content.contains("com.android.application") ||
                        content.contains("com.android.library")
                }.filter { (_, content) -> content.contains("dependencies") }
                .sortedWith(compareBy({ if (it.first.toString().contains("/app/")) 0 else 1 }, { it.first.toString().length }))
                .map { it.first }
                .firstOrNull()
        }

    private fun insertIntoDependencies(
        original: String,
        dependencyBlock: String,
    ): String {
        val start = original.indexOf("dependencies")
        if (start < 0) return "$original\n\ndependencies {\n$dependencyBlock}\n"
        val opening = original.indexOf('{', start)
        if (opening < 0) return "$original\n\ndependencies {\n$dependencyBlock}\n"
        return original.substring(0, opening + 1) + "\n" + dependencyBlock + original.substring(opening + 1)
    }
}

public data class LeakCanaryInjectionResult(
    val gradleFile: Path,
    val backupFile: Path?,
    val agentAar: Path,
    val protocolJar: Path,
    val changed: Boolean,
    val message: String,
)
