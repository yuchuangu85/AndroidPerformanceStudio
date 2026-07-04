package dev.agentperf.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "AgentPerf Desktop Viewer",
    ) {
        var settingsRequest by remember { mutableStateOf(0L) }
        val settingsMenuInstaller = remember {
            ApplicationSettingsMenuInstaller.desktop()
        }
        DisposableEffect(settingsMenuInstaller) {
            val registration = settingsMenuInstaller.install {
                settingsRequest += 1
            }
            onDispose(registration::close)
        }
        window.minimumSize = java.awt.Dimension(1100, 720)
        DesktopViewerApp(settingsRequest = settingsRequest)
    }
}

internal fun shouldOpenSettingsForRequest(request: Long): Boolean = request > 0L
