package com.androidperformancestudio.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.androidperformancestudio.ui.installDefaultWindowMenuBar

internal const val APP_DISPLAY_NAME = "AndroidPerfermanceStudio"

fun main() {
    // The window's own menu bar only attaches after the first Compose frame; until then macOS would
    // show just the Apple and application menus.
    installDefaultWindowMenuBar(
        ApplicationUiSettingsStore.desktop().load().language.resolve(java.util.Locale.getDefault()),
    )
    runApplication()
}

private fun runApplication() = application {
    val appIcon = painterResource("icons/app-icon.png")
    val windowTitle = remember { mutableStateOf(APP_DISPLAY_NAME) }
    val windowState = rememberWindowState(placement = WindowPlacement.Maximized)
    Window(
        onCloseRequest = ::exitApplication,
        icon = appIcon,
        title = windowTitle.value,
        state = windowState,
    ) {
        // Let the app paint the strip behind the native macOS window buttons with the theme.
        SideEffect { MacOsWindowChrome.install(window) }
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
        window.minimumSize = java.awt.Dimension(1280, 720)
        DesktopAppMainPage(windowTitle, settingsRequest = settingsRequest)
    }
}
