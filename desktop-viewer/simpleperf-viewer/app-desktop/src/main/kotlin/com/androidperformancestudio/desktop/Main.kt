package com.androidperformancestudio.desktop

import org.jetbrains.compose.resources.stringResource

import com.androidperformancestudio.app_desktop.generated.resources.Res
import com.androidperformancestudio.app_desktop.generated.resources.android_performance_studio
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
            title = stringResource(Res.string.android_performance_studio),
        ) {
            SimpleperfMainPage(window = window)
        }
    }
