package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SimpleperfSourceNavigationWiringTest {
    @Test
    fun `CPU source candidate navigation rejects weak resolutions`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppMainPage.kt"))
        val cpuPage = source.substringAfter("AppDestination.SIMPLEPERF ->").substringBefore("AppDestination.PERFETTO ->")

        assertTrue(cpuPage.contains("?.takeIf { it.confidence != ResolutionConfidence.WEAK }"))
        assertTrue(cpuPage.contains("navigator.openSource(candidate.location)"))
    }
}
