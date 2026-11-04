package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PackagedRuntimeModulesTest {
    @Test
    fun `packaged runtime includes the HTTP client used during application startup`() {
        val buildScript = Files.readString(Path.of("build.gradle.kts"))

        assertTrue(
            buildScript.contains("""modules("java.net.http")"""),
            "The minimized runtime must include java.net.http for JdkAiHttpTransport.",
        )
        assertTrue(
            buildScript.contains("""modules("java.sql")"""),
            "The minimized runtime must include java.sql for Simpleperf SQLite storage.",
        )
        assertTrue(
            buildScript.contains("""modules("java.management")"""),
            "The minimized runtime must include java.management for AI coroutine CPU metering.",
        )
    }
}
