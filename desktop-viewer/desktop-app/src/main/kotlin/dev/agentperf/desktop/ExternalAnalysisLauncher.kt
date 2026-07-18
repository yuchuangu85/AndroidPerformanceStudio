package dev.agentperf.desktop

import java.awt.Desktop
import java.net.URI

internal class ExternalAnalysisLauncher(
    private val browse: (URI) -> Unit = { uri -> Desktop.getDesktop().browse(uri) },
) {
    fun openPerfetto() {
        browse(PERFETTO_UI_URI)
    }
}

internal val PERFETTO_UI_URI: URI = URI.create("https://ui.perfetto.dev")
