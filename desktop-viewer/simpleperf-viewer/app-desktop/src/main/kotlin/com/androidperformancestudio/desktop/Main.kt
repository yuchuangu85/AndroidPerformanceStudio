package com.androidperformancestudio.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() =
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Android Performance Studio",
        ) {
            SimpleperfWorkspace(window = window)
        }
    }
