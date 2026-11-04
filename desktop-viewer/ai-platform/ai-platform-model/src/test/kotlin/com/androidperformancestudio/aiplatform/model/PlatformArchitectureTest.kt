package com.androidperformancestudio.aiplatform.model

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformArchitectureTest {
    private val platform =
        Path.of("").toAbsolutePath().let { cwd ->
            generateSequence(cwd) { it.parent }.first { Files.exists(it.resolve("ai-platform-model")) }
        }

    @Test
    fun `platform is an independent composite with no profiler implementation dependencies`() {
        val modules = listOf("model", "registry", "storage", "providers", "runtime")
        val settings = platform.resolve("settings.gradle.kts").readText()
        modules.forEach { module -> assertTrue(settings.contains("\":ai-platform-$module\"")) }
        assertTrue(
            platform.parent
                .resolve("settings.gradle.kts")
                .readText()
                .contains("includeBuild(\"ai-platform\")"),
        )
        modules.forEach { module ->
            val build = platform.resolve("ai-platform-$module/build.gradle.kts").readText()
            listOf("aps.presentation", "aps.app.desktop", "aps.memory", "aps.source.workspace").forEach { forbidden ->
                assertFalse(build.contains(forbidden), "$module depends on Profiler implementation: $forbidden")
            }
        }
    }

    @Test
    fun `business modules cannot import provider sdk types`() {
        val forbiddenImports = Regex("(?m)^import\\s+(?:com\\.openai|com\\.anthropic|ai\\.anthropic|com\\.codex)\\.")
        Files.walk(platform.parent).use { paths ->
            paths
                .filter { path -> path.toString().endsWith(".kt") && path.none { it.name in setOf("build", ".gradle", ".kotlin") } }
                .filter { path ->
                    path.none {
                        it.name in
                            setOf("ai-core", "ai-platform-providers", "ai-platform-provider-codex", "ai-platform-provider-claude")
                    }
                }.forEach { path -> assertFalse(forbiddenImports.containsMatchIn(path.readText()), "SDK leaked into $path") }
        }
    }
}
