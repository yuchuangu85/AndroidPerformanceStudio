package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AiPlatformReleaseGateTest {
    @Test
    fun `release test job checks the independent AI composite before packaging`() {
        val workflow = Files.readString(Path.of("../../.github/workflows/release.yml"))
        val testJob = workflow.substringAfter("\n  test:").substringBefore("\n  build-compose-agent:")
        assertTrue(testJob.contains(":ai-platform:checkAll :ai-core:test --no-daemon"))
        assertTrue(testJob.indexOf("Verify independent AI platform") < testJob.indexOf("- name: Run tests"))
        assertTrue(workflow.substringAfter("\n  build-compose-agent:").contains("needs: [resolve, test]"))
    }
}
