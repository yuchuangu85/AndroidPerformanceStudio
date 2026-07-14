package com.androidperformancestudio.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() =
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Android Performance Studio",
        ) {
            val settingsStore = remember { SimpleperfUiSettingsStore.desktop() }
            var settings by remember { mutableStateOf(settingsStore.load()) }
            SimpleperfWorkspace(
                window = window,
                settings = settings,
                onSettingsChanged = { updated ->
                    settings = updated
                    settingsStore.save(updated)
                },
            )
        }
    }
