package com.androidperformancestudio.desktop

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalAnalysisLauncherTest {
    @Test
    fun `Perfetto option opens the online trace analyzer`() {
        val opened = mutableListOf<URI>()

        ExternalAnalysisLauncher(opened::add).openPerfetto()

        assertEquals(listOf(URI.create("https://ui.perfetto.dev")), opened)
    }
}
