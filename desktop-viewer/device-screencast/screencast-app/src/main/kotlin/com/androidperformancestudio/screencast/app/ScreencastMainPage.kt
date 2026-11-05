package com.androidperformancestudio.screencast.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.androidperformancestudio.screencast.presentation.ScreencastPage
import com.androidperformancestudio.ui.UiLanguage
import java.nio.file.Path

/**
 * Screencast page entry for the desktop shell, rendered inside its own OS-level window. The
 * controller is remembered per page instance and disposed when the window closes.
 *
 * The window title already carries the "设备投屏" name, so no in-app title row is rendered here, and
 * closing the window relies on the OS-provided window close button — there is no in-app close
 * button.
 */
@Suppress("FunctionNaming")
@Composable
public fun ScreencastMainPage(
    language: UiLanguage,
    androidSdkPath: Path?,
) {
    val controller = remember(androidSdkPath) {
        ScreencastDependencies(androidSdkPath).createController()
    }
    DisposableEffect(controller) {
        controller.refreshDevices()
        onDispose { controller.dispose() }
    }
    ScreencastPage(
        controller = controller,
        modifier = Modifier.fillMaxSize(),
    )
}
