package com.androidperformancestudio.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.androidperformancestudio.app_desktop.generated.resources.Res
import com.androidperformancestudio.app_desktop.generated.resources.sp_app_title
import org.jetbrains.compose.resources.stringResource

fun main() =
    application {
        val windowState = rememberWindowState(placement = WindowPlacement.Maximized)
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = stringResource(Res.string.sp_app_title),
        ) {
            SimpleperfMainPage(window = window)
        }
    }
