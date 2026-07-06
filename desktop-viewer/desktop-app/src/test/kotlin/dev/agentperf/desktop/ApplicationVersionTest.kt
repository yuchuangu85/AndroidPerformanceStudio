package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApplicationVersionTest {
    private val projectBuildScript = Files.readString(Path.of("../build.gradle.kts"))
    private val desktopBuildScript = Files.readString(Path.of("build.gradle.kts"))

    @Test
    fun `project version defaults to 0_1_1 and accepts the appVersion property`() {
        assertTrue(projectBuildScript.contains("""val defaultAppVersion = "0.1.1""""))
        assertTrue(
            projectBuildScript.contains(
                """providers.gradleProperty("appVersion").getOrElse(defaultAppVersion)""",
            ),
        )
        assertTrue(projectBuildScript.contains("version = appVersion"))
    }

    @Test
    fun `desktop distributions use the resolved project version`() {
        assertTrue(desktopBuildScript.contains("val appVersion = project.version.toString()"))
        assertTrue(desktopBuildScript.contains("packageVersion = appVersion"))
        assertFalse(desktopBuildScript.contains("""packageVersion = "0.1.1""""))
    }

    @Test
    fun `desktop distributions enable every supported native installer format`() {
        listOf("Deb", "Rpm", "Msi", "Exe", "Dmg", "Pkg").forEach { format ->
            assertTrue(
                desktopBuildScript.contains("TargetFormat.$format"),
                "Missing TargetFormat.$format",
            )
        }
    }

    @Test
    fun `macOS package versions discard leading zero components`() {
        assertTrue(desktopBuildScript.contains("fun macOsPackageVersion(version: String): String"))
        assertTrue(
            desktopBuildScript.contains(
                "indexOfFirst { component -> component.toIntOrNull()?.let { it > 0 } == true }",
            ),
        )
        assertTrue(desktopBuildScript.contains("numericComponents.drop(firstPositiveIndex).joinToString"))
        assertTrue(desktopBuildScript.contains("packageBuildVersion = macVersion"))
    }
}
