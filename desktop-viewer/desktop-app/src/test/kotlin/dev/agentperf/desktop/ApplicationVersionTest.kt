package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApplicationVersionTest {
    @Test
    fun `project and desktop distribution use version 0_1_1`() {
        val projectBuildScript = Files.readString(Path.of("../build.gradle.kts"))
        val desktopBuildScript = Files.readString(Path.of("build.gradle.kts"))

        assertTrue(projectBuildScript.contains("""version = "0.1.1""""))
        assertTrue(desktopBuildScript.contains("""packageVersion = "0.1.1""""))
        assertTrue(desktopBuildScript.contains("""packageVersion = "1.1" // 0.1.1 compatibility"""))
        assertTrue(desktopBuildScript.contains("""packageBuildVersion = "1.1""""))
    }
}
