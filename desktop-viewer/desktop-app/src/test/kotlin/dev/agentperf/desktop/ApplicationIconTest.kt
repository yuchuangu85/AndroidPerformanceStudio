package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ApplicationIconTest {
    private val buildScript = Files.readString(Path.of("build.gradle.kts"))
    private val mainSource = Files.readString(Path.of("src/main/kotlin/dev/agentperf/desktop/Main.kt"))

    @Test
    fun `desktop window loads the bundled application icon`() {
        assertTrue(mainSource.contains("painterResource(\"icons/app-icon.png\")"))
        assertTrue(mainSource.contains("icon = appIcon"))
    }

    @Test
    fun `native distributions use platform specific application icons`() {
        assertTrue(buildScript.contains("src/main/package/macos/app-icon.icns"))
        assertTrue(buildScript.contains("src/main/package/windows/app-icon.ico"))
        assertTrue(buildScript.contains("src/main/package/linux/app-icon.png"))
    }

    @Test
    fun `application icon assets are committed for runtime and packaging`() {
        listOf(
            "src/main/resources/icons/app-icon.png",
            "src/main/package/macos/app-icon.icns",
            "src/main/package/windows/app-icon.ico",
            "src/main/package/linux/app-icon.png",
        ).forEach { relativePath ->
            assertTrue(Files.size(Path.of(relativePath)) > 0, "Expected non-empty icon asset: $relativePath")
        }
    }
}
