package dev.agentperf.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "AgentPerf Desktop Viewer",
    ) {
        window.minimumSize = java.awt.Dimension(1100, 720)
        DesktopViewerApp()
    }
}
