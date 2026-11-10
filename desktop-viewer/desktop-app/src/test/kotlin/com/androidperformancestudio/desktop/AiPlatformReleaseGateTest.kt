package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AiPlatformReleaseGateTest {
    @Test
    fun `packaging jobs depend only on release preparation and packaging prerequisites`() {
        val workflow = Files.readString(Path.of("../../.github/workflows/release.yml"))
        assertTrue(workflow.contains("needs: [resolve, prepare-release, build-compose-agent]"))
        assertTrue(workflow.contains("needs: [resolve, prepare-release, package-windows, package-macos, package-linux]"))
        assertTrue(!workflow.contains("ai-qualification"))
        assertTrue(!workflow.contains("\n  test:"))
    }
}
