package com.androidperformancestudio.desktop

import com.androidperformancestudio.app_desktop.generated.resources.Res
import com.androidperformancestudio.app_desktop.generated.resources.android_performance_studio
import com.androidperformancestudio.ui.localizedStringResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() =
    application {
        val windowState = rememberWindowState(placement = WindowPlacement.Maximized)
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = localizedStringResource(Res.string.android_performance_studio, chinese = false),
        ) {
            SimpleperfWorkspace(window = window)
        }
    }
