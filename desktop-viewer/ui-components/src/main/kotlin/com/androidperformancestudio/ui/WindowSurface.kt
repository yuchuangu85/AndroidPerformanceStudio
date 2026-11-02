package com.androidperformancestudio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.window.FrameWindowScope
import java.awt.Component
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JFrame
import java.awt.Color as AwtColor

/** Keeps native resize exposure and the Compose canvas on the same opaque background. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
public fun FrameWindowScope.WindowSurfaceEffect(background: Color) {
    SideEffect { applyWindowSurfaceBackground(window, AwtColor(background.toArgb())) }
    DisposableEffect(window) {
        val listener =
            if (System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)) {
                WindowResizeRedrawListener(window) { window.renderImmediately() }
            } else {
                null
            }
        listener?.let(window::addComponentListener)
        onDispose { listener?.let(window::removeComponentListener) }
    }
}

internal fun applyWindowSurfaceBackground(window: JFrame, background: AwtColor) {
    val surfaces =
        listOf<Component>(window, window.rootPane, window.layeredPane, window.contentPane) +
            window.contentPane.components
    surfaces.forEach { surface ->
        if (!surface.isBackgroundSet || surface.background != background) surface.background = background
    }
}

internal class WindowResizeRedrawListener(
    private val window: Window,
    private val render: () -> Unit,
) : ComponentAdapter() {
    private var rendering = false

    override fun componentResized(event: ComponentEvent) {
        if (rendering || !window.isDisplayable || !window.isShowing) return
        if (window.width <= 0 || window.height <= 0) return
        rendering = true
        try {
            // Metal otherwise presents the old-size frame while native live resize exposes new space.
            window.validate()
            render()
        } finally {
            rendering = false
        }
    }
}
