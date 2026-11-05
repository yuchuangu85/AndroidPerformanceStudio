package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApplicationDisplayNameTest {
    @Test
    fun `runtime and distribution use AndroidPerformanceStudio`() {
        assertEquals("AndroidPerformanceStudio", APP_DISPLAY_NAME)

        val buildScript = Files.readString(Path.of("build.gradle.kts"))
        assertTrue(
            buildScript.contains(
                """jvmArgs("-Dapple.awt.application.name=AndroidPerformanceStudio")""",
            ),
        )
        assertTrue(
            buildScript.contains("""packageName = desktopAppClassName"""),
        )
        assertTrue(
            buildScript.contains("""val desktopAppClassName = "AndroidPerformanceStudio""""),
        )
    }
}
