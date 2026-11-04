@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package com.androidperformancestudio.aiplatform.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowState
import java.awt.Window

@Composable
internal fun FrameWindowScope.AiConversationWindowActivationEffect(
    openRequestId: Long,
    windowState: WindowState,
) {
    val currentWindow = window
    AiConversationWindowActivationEffect(openRequestId, currentWindow) {
        activateAiConversationWindow(currentWindow, windowState)
    }
}

@Composable
internal fun AiConversationWindowActivationEffect(
    openRequestId: Long,
    windowKey: Any,
    onActivate: () -> Unit,
) {
    // Window composition supplies the EDT; retain its context and existing window lifetime.
    LaunchedEffect(windowKey, openRequestId) {
        onActivate()
    }
}

internal fun activateAiConversationWindow(
    window: Window,
    windowState: WindowState,
) {
    windowState.isMinimized = false
    window.toFront()
    window.requestFocus()
}
