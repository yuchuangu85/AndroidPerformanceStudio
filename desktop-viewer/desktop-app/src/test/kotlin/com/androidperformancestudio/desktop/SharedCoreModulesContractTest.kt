package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SharedCoreModulesContractTest {
    @Test
    fun `root build exposes the AI and import libraries as composite builds`() {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        val settings = Files.readString(desktopViewer.resolve("settings.gradle.kts"))

        assertTrue(settings.contains("includeBuild(\"ai-core\")"))
        assertTrue(settings.contains("includeBuild(\"import-core\")"))
        assertTrue(
            Files.readString(desktopViewer.resolve("layout-inspector/presentation/build.gradle.kts"))
                .contains("com.androidperformancestudio:ai-core:0.1.0-SNAPSHOT"),
        )
    }

    @Test
    fun `shared logic libraries remain UI independent`() {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()

        listOf("ai-core", "import-core").forEach { module ->
            val buildScript = Files.readString(desktopViewer.resolve("$module/build.gradle.kts"))
            assertTrue(buildScript.contains("`java-library`"))
            assertFalse(buildScript.contains("org.jetbrains.compose"))
            assertFalse(buildScript.contains("ui-components"))
        }
    }
}
