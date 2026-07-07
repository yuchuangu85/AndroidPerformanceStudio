package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopShortcutPackagingTest {
    private val desktopBuildScript = Files.readString(Path.of("build.gradle.kts"))

    @Test
    fun `native installers request desktop shortcuts where jpackage supports them`() {
        assertTrue(desktopBuildScript.contains("windows {"))
        assertTrue(desktopBuildScript.contains("linux {"))
        assertTrue(Regex("""shortcut\s*=\s*true""").findAll(desktopBuildScript).count() >= 2)
    }
}
