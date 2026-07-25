package dev.agentperf.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

internal const val APP_DISPLAY_NAME = "AndroidPerfermanceStudio"

fun main() = application {
    val appIcon = painterResource("icons/app-icon.png")
    Window(
        onCloseRequest = ::exitApplication,
        icon = appIcon,
        title = APP_DISPLAY_NAME,
    ) {
        var settingsRequest by remember { mutableStateOf<SettingsRequest?>(null) }
        var nextSettingsRequestId by remember { mutableStateOf(0L) }
        val settingsMenuInstaller = remember {
            ApplicationSettingsMenuInstaller.desktop()
        }
        DisposableEffect(settingsMenuInstaller) {
            val registration = settingsMenuInstaller.install {
                nextSettingsRequestId += 1
                settingsRequest = SettingsRequest(SettingsPage.GENERAL, nextSettingsRequestId)
            }
            onDispose(registration::close)
        }
        window.minimumSize = java.awt.Dimension(1100, 720)
        UnifiedDesktopApp(settingsRequest = settingsRequest)
    }
}
