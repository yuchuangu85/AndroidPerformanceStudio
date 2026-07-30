package com.androidperformancestudio.desktop

import java.net.URI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExternalAnalysisLauncherTest {
    @Test
    fun `Perfetto home option opens the online trace analyzer`() {
        val opened = mutableListOf<URI>()

        ExternalAnalysisLauncher(opened::add).openPerfetto()

        assertEquals(listOf(URI.create("https://ui.perfetto.dev")), opened)
    }
}
