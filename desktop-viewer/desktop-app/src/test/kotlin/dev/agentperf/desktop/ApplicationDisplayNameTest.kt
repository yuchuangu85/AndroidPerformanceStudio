package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApplicationDisplayNameTest {
    @Test
    fun `runtime and distribution use AgentPerf Inspector`() {
        assertEquals("AgentPerf Inspector", APP_DISPLAY_NAME)

        val buildScript = Files.readString(Path.of("build.gradle.kts"))
        assertTrue(
            buildScript.contains(
                """jvmArgs("-Dapple.awt.application.name=AgentPerf Inspector")""",
            ),
        )
        assertTrue(
            buildScript.contains("""packageName = "AgentPerf Inspector""""),
        )
    }
}
