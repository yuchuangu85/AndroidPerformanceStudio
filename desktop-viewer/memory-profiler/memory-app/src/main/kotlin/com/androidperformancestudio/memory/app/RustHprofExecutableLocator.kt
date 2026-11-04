package com.androidperformancestudio.memory.app

import java.nio.file.Files
import java.nio.file.Path

internal object RustHprofExecutableLocator {
    fun resolve(): Path {
        val explicit =
            System.getProperty("aps.hprofEngine.path")?.takeIf(String::isNotBlank)
                ?: System.getenv("APS_HPROF_ENGINE")?.takeIf(String::isNotBlank)
        val name =
            if (System.getProperty("os.name").contains("win", ignoreCase = true)) {
                "aps-hprof-engine.exe"
            } else {
                "aps-hprof-engine"
            }
        val resourceRoot = System.getProperty("compose.application.resources.dir")?.takeIf(String::isNotBlank)
        return when {
            explicit != null -> requireExecutable(Path.of(explicit), packaged = false)
            resourceRoot != null ->
                requireExecutable(
                    Path.of(resourceRoot).resolve("hprof-engine").resolve(name),
                    packaged = true,
                )
            else -> developmentExecutable(name)
        }
    }

    private fun developmentExecutable(name: String): Path {
        val working = Path.of("").toAbsolutePath()
        val roots = listOf(working, working.resolve("desktop-viewer"), working.parent).filterNotNull()
        val candidates =
            roots.flatMap { root ->
                listOf(
                    root.resolve("memory-profiler/hprof-engine-rust/target/release/$name"),
                    root.resolve("hprof-engine-rust/target/release/$name"),
                )
            }
        return candidates.firstOrNull(Files::isRegularFile)?.let { requireExecutable(it, packaged = false) }
            ?: throw IllegalStateException(
                "Rust HPROF engine is unavailable. Build :memory-profiler:memory-app:buildRustHprofEngine " +
                    "or set APS_HPROF_ENGINE to its executable. The Kotlin engine remains selectable in Settings.",
            )
    }

    private fun requireExecutable(
        path: Path,
        packaged: Boolean,
    ): Path {
        require(Files.isRegularFile(path) && Files.isExecutable(path)) { "Rust HPROF executable is unavailable: $path" }
        val checksum = path.resolveSibling("${path.fileName}.sha256")
        if (packaged || Files.isRegularFile(checksum)) {
            require(Files.isRegularFile(checksum)) { "Rust HPROF executable checksum is missing: $checksum" }
            val expected = Files.readString(checksum).trim().substringBefore(' ')
            val validHash =
                expected.matches(Regex("[0-9a-fA-F]{64}")) &&
                    ManagedHprofSource.sha256(path).equals(expected, true)
            require(validHash) {
                "Rust HPROF executable checksum mismatch: $path"
            }
        }
        return path.toAbsolutePath().normalize()
    }
}
